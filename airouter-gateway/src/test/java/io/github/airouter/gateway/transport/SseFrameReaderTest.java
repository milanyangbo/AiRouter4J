package io.github.airouter.gateway.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;

/**
 * SSE 分帧的行为：块边界与事件边界无关。
 *
 * <p>这里断言的四件事，每一件都对应一种只在真实网络上才会出现的情形 ——
 * 而它们共同的特点是：**不会报错，只会让客户端看到一个奇怪的结果**。
 * 一个被截断的多字节字符、一个永远等不到下一行的半截事件，都属于这一类。
 */
final class SseFrameReaderTest {

  private static final DefaultDataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;

  @Test
  @DisplayName("一个块里含多个事件时，每个事件单独产出")
  void multipleEventsInOneChunk() {
    List<String> frames = read("data: one\n\ndata: two\n\n");

    assertThat(frames).containsExactly("one", "two");
  }

  @Test
  @DisplayName("同一个事件被切在两个块之间时，仍然作为一个完整事件产出")
  void eventSplitAcrossChunks() {
    List<String> frames = read("data: be", "ginning\n\n", "data: end\n\n");

    assertThat(frames).containsExactly("beginning", "end");
  }

  @Test
  @DisplayName("多字节字符被切在块边界时，字符本身保持完整")
  void multibyteCharacterSplitAcrossChunks() {
    byte[] whole = "data: 你好\n\n".getBytes(StandardCharsets.UTF_8);
    // 「你」是三个字节，切在它的第一个字节之后 —— 解码如果发生在字节切分之前，
    // 这里就会变成一个替换字符。
    int cut = "data: ".length() + 1;
    List<String> frames = readBytes(head(whole, cut), tail(whole, cut));

    assertThat(frames).containsExactly("你好");
  }

  @Test
  @DisplayName("CRLF 分隔的事件同样被认出来：行尾的回车不属于内容")
  void carriageReturnLineEndingsAreHandled() {
    List<String> frames = read("data: one\r\n\r\ndata: two\r\n\r\n");

    assertThat(frames).containsExactly("one", "two");
  }

  @Test
  @DisplayName("多个 data 行属于同一个事件，用换行拼起来")
  void severalDataLinesFormOneEvent() {
    List<String> frames = read("data: first\ndata: second\n\n");

    assertThat(frames).containsExactly("first\nsecond");
  }

  @Test
  @DisplayName("data: 之后的那一个空格不属于内容")
  void oneLeadingSpaceAfterTheFieldNameIsNotContent() {
    List<String> frames = read("data: spaced\n\n");

    assertThat(frames).containsExactly("spaced");
  }

  @Test
  @DisplayName("只有注释行的事件也产出，以冒号开头 —— 它是上游在保活，不是数据")
  void commentOnlyEventIsEmittedAsAComment() {
    List<String> frames = read(": keep-alive\n\n");

    assertThat(frames).containsExactly(": keep-alive");
  }

  @Test
  @DisplayName("流结束时残留的半截事件不产出：没有以空行结束的事件是不完整的")
  void incompleteEventAtEndOfStreamIsDropped() {
    List<String> frames = read("data: complete\n\ndata: half");

    assertThat(frames).containsExactly("complete");
  }

  private static List<String> read(String... chunks) {
    return Flux.fromArray(chunks)
        .map(chunk -> (DataBuffer) BUFFERS.wrap(chunk.getBytes(StandardCharsets.UTF_8)))
        .transform(SseFrameReader::read)
        .collectList()
        .block();
  }

  private static List<String> readBytes(byte[]... chunks) {
    return Flux.fromArray(chunks)
        .map(chunk -> (DataBuffer) BUFFERS.wrap(chunk))
        .transform(SseFrameReader::read)
        .collectList()
        .block();
  }

  private static byte[] head(byte[] source, int cut) {
    byte[] head = new byte[cut];
    System.arraycopy(source, 0, head, 0, cut);
    return head;
  }

  private static byte[] tail(byte[] source, int cut) {
    byte[] tail = new byte[source.length - cut];
    System.arraycopy(source, cut, tail, 0, tail.length);
    return tail;
  }
}
