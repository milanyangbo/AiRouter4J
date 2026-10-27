package io.github.airouter.contract;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
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
 * <p>两种回放：非流式一次写完并关闭；流式按块写出、块间留出给定的静默。
 * 控制到字节块一级是它的价值所在 —— 「讲完但故意不发结束哨兵」「块间静默」
 * 「一个多字节字符被切成两半」这几件事只有它造得出来，而它们各自对应一条真实会踩的故障。
 */
public final class RecordedUpstream implements AutoCloseable {

  private static final int STOP_TIMEOUT_SECONDS = 5;

  private static final String JSON = "application/json";

  private static final String EVENT_STREAM = "text/event-stream";

  private final HttpServer server;

  private volatile int status;

  private volatile String body;

  private volatile List<String> chunks = List.of();

  private volatile Duration between = Duration.ZERO;

  private volatile boolean streaming;

  private volatile String lastRequestBody = "";

  private volatile String lastPath = "";

  private volatile String lastAuthorization = null;

  private final AtomicInteger requests = new AtomicInteger();

  /** 已经写出去了几块。它是「客户端有没有在流结束之前收到东西」的对照物。 */
  private final AtomicInteger chunksWritten = new AtomicInteger();

  /**
   * @param port 端口，传 0 表示由内核分配
   * @param status 回放的状态码
   * @param body 回放的响应体
   * @throws IOException 端口绑定失败时抛出
   */
  public RecordedUpstream(int port, int status, String body) throws IOException {
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    this.server.setExecutor(daemonWorkers());
    this.status = status;
    this.body = body;
    this.server.createContext("/", exchange -> {
      RecordedUpstream.this.requests.incrementAndGet();
      RecordedUpstream.this.lastRequestBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      RecordedUpstream.this.lastPath = exchange.getRequestURI().getPath();
      // 不给缺省值：这个头「在不在」本身就是被测的东西，缺的时候必须是 null 而不是空串。
      RecordedUpstream.this.lastAuthorization =
          exchange.getRequestHeaders().getFirst("Authorization");
      if (RecordedUpstream.this.streaming) {
        writeInChunks(exchange);
        return;
      }
      byte[] payload = RecordedUpstream.this.body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", JSON);
      exchange.sendResponseHeaders(RecordedUpstream.this.status, payload.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(payload);
      }
      exchange.close();
    });
    this.server.start();
  }

  /**
   * 按块写出。
   *
   * <p>块间留出 {@code between} 的静默是这一份录制器存在的理由之一：
   * 「一条想很久但一直在说话的流」与「一条静默的流」只有靠它才能区分开，
   * 而这两种流该得到完全不同的判定。
   */
  private void writeInChunks(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", EVENT_STREAM);
    // 长度给 0 表示 chunked：一次写完的模式会把「客户端在流结束前有没有收到东西」这件事藏起来。
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      for (int i = 0; i < chunks.size(); i++) {
        if (i > 0 && !between.isZero()) {
          sleep(between);
        }
        out.write(chunks.get(i).getBytes(StandardCharsets.UTF_8));
        out.flush();
        chunksWritten.incrementAndGet();
      }
    }
    exchange.close();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException expected) {
      Thread.currentThread().interrupt();
    }
  }

  /** 基地址，形态与真实 OpenAI 兼容端点一致，这样每一处的路径拼接走的都是同一条逻辑。 */
  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  /** 最近一次请求打在哪个路径上。它是「网关把请求发到了哪里」最直接的证据。 */
  public String lastPath() {
    return lastPath;
  }

  /**
   * 最近一次请求带的 {@code Authorization} 头。
   *
   * <p>它要能被断言成「确实带上了配置里的那个凭证」：上游认的是这个头，
   * 网关拿着凭证却没发出去，调用会以 401 结束，而日志上看不出是谁没做对。
   */
  public String lastAuthorization() {
    return lastAuthorization;
  }

  /** 网关最后发出去的请求体，用于断言「转发出去的东西确实是原文」。 */
  public String lastRequestBody() {
    return lastRequestBody;
  }

  /**
   * 收到过几次请求。
   *
   * <p>它是「有没有出网」唯一的硬证据：读组件自己的状态只能证明它记得自己是什么状态，
   * 不能证明请求有没有真的被发出去 —— 这两件事在来源项目里恰恰是分开的。
   */
  public int requests() {
    return requests.get();
  }

  /** 把请求计数归零。 */
  public void resetRequests() {
    requests.set(0);
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
    this.streaming = false;
    this.chunks = List.of();
    this.lastRequestBody = "";
    this.lastPath = "";
    this.lastAuthorization = null;
  }

  /**
   * 换一份 SSE 样本：按块写出，块间留出 {@code nextBetween} 的静默。
   *
   * @param nextChunks 要写出的块。每一块都是**原始字节的角度**：想让一个多字节字符被劈开，
   *                   就把它的两个字节分别放进相邻的两块
   * @param nextBetween 块间静默。给 {@link Duration#ZERO} 表示不留
   */
  public void replayStream(List<String> nextChunks, Duration nextBetween) {
    this.chunks = List.copyOf(nextChunks);
    this.between = nextBetween;
    this.streaming = true;
    this.chunksWritten.set(0);
    this.lastRequestBody = "";
    this.lastPath = "";
    this.lastAuthorization = null;
  }

  /**
   * 已经写出去了几块。
   *
   * <p>它与「客户端收到了几帧」是两件事，放在一起看才能回答背压的问题：
   * 上游写完了十块而客户端一帧都没收到，那就是在内存里攒着 —— 流式不该有的样子。
   */
  public int chunksWritten() {
    return chunksWritten.get();
  }

  @Override
  public void close() {
    server.stop(STOP_TIMEOUT_SECONDS);
  }

  /** 给回放用的线程：守护线程，名字可辨 —— 线程转储里要认得出是谁。 */
  private static Executor daemonWorkers() {
    return task -> {
      Thread worker = new Thread(task, "recorded-upstream");
      worker.setDaemon(true);
      worker.start();
    };
  }
}
