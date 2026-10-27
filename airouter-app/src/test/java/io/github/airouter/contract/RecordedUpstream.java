package io.github.airouter.contract;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * <p>阶段 2 只回放非流式：一次写完并关闭。按块写出的能力（以及「讲完但故意不发结束哨兵」
 * 「块间静默」「一个多字节字符被切成两半」这些样本）随票 03 / 05 加进来 ——
 * 它们各自是被某一票需要的，不为将来可能用到的能力提前建。
 */
public final class RecordedUpstream implements AutoCloseable {

  private static final int STOP_TIMEOUT_SECONDS = 5;

  private static final String JSON = "application/json";

  private final HttpServer server;

  private volatile int status;

  private volatile String body;

  private volatile String lastRequestBody = "";

  private volatile String lastPath = "";

  private volatile String lastAuthorization = null;

  private final AtomicInteger requests = new AtomicInteger();

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
    this.lastRequestBody = "";
    this.lastPath = "";
    this.lastAuthorization = null;
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
