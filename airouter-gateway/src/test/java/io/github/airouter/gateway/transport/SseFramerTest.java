package io.github.airouter.gateway.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SSE 分帧。
 *
 * <p>这里守的是**分块的任意性**：上游怎么把字节切成 TCP 段，与帧的边界毫无关系。
 * 一次收到全部、一个字节一个字节地收、把一个多字节字符劈成两半 ——
 * 三种方式必须产出同一串帧。这一条不成立时，故障表现是「偶尔少一段内容」，
 * 而且只在响应够长、网络够忙的时候出现 —— 属于最难复现的那一类。
 */
@Tag("unit")
final class SseFramerTest {

  private static final int MAX_FRAME_BYTES = 1024;

  private static SseFramer framer() {
    return new SseFramer(MAX_FRAME_BYTES);
  }

  private static List<SseFrame> feed(SseFramer framer, String text) {
    return framer.push(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String payload(SseFrame frame) {
    return ((SseFrame.Data) frame).payload();
  }

  @Test
  @DisplayName("一次收到全部：两帧载荷与一个哨兵，且顺序不变")
  void wholeBodyAtOnce() {
    List<SseFrame> frames = feed(framer(), "data: {\"a\":1}\n\ndata: {\"b\":2}\n\ndata: [DONE]\n\n");

    assertThat(frames).hasSize(3);
    assertThat(payload(frames.get(0))).isEqualTo("{\"a\":1}");
    assertThat(payload(frames.get(1))).isEqualTo("{\"b\":2}");
    assertThat(payload(frames.get(2))).isEqualTo("[DONE]");
  }

  /**
   * 逐字节喂入：结果与一次收到全部**完全相同**。
   *
   * <p>这条是这一份测试存在的理由：分帧器一旦依赖「块通常是完整的」这个假设，
   * 差的就是这一类故障。
   */
  @Test
  @DisplayName("一个字节一个字节地收：结果与一次收到全部相同")
  void byteByByte() {
    String body = "data: {\"a\":1}\n\ndata: [DONE]\n\n";
    List<SseFrame> expected = feed(framer(), body);

    SseFramer drip = framer();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    List<SseFrame> actual = new ArrayList<>();
    for (byte single : bytes) {
      actual.addAll(drip.push(new byte[] {single}));
    }

    assertThat(actual).isEqualTo(expected);
  }

  /**
   * 一个中文字符被劈在两个块之间。
   *
   * <p>按字符拼的实现会在这里产出替换字符，表现是中文响应在块边界乱码。
   * 分帧器在字节层面缓存，因此劈开的位置不影响结果。
   */
  @Test
  @DisplayName("一个多字节字符被切成两半：合并后仍然完整，不出现替换字符")
  void multiByteCharacterSplitAcrossChunks() {
    SseFramer framer = framer();
    String body = "data: {\"content\":\"中\"}\n\n";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    int cut = body.indexOf('中') + 1;

    framer.push(Arrays.copyOfRange(bytes, 0, cut));
    List<SseFrame> frames = framer.push(Arrays.copyOfRange(bytes, cut, bytes.length));

    assertThat(frames).hasSize(1);
    assertThat(payload(frames.get(0))).isEqualTo("{\"content\":\"中\"}");
  }

  /**
   * 空 {@code data:} 帧**仍然是一帧**（SSE 里它是「一个内容为空的事件」），
   * 只是不当成内容 —— 那一步由 codec 做。分帧器不做这个判断，
   * 否则「哪种空帧算保活」会变成传输层的知识。
   */
  @Test
  @DisplayName("空 data 帧照样被分派：它是不是保活由 codec 判断，不是由分帧器")
  void emptyDataIsStillAFrame() {
    List<SseFrame> frames = feed(framer(), "data:\n\ndata: x\n\n");

    assertThat(frames).hasSize(2);
    assertThat(payload(frames.get(0))).isEmpty();
    assertThat(payload(frames.get(1))).isEqualTo("x");
  }

  @Test
  @DisplayName("注释是注释，不是内容：上游保活认得出来")
  void commentIsAComment() {
    List<SseFrame> frames = feed(framer(), ": keepalive\n\ndata: x\n\n");

    assertThat(frames.get(0)).isEqualTo(new SseFrame.Comment(" keepalive"));
    assertThat(payload(frames.get(1))).isEqualTo("x");
  }

  @Test
  @DisplayName("多行 data 以换行相接：一个事件可以跨多行")
  void multipleDataLinesAreJoined() {
    List<SseFrame> frames = feed(framer(), "data: one\ndata: two\n\n");

    assertThat(payload(frames.get(0))).isEqualTo("one\ntwo");
  }

  @Test
  @DisplayName("CRLF 与 LF 等价：那一边写出来的流同样能读")
  void carriageReturnIsStripped() {
    List<SseFrame> frames = feed(framer(), "data: x\r\n\r\n");

    assertThat(payload(frames.get(0))).isEqualTo("x");
  }

  @Test
  @DisplayName("未建模的字段不影响内容：event / id / retry 都不是正文")
  void unmodeledFieldsDoNotBecomeContent() {
    List<SseFrame> frames = feed(framer(), "event: message\nid: 7\ndata: x\n\n");

    assertThat(frames).hasSize(1);
    assertThat(payload(frames.get(0))).isEqualTo("x");
  }

  /**
   * 流在中途断掉：残缺的那一段**不**被分派。
   *
   * <p>把它当成一帧，客户端会拿到一段不完整的 JSON，而「不完整」在这里无从判断；
   * 不当成一帧，「上游没把话说完」就是能被看见的（终态缺席）。
   */
  @Test
  @DisplayName("没有以空行收尾的那一段不被分派：半截响应不会冒充一个完整事件")
  void incompleteTrailerIsNotDispatched() {
    SseFramer framer = framer();
    List<SseFrame> frames = feed(framer, "data: {\"a\":1}\n\ndata: {\"b\":");

    assertThat(frames).hasSize(1);
    assertThat(payload(frames.get(0))).isEqualTo("{\"a\":1}");
    assertThat(framer.finish()).isEmpty();
  }

  /**
   * 单帧上限。
   *
   * <p>它是「字节缓冲」这条腿里唯一能在解码之前生效的一个：
   * 一个永远不发换行的上游会让缓存无限增长，而累积内容上限作用在解码之后，管不到它。
   */
  @Test
  @DisplayName("一帧超过上限就报错：不给「缓存无限增长」留一条静默的路")
  void frameBeyondLimitIsRejected() {
    SseFramer framer = framer();

    assertThatThrownBy(() -> framer.push(new byte[MAX_FRAME_BYTES + 1]))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("1024");
  }

  /** 上限跨块累计：一次只发一点点、但永远不发换行的上游同样要撞到它。 */
  @Test
  @DisplayName("上限跨块累计：每次都不到上限、但合计超限时仍然报错")
  void limitIsCountedAcrossChunks() {
    SseFramer framer = framer();
    byte[] chunk = new byte[MAX_FRAME_BYTES / 10 + 1];

    assertThatThrownBy(() -> {
      for (int i = 0; i < 10; i++) {
        framer.push(chunk);
      }
    }).isInstanceOf(IllegalStateException.class);
  }

  /** 反过来：一帧分派之后计数归零，否则一条长流会在若干帧之后误撞上限。 */
  @Test
  @DisplayName("一帧分派之后计数归零：长流不会因为累计而误撞上限")
  void counterResetsAfterDispatch() {
    SseFramer framer = framer();
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      body.append("data: ").append("x".repeat(40)).append("\n\n");
    }

    assertThat(feed(framer, body.toString())).hasSize(20);
  }
}
