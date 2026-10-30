package io.github.airouter.gateway.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.UpstreamFailure;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 出站这一侧的**字节**：怎么把它切成帧，以及对端不在的时候它是什么。
 *
 * <p>两件事合成一份的理由不是「都是 transport 包」，而是它们看的是同一条边界的两头：
 * 分帧器处理「上游把字节切成了什么样」，出站口处理「根本没字节过来」。
 * 分开写的时候，「字节到了但没拼好」与「字节没到」是两份不同的测试，
 * 于是「上游没把话说完」这件两者都会遇到的事没有一处把它当成一件事来看 ——
 * 半截响应在分帧器这里是「不派发」，在出站口那里是「UPSTREAM_FAILURE 而不是空流」，
 * 判据不同，但说的是同一句：**没说完不等于说完了但内容是空的**。
 *
 * <p>前半段（分帧）守的是**分块的任意性**：上游怎么把字节切成 TCP 段，与帧的边界毫无关系。
 * 一次收到全部、一个字节一个字节地收、把一个多字节字符劈成两半 ——
 * 三种方式必须产出同一串帧。这一条不成立时，故障表现是「偶尔少一段内容」，
 * 而且只在响应够长、网络够忙的时候出现 —— 属于最难复现的那一类。
 *
 * <p>后半段（对端缺席）守的是**两种失败的区分**：「上游地址不可达」必须在类型上就与
 * 「上游拒绝了」分开，前者是网关与上游之间什么都没发生，后者是上游明确地说了不。
 * 把两者都收成一个 502，看日志的人会去查一个根本没参与的请求。
 * 那里用内核刚释放的端口代替「挑一个没人用的端口」：后者在别人占用的那一刻会
 * 变成一条偶发绿（连上了却没断言任何东西）。
 */
@Tag("unit")
final class OutboundBytesTest {

  private static final int MAX_FRAME_BYTES = 1024;

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  /** 这一份测试里那个实例的标识。它与真实配置无关，只需要能进出在途表。 */
  private static final String PROBE = "probe-instance";

  /** 聚合读取的上限。这里只是让它显式在场：这一份测试不打算撞它。 */
  private static final int BUFFER_BYTES = 1 << 20;

  private static final int WIRE_MAX_FRAME_BYTES = 1 << 20;

  private static SseFramer framer() {
    return new SseFramer(MAX_FRAME_BYTES);
  }

