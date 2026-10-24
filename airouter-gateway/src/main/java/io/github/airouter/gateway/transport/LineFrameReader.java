package io.github.airouter.gateway.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

/**
 * 把上游的**逐行 JSON**（NDJSON）切成事件。
 *
 * <p>它与 {@link SseFrameReader} 是并列关系，不是它的变体。两种约定在字节层就有区别，
 * 而且区别不在细节上：
 *
 * <table>
 *   <caption>两种分帧约定的差异</caption>
 *   <tr><td></td><td>SSE</td><td>逐行 JSON</td></tr>
 *   <tr><td>事件前缀</td><td>{@code data:}</td><td>无</td></tr>
 *   <tr><td>事件分隔</td><td>空行</td><td>换行本身</td></tr>
 *   <tr><td>结束标志</td><td>{@code [DONE]} 之类的哨兵</td><td>没有，由最后一帧的内容宣告</td></tr>
 *   <tr><td>注释/保活</td><td>{@code :} 开头的行</td><td>没有这一种行</td></tr>
 * </table>
 *
 * <p>因此「SSE 的读法读不出逐行 JSON 的流」不是缺陷，而是这两种 wire 形态不同的证据：
 * SSE 的读法会把每一行当成既非 {@code data:} 又非空行的东西丢掉，一条事件也产不出来。
 * 反过来把它们塞进同一个读法里——比如遇到不带前缀的行就当数据——会让「这一行到底是什么」
 * 变成一个只能靠猜的问题，而猜错的后果是一个被截断的 JSON 被当成完整事件交给下游。
 *
 * <p>与 SSE 那一侧一样：切分在字节层，不解析 JSON，不认识任何字段。
 * 一份字节在这里被切开之后才交给 Provider 去解释（ADR-0005 的 CHUNK_PARSE_COUNT=1
 * 说的是解析次数，切开不算解析）。
 */
public final class LineFrameReader {

  private static final byte LINE_FEED = (byte) '\n';

  private static final byte CARRIAGE_RETURN = (byte) '\r';

  private LineFrameReader() {
  }

  /**
   * 把字节块流变成事件流。
   *
   * <p>每次订阅都新建一份累积状态，与 {@link SseFrameReader#read} 同理：
   * 同一个 {@code Flux} 被订阅两次时两次各自从零开始。
   *
   * @param chunks 上游的字节块，块边界与行边界无关
   * @return 事件流；每个元素是一整行（不含行分隔符）
   */
  public static Flux<String> read(Flux<DataBuffer> chunks) {
    return Flux.defer(() -> {
      Accumulator accumulator = new Accumulator();
      return chunks.concatMap(accumulator::accept);
    });
  }

  /** 一次订阅内的累积状态。同一条流只在一个订阅里被顺序消费，因此不需要线程安全。 */
  private static final class Accumulator {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /**
     * 吃进一个字节块，产出其中已经完整的行。
     *
     * @param buffer 字节块，读完后释放
     * @return 这一块里已经完整的行，可能是零行
     */
    Flux<String> accept(DataBuffer buffer) {
      byte[] chunk = drain(buffer);
      pending.write(chunk, 0, chunk.length);
      byte[] buffered = pending.toByteArray();
      pending.reset();

      List<String> emitted = new ArrayList<>();
      int lineStart = 0;
      for (int i = 0; i < buffered.length; i++) {
        if (buffered[i] == LINE_FEED) {
          String line = text(buffered, lineStart, i);
          lineStart = i + 1;
          // 空行不产出：两种约定里它都不承载事件。逐行 JSON 的流在末尾常有一个多余的换行，
          // 把它变成一条事件会让下游收到一个空串，而空串不是合法 JSON。
          if (!line.isEmpty()) {
            emitted.add(line);
          }
        }
      }
      pending.write(buffered, lineStart, buffered.length - lineStart);
      return Flux.fromIterable(emitted);
    }

    private static String text(byte[] buffered, int from, int to) {
      int end = to;
      if (end > from && buffered[end - 1] == CARRIAGE_RETURN) {
        end--;
      }
      return new String(buffered, from, end - from, StandardCharsets.UTF_8);
    }

    /** 取出字节并释放缓冲：Netty 的池化缓冲不释放会一直占着，而且不会有任何报错提示。 */
    private static byte[] drain(DataBuffer buffer) {
      byte[] bytes = new byte[buffer.readableByteCount()];
      buffer.read(bytes);
      DataBufferUtils.release(buffer);
      return bytes;
    }
  }
}
