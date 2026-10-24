package io.github.airouter.gateway.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

/**
 * 把上游的 SSE 字节流切成事件。
 *
 * <p>切分发生在**字节层**，解码发生在事件完成之后。这不是实现细节而是正确性问题：
 * 一个多字节字符完全可能被 TCP 的分段切在两个块之间，若先解码再切，那个字符会变成一个替换字符，
 * 客户端看到的就是「模型偶尔吐出乱码」，而这类现象在日志里没有任何痕迹。
 *
 * <p>它不知道 SSE 载荷里装的是什么：不解析 JSON，不认识 {@code [DONE]}。
 * 那是 Provider 的事（结构规则 R5：出站管道只搬字节）。
 *
 * <p>产出的每个元素是一个完整事件的载荷：
 * <ul>
 *   <li>有 {@code data:} 行时，是各 data 行用 {@code \n} 拼接的结果（SSE 允许多个 data 行）；
 *   <li>只有注释行（上游的保活）时，是 {@code :} 加注释内容 —— 以 {@code :} 开头即表示它不是数据；
 *   <li>其余字段（{@code event:} / {@code id:} / {@code retry:}）不参与输出：
 *       网关这一层不按事件名分派，也不需要上游的事件编号。
 * </ul>
 */
public final class SseFrameReader {

  private static final byte LINE_FEED = (byte) '\n';

  private static final byte CARRIAGE_RETURN = (byte) '\r';

  private SseFrameReader() {
  }

  /**
   * 把字节块流变成事件流。
   *
   * <p>每次订阅都新建一份累积状态：同一个 {@code Flux} 被订阅两次时，两次各自从零开始，
   * 而不是共用一份「上次读到哪里」。
   *
   * @param chunks 上游的字节块，块边界与事件边界无关
   * @return 事件载荷流；块内没有完整事件时不产出元素
   */
  public static Flux<String> read(Flux<DataBuffer> chunks) {
    return Flux.defer(() -> {
      Accumulator accumulator = new Accumulator();
      return chunks.concatMap(accumulator::accept).concatWith(Flux.defer(accumulator::finish));
    });
  }

  /** 一次订阅内的累积状态。它不是线程安全的，也不需要是：同一条流只在一个订阅里被顺序消费。 */
  private static final class Accumulator {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private final List<String> data = new ArrayList<>();

    private final List<String> comments = new ArrayList<>();

    /**
     * 吃进一个字节块，产出其中已经完整的事件。
     *
     * @param buffer 字节块，读完后释放
     * @return 这一块里已经完整的事件，可能是零个
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
          consumeLine(buffered, lineStart, i, emitted);
          lineStart = i + 1;
        }
      }
      pending.write(buffered, lineStart, buffered.length - lineStart);
      return Flux.fromIterable(emitted);
    }

    /**
     * 流结束时的收尾。
     *
     * <p>残留的半截事件被丢弃而不是补发出去：没有以空行结束的事件是不完整的，
     * 把它当成事件会让下游收到一个被截断的 JSON。缺终态这件事由 Provider 补发
     * {@code Failed} 来处理，不由这里假装看完了。
     */
    Flux<String> finish() {
      pending.reset();
      data.clear();
      comments.clear();
      return Flux.empty();
    }

    private void consumeLine(byte[] buffered, int from, int to, List<String> emitted) {
      int end = to;
      if (end > from && buffered[end - 1] == CARRIAGE_RETURN) {
        end--;
      }
      String line = new String(buffered, from, end - from, StandardCharsets.UTF_8);
      if (line.isEmpty()) {
        emitCurrent(emitted);
        return;
      }
      if (line.startsWith("data:")) {
        data.add(stripOneLeadingSpace(line.substring("data:".length())));
        return;
      }
      if (line.startsWith(":")) {
        comments.add(line.substring(1));
      }
    }

    private void emitCurrent(List<String> emitted) {
      if (!data.isEmpty()) {
        emitted.add(String.join("\n", data));
      } else if (!comments.isEmpty()) {
        emitted.add(":" + String.join("\n", comments));
      }
      data.clear();
      comments.clear();
    }

    private static String stripOneLeadingSpace(String value) {
      return value.startsWith(" ") ? value.substring(1) : value;
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
