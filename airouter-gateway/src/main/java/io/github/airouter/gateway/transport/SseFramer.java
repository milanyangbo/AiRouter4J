package io.github.airouter.gateway.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SSE 分帧器：把一串任意切分的字节块变成一帧一帧的 {@link UpstreamFrame}。
 *
 * <p>它在**字节层面**缓存，不在字符层面：一行没攒够就留着，攒够一行才解码。
 * 这正好解决了「一个多字节字符被切在两个 TCP 段之间」—— UTF-8 的多字节序列里不可能出现
 * {@code 0x0A}，所以按字节找换行再解码，永远不会把一个字符劈开。
 * 反过来（先把字节解码成字符串再拼）会在块边界上产生替换字符，表现是中文响应在块边界乱码。
 *
 * <p>分块的任意性是被严肃对待的：**上游怎么切块不影响结果**。
 * 一次收到全部、一个字节一个字节地收、或者把一个中文字符劈成两半 —— 三种方式产出的帧序列必须相同。
 *
 * <p>分派规则按 SSE：一个事件由若干 {@code data:} 行构成，以空行结束；只有 {@code :} 行的块是注释
 * （上游的保活）。其余字段（{@code event:} / {@code id:} / {@code retry:}）现阶段不建模 ——
 * 它们既不是内容，也不影响终态。
 *
 * <p>它是 {@link UpstreamFramer} 的**第一种**读法。逐行 JSON 那一种随 {@code ollama} 形态进来，
 * 届时新增的是这个接口的另一个实现，不是给这里加一个开关（ADR-0006 第 3 节）。
 */
public final class SseFramer implements UpstreamFramer {

  private static final byte LINE_FEED = (byte) '\n';

  private static final String DATA_FIELD = "data:";

  private static final String COMMENT_MARK = ":";

  private final int maxFrameBytes;

  /** 未攒够一行的字节。它是字节而不是字符串，理由见类注释第一段。 */
  private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();

  private final List<String> dataLines = new ArrayList<>();

  private final List<String> comments = new ArrayList<>();

  /** 当前这一帧已经收下的字节数。它是「这一帧有多大」的唯一账本，跨块累计。 */
  private int bufferedBytes;

  /**
   * @param maxFrameBytes 单帧上限。一个不发换行的上游会让缓存无限增长，
   *                      而这个上限是唯一能在**解码之前**拦住它的东西 ——
   *                      累积内容上限作用在解码之后，管不到「一帧还没成形」的那段时间
   */
  public SseFramer(int maxFrameBytes) {
    this.maxFrameBytes = maxFrameBytes;
  }

  /**
   * 喂入一块字节。
   *
   * @param chunk 这一块字节，长度可以为 0
   * @return 这一块之后**已经完整**的帧。块的边界与帧的边界没有任何关系，
   *         因此一块可能带来零帧、一帧或多帧
   * @throws IllegalStateException 单帧超过上限时抛出。它是一个「上游送来了不可能合法的东西」
   *         的信号，由调用方决定这算哪一种失败 —— 分帧器不下成败的结论
   */
  @Override
  public List<UpstreamFrame> push(byte[] chunk) {
    List<UpstreamFrame> frames = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < chunk.length; i++) {
      if (chunk[i] == LINE_FEED) {
        append(chunk, start, i - start);
        String line = new String(pendingLine.toByteArray(), StandardCharsets.UTF_8);
        pendingLine.reset();
        UpstreamFrame frame = consume(stripCarriageReturn(line));
        if (frame != null) {
          frames.add(frame);
        }
        start = i + 1;
      }
    }
    append(chunk, start, chunk.length - start);
    return List.copyOf(frames);
  }

  /**
   * 流结束了。
   *
   * <p>残留在缓存里、没有以空行收尾的那一段**不被分派**：SSE 里一个事件要等到空行才成立，
   * 连接在中途断掉时那段内容是不完整的，把它当成一帧等于把半截响应当成完整的。
   * 「上游没把话说完」因此是能被看见的（终态缺席），而不是被悄悄补成一个成功。
   *
   * @return 恒为空列表。保留返回值是为了让调用方不必记住「结束时要不要再看一眼」
   */
  @Override
  public List<UpstreamFrame> finish() {
    pendingLine.reset();
    dataLines.clear();
    comments.clear();
    bufferedBytes = 0;
    return List.of();
  }

  private void append(byte[] chunk, int offset, int length) {
    if (length > 0) {
      pendingLine.write(chunk, offset, length);
      bufferedBytes += length;
    }
    if (bufferedBytes > maxFrameBytes) {
      throw new IllegalStateException("SSE 单帧超过 " + maxFrameBytes + " 字节仍未见换行");
    }
  }

  /** 一行：空行分派一个事件；字段行只登记，不产生帧。 */
  private UpstreamFrame consume(String line) {
    if (line.isEmpty()) {
      return dispatch();
    }
    if (line.startsWith(COMMENT_MARK)) {
      comments.add(line.substring(1));
      return null;
    }
    if (line.startsWith(DATA_FIELD)) {
      dataLines.add(stripOneLeadingSpace(line.substring(DATA_FIELD.length())));
      return null;
    }
    return null;
  }

  /** 一个块结束：有 data 行就是一帧载荷，否则是注释。两者都空的空块不产生帧。 */
  private UpstreamFrame dispatch() {
    bufferedBytes = 0;
    if (!dataLines.isEmpty()) {
      String payload = String.join("\n", dataLines);
      dataLines.clear();
      comments.clear();
      return new UpstreamFrame.Data(payload);
    }
    if (!comments.isEmpty()) {
      String text = String.join("\n", comments);
      comments.clear();
      return new UpstreamFrame.Comment(text);
    }
    return null;
  }

  private static String stripCarriageReturn(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  /** SSE 的字段值允许带一个前导空格，它不属于值。 */
  private static String stripOneLeadingSpace(String value) {
    return value.startsWith(" ") ? value.substring(1) : value;
  }
}
