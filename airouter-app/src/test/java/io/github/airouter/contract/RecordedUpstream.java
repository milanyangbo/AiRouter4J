package io.github.airouter.contract;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回放录制字节的上游。
 *
 * <p>它替代的是「真实模型端点」的位置，而不是网关内部的任何组件 ——
 * 出方向与入方向的全部代码照常执行，被替换掉的只是远端那个不确定的答复。
 * 这是唯一能让「同一份上游字节」被反复重放的方式，也是契约测试的前提。
 *
 * <p>用 JDK 自带的 {@code HttpServer} 而不是引入 mock 框架，是为了不为几十行的用途拉进一棵依赖树。
 *
 * <p>它有两种回放方式，区别在**写出节奏**：
 * <ul>
 *   <li>{@link #replay} —— 一次写完，用于非流式；
 *   <li>{@link #replayStream} —— 按块写并 flush，用于流式。
 *       块边界由调用方决定，因此可以构造「一个多字节字符被切在两个块之间」这类
 *       真实端点给不出、但在迁移期最容易出错的样本。
 * </ul>
 */
public final class RecordedUpstream implements AutoCloseable {

  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

  private static final String JSON = "application/json";

  private static final String EVENT_STREAM = "text/event-stream";

  /** SSE 的事件分隔符。按它切分是为了让每一块对应一个完整的事件，而不是任意的字节数。 */
  private static final String EVENT_SEPARATOR = "\n\n";

  private final HttpServer server;

  private volatile int status = 200;

  // 存字符串而不是字节数组：volatile 引用指向数组时，数组元素本身并不受 volatile 保护，
  // 而这里的写入与读取分处两个线程。
  private volatile String body = "";

  private volatile String lastRequestBody = "";

  private volatile String lastPath = "";

  private volatile String lastAuthorization = "";

  private volatile String contentType = JSON;

  /** 非 null 时按块写出（流式模式）；null 表示一次写完。 */
  private volatile List<byte[]> chunks = null;

  private volatile Duration betweenChunks = Duration.ZERO;

  private final AtomicInteger writtenChunks = new AtomicInteger();

  /**
   * 整份样本有没有写到头。
   *
   * <p>它是「背压有没有生效」唯一**不依赖具体数字**的证据：数字停在哪一块取决于
   * 内核 socket 缓冲区的大小，而那个数值随机器与内核而变（本机与 CI 上差了一倍）。
   * 「有没有写到头」则是非此即彼的：要么写到最后一块（对端把整条流都吃进去了，
   * 即无界缓冲），要么停在某处（有界）。断言要落在后者上。
   */
  private final AtomicBoolean feedFullyWritten = new AtomicBoolean();

  /** 收到了几次请求。它是「有没有出网」的判定依据，见 {@link #requests()}。 */
  private final AtomicInteger requests = new AtomicInteger();

  /**
   * @param port 端口，传 0 表示由内核分配
   * @param status 回放的状态码
   * @param body 回放的响应体
   * @throws IOException 端口绑定失败时抛出
   */
  public RecordedUpstream(int port, int status, String body) throws IOException {
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    // 处理线程必须是守护线程：不读取的消费方会让回放线程停在 write 上（那正是背压的证据），
    // 而 JDK 默认给 HttpServer 的 executor 建的是非守护线程 —— 那样测试跑完了 JVM 也退不出去。
    this.server.setExecutor(daemonWorkers());
    this.status = status;
    this.body = body;
    this.server.createContext("/", exchange -> {
      RecordedUpstream.this.requests.incrementAndGet();
      RecordedUpstream.this.lastRequestBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      RecordedUpstream.this.lastPath = exchange.getRequestURI().getPath();
      // 不给缺省值：这个头「在不在」本身就是被测的东西，缺的时候必须是 null 而不是空串。
      RecordedUpstream.this.lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
      List<byte[]> pending = RecordedUpstream.this.chunks;
      if (pending != null) {
        writeInChunks(exchange, pending);
        return;
      }
      // 显式引用字段：构造参数同名，在 lambda 里写裸名字会被捕获成那个字符串参数，
      // 于是回放的内容变成「构造时的快照」，后续再也改不动且看不出来。
      byte[] payload = RecordedUpstream.this.body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", RecordedUpstream.this.contentType);
      exchange.sendResponseHeaders(RecordedUpstream.this.status, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
      RecordedUpstream.this.feedFullyWritten.set(true);
    });
    this.server.start();
  }

  /**
   * 地址根，不含路径前缀。
   *
   * <p>兼容端点挂在 {@code /v1} 上，Ollama 的原生接口挂在 {@code /api} 上 ——
   * 同一个回放服务可以同时扮演两种端点，前缀由调用方自己拼。
   */
  public String root() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** 基地址，形态与真实 OpenAI 兼容端点一致，这样每一处的路径拼接走的都是同一条逻辑。 */
  public String baseUrl() {
    return root() + "/v1";
  }

  /** 最近一次请求打在哪个路径上。它是「走的是哪一种形态」最直接的证据。 */
  public String lastPath() {
    return lastPath;
  }

  /**
   * 最近一次请求带的 {@code Authorization} 头。
   *
   * <p>它要能被断言成「没有」，而不只是能被断言成有：不需要凭证的形态若也把占位串发了出去，
   * 看日志的人会以为这次调用是被授权过的。
   */
  public String lastAuthorization() {
    return lastAuthorization;
  }

  /**
   * 换一份样本。同一个服务进程可以被多个用例复用，端口不必每次重新分配。
   *
   * @param nextStatus 下一次要回放的状态码
   * @param nextBody 下一次要回放的响应体
   */
  public void replay(int nextStatus, String nextBody) {
    this.status = nextStatus;
    this.body = nextBody;
    this.chunks = null;
    this.contentType = JSON;
    this.feedFullyWritten.set(false);
    this.lastRequestBody = "";
    this.lastPath = "";
    this.lastAuthorization = null;
  }

  /**
   * 以 SSE 的形式回放一段文本，按事件分块写出。
   *
   * @param nextStatus 下一次要回放的状态码
   * @param sse SSE 文本，事件之间以空行分隔
   */
  public void replayStream(int nextStatus, String sse) {
    replayStream(nextStatus, splitIntoEvents(sse), Duration.ZERO);
  }

  /**
   * 以指定的字节块回放。块边界完全由调用方决定 —— 这是唯一能构造
   * 「一个字符被切在两个块之间」的方式。
   *
   * @param nextStatus 下一次要回放的状态码
   * @param nextChunks 依次写出的字节块，每块写完立即 flush
   * @param betweenChunks 块之间的停顿，用于让调用方真的**先**收到前一块
   */
  public void replayStream(int nextStatus, List<byte[]> nextChunks, Duration betweenChunks) {
    this.status = nextStatus;
    this.chunks = List.copyOf(nextChunks);
    this.contentType = EVENT_STREAM;
    this.betweenChunks = betweenChunks == null ? Duration.ZERO : betweenChunks;
    this.feedFullyWritten.set(false);
    this.lastRequestBody = "";
    this.lastPath = "";
    this.lastAuthorization = null;
  }

  /** 网关最后发出去的请求体，用于断言「转发出去的东西确实是原文」。 */
  public String lastRequestBody() {
    return lastRequestBody;
  }

  /**
   * 已经写出去了几块。
   *
   * <p>它是**背压的唯一可读证据**：一个不读取的消费方会让对端的写停在某个块上，
   * 于是这个数字停止增长；反过来，如果网关把整条流都吃进了内存，它会一路涨到全部写完。
   * 「停止增长」与「涨到头」之间的差别，就是有界缓冲与无界缓冲的差别。
   */
  public int writtenChunks() {
    return writtenChunks.get();
  }

  /** 整份样本是否已经写到头。用法见字段上的说明：它比「写到第几块」更值得断言。 */
  public boolean feedFullyWritten() {
    return feedFullyWritten.get();
  }

  /** 把写出计数归零。每个用例自己从零开始，免得前一个用例的数字混进来。 */
  public void resetWrittenChunks() {
    writtenChunks.set(0);
  }

  /**
   * 收到过几次请求。
   *
   * <p>它是「本地快速失败」唯一的硬证据：读熔断器自己的状态只能证明它记得自己是开的，
   * 不能证明**请求没有被发出去**。这两件事在来源项目里恰恰是分开的 ——
   * 状态记了，请求照发。
   */
  public int requests() {
    return requests.get();
  }

  /** 把请求计数归零。 */
  public void resetRequests() {
    requests.set(0);
  }

  /**
   * 挂住连接但一个字节都不写。
   *
   * <p>它对应的是「上游接了请求、回应了头，然后就再也没说话」这一种故障：
   * 连接是活的，内容是空的。这与「连接被拒」是两回事 ——
   * 后者在建立阶段就失败了，前者要等到帧间静默超时才被判定为出事，
   * 而**只有**后者存在时，超时这一类结算路径永远走不到。
   *
   * @param nextStatus 回应头里的状态码
   * @param silence 保持沉默的时长
   */
  public void holdOpen(int nextStatus, Duration silence) {
    replayStream(nextStatus, List.of(new byte[0]), silence);
  }

  @Override
  public void close() {
    server.stop((int) STOP_TIMEOUT.toSeconds());
  }

  /** 给回放用的线程：守护线程，名字可辨 —— 卡在写出上的那一条在线程转储里要认得出是谁。 */
  private static Executor daemonWorkers() {
    return task -> {
      Thread worker = new Thread(task, "recorded-upstream");
      worker.setDaemon(true);
      worker.start();
    };
  }

  /**
   * 按块写出并 flush。
   *
   * <p>flush 不是可选的动作：少了它，所有块会被攒在缓冲区里一次性发出，
   * 于是「逐帧到达」与「一次收到整体」在客户端看起来是一样的 ——
   * 那正是流式这一票要区分的两件事。
   */
  private void writeInChunks(com.sun.net.httpserver.HttpExchange exchange, List<byte[]> pending) {
    exchange.getResponseHeaders().add("Content-Type", RecordedUpstream.this.contentType);
    try {
      exchange.sendResponseHeaders(RecordedUpstream.this.status, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        for (byte[] chunk : pending) {
          out.write(chunk);
          out.flush();
          writtenChunks.incrementAndGet();
          pause(RecordedUpstream.this.betweenChunks);
        }
        // 走到这里才是「整份样本都写出去了」；停在 write 上（对端不读了）不会到这里。
        RecordedUpstream.this.feedFullyWritten.set(true);
      }
    } catch (IOException expected) {
      // 对端先走了。这正是「调用方中途断开」那条路径的必然结果，不是被测行为；
      // 这里必须吞掉并正常收尾：抛出会让回放线程带着一把没关的连接停在半路，
      // 而收尾阶段会一直等一个不会到来的写出。
    } finally {
      exchange.close();
    }
  }

  private static void pause(Duration duration) {
    if (duration.isZero() || duration.isNegative()) {
      return;
    }
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException expected) {
      Thread.currentThread().interrupt();
    }
  }

  /** 按事件切分，切出来的每一块都以空行结尾，因此每一块都是一个完整的 SSE 事件。 */
  private static List<byte[]> splitIntoEvents(String sse) {
    List<byte[]> events = new ArrayList<>();
    int start = 0;
    while (start < sse.length()) {
      int end = sse.indexOf(EVENT_SEPARATOR, start);
      if (end < 0) {
        events.add(sse.substring(start).getBytes(StandardCharsets.UTF_8));
        return events;
      }
      events.add(sse.substring(start, end + EVENT_SEPARATOR.length()).getBytes(StandardCharsets.UTF_8));
      start = end + EVENT_SEPARATOR.length();
    }
    return events;
  }
}