  private static List<UpstreamFrame> feed(SseFramer framer, String text) {
    return framer.push(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String payload(UpstreamFrame frame) {
    return ((UpstreamFrame.Data) frame).payload();
  }

  @Test
  @DisplayName("一次收到全部：两帧载荷与一个哨兵，且顺序不变")
  void wholeBodyAtOnce() {
    List<UpstreamFrame> frames = feed(framer(), "data: {\"a\":1}\n\ndata: {\"b\":2}\n\ndata: [DONE]\n\n");

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
    List<UpstreamFrame> expected = feed(framer(), body);

    SseFramer drip = framer();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    List<UpstreamFrame> actual = new ArrayList<>();
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
    List<UpstreamFrame> frames = framer.push(Arrays.copyOfRange(bytes, cut, bytes.length));

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
    List<UpstreamFrame> frames = feed(framer(), "data:\n\ndata: x\n\n");

    assertThat(frames).hasSize(2);
    assertThat(payload(frames.get(0))).isEmpty();
    assertThat(payload(frames.get(1))).isEqualTo("x");
  }

  @Test
  @DisplayName("注释是注释，不是内容：上游保活认得出来")
  void commentIsAComment() {
    List<UpstreamFrame> frames = feed(framer(), ": keepalive\n\ndata: x\n\n");

    assertThat(frames.get(0)).isEqualTo(new UpstreamFrame.Comment(" keepalive"));
    assertThat(payload(frames.get(1))).isEqualTo("x");
  }

  @Test
  @DisplayName("多行 data 以换行相接：一个事件可以跨多行")
  void multipleDataLinesAreJoined() {
    List<UpstreamFrame> frames = feed(framer(), "data: one\ndata: two\n\n");

    assertThat(payload(frames.get(0))).isEqualTo("one\ntwo");
  }

  @Test
  @DisplayName("CRLF 与 LF 等价：那一边写出来的流同样能读")
  void carriageReturnIsStripped() {
    List<UpstreamFrame> frames = feed(framer(), "data: x\r\n\r\n");

    assertThat(payload(frames.get(0))).isEqualTo("x");
  }

  @Test
  @DisplayName("未建模的字段不影响内容：event / id / retry 都不是正文")
  void unmodeledFieldsDoNotBecomeContent() {
    List<UpstreamFrame> frames = feed(framer(), "event: message\nid: 7\ndata: x\n\n");

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
    List<UpstreamFrame> frames = feed(framer, "data: {\"a\":1}\n\ndata: {\"b\":");

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

  @Test
  @DisplayName("上游地址不可达：判定为 UPSTREAM_FAILURE，而不是成功也不是超时")
  void unreachableUpstreamIsAnUpstreamFailure() {
    OutboundWire wire = wire(closedPort());

    assertThatThrownBy(() -> wire.post("/chat/completions", Map.of(), "{}").block())
        .isInstanceOf(UpstreamFailure.class)
        .satisfies(failure -> {
          UpstreamFailure upstream = (UpstreamFailure) failure;
          assertThat(upstream.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
          assertThat(upstream.error().type()).isEqualTo("upstream_unreachable");
          assertThat(upstream.error().httpStatus()).isEqualTo(502);
        });
  }

  /**
   * 流式那条路同样要得到「够不着上游」，而且要在**订阅时**就得到。
   *
   * <p>它容易被写成「流正常结束」（空流）：那样客户端收到一个 200 加一段空流，
   * 看起来像「模型什么都没说」，而实际上一次请求都没发出去。
   * 这与上面「半截响应不被派发」是同一条边界的另一头。
   */
  @Test
  @DisplayName("上游地址不可达时，流式同样判定为 UPSTREAM_FAILURE，而不是一条空流")
  void unreachableUpstreamFailsTheStreamToo() {
    OutboundWire wire = wire(closedPort());

    assertThatThrownBy(() -> wire.postStream("/chat/completions", Map.of(), "{}", TIMEOUT,
        SseFramer::new).collectList().block())
        .isInstanceOf(UpstreamFailure.class)
        .satisfies(failure -> {
          UpstreamFailure upstream = (UpstreamFailure) failure;
          assertThat(upstream.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
          assertThat(upstream.error().type()).isEqualTo("upstream_unreachable");
        });
  }

  /**
   * 在途计数：**每一次订阅加一，任何结束方式都减一**。
   *
   * <p>它是 {@code least-connections} 的输入，而这个策略对数错的最好报复方式是「永远不再选某一台机器」——
   * 因此这里要同时断言两个方向：加一发生在订阅时（{@code doOnSubscribe} 里读到的是 1），
   * 而**失败也算结束**（回到 0）。漏掉后者的经典后果是：一次连不上的调用永远占着一个名额。
   */
  @Test
  @DisplayName("出站口记着自己在途几次：订阅时加一，失败与取消都回到零")
  void inFlightCountingCountsEveryExit() {
    ActiveCallMeter meter = new ActiveCallMeter();
    OutboundWire wire = wire(meter, closedPort());
    AtomicInteger duringCall = new AtomicInteger(-1);

    assertThatThrownBy(() -> wire.post("/chat/completions", Map.of(), "{}")
        .doOnSubscribe(ignored -> duringCall.set(meter.activeOf(PROBE)))
        .block())
        .isInstanceOf(UpstreamFailure.class);

    assertThat(meter.activeOf(PROBE))
        .as("登记才有值：**没有请求过不等于不知道**，它应当答 0 而不是 null")
        .isEqualTo(0);
    assertThat(duringCall.get()).as("调用还在途中的计数").isEqualTo(1);
    assertThat(meter.activeOf("没有这个实例"))
        .as("没有登记过的实例：这里要说不知道，而不是说它闲着")
        .isNull();
  }

  /**
   * 流式那一条路同样记账。
   *
   * <p>**只断言「结束之后是 0」是不够的**：登记过的实例本来就该答 0，
   * 于是「压根没有计数」这种写法也能让它绿 —— 探红抓到的正是这个。
   * 因此这里与非流式那条一样，要看到**订阅时在途是 1**。
   */
  @Test
  @DisplayName("流式那一条路同样记账：订阅时加一，失败也要把名额还回去")
  void inFlightCountingCoversStreamingToo() {
    ActiveCallMeter meter = new ActiveCallMeter();
    OutboundWire wire = wire(meter, closedPort());
    AtomicInteger duringCall = new AtomicInteger(-1);

    assertThatThrownBy(() -> wire.postStream("/chat/completions", Map.of(), "{}", TIMEOUT,
        SseFramer::new)
        .doOnSubscribe(ignored -> duringCall.set(meter.activeOf(PROBE)))
        .collectList().block())
        .isInstanceOf(UpstreamFailure.class);

    assertThat(duringCall.get()).as("流式调用还在途中的计数").isEqualTo(1);
    assertThat(meter.activeOf(PROBE)).as("流式失败之后").isEqualTo(0);
  }

  private static OutboundWire wire(int port) {
    return wire(new ActiveCallMeter(), port);
  }

  private static OutboundWire wire(ActiveCallMeter meter, int port) {
    return new OutboundWire(PROBE, meter, "http://127.0.0.1:" + port + "/v1", TIMEOUT, BUFFER_BYTES,
        WIRE_MAX_FRAME_BYTES);
  }

  /**
   * 一个确定没有在监听的端口。
   *
   * <p>先让内核分配再立刻关掉，得到的是「刚刚还在用、现在没人监听」的端口：
   * 连接它必定被拒，而占用它的可能性比随手挑一个端口号小得多。
   */
  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException failure) {
      throw new IllegalStateException("拿不到一个空闲端口", failure);
    }
  }
}
