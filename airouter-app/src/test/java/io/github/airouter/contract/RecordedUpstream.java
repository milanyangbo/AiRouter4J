package io.github.airouter.contract;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 回放录制字节的上游。
 *
 * <p>它替代的是「真实模型端点」的位置，而不是网关内部的任何组件 ——
 * 出方向与入方向的全部代码照常执行，被替换掉的只是远端那个不确定的答复。
 * 这是唯一能让「同一份上游字节」被反复重放的方式，也是契约测试的前提。
 *
 * <p>用 JDK 自带的 {@code HttpServer} 而不是引入 mock 框架，是为了不为几十行的用途拉进一棵依赖树。
 */
public final class RecordedUpstream implements AutoCloseable {

  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

  private final HttpServer server;

  private volatile int status = 200;

  // 存字符串而不是字节数组：volatile 引用指向数组时，数组元素本身并不受 volatile 保护，
  // 而这里的写入与读取分处两个线程。
  private volatile String body = "";

  private volatile String lastRequestBody = "";

  /**
   * @param port 端口，传 0 表示由内核分配
   * @param status 回放的状态码
   * @param body 回放的响应体
   * @throws IOException 端口绑定失败时抛出
   */
  public RecordedUpstream(int port, int status, String body) throws IOException {
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    this.status = status;
    this.body = body;
    this.server.createContext("/", exchange -> {
      RecordedUpstream.this.lastRequestBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      // 显式引用字段：构造参数同名，在 lambda 里写裸名字会被捕获成那个字符串参数，
      // 于是回放的内容变成「构造时的快照」，后续再也改不动且看不出来。
      byte[] payload = RecordedUpstream.this.body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(RecordedUpstream.this.status, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    });
    this.server.start();
  }

  /** 基地址，形态与真实 OpenAI 兼容端点一致，这样每一处的路径拼接走的都是同一条逻辑。 */
  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
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
  }

  /** 网关最后发出去的请求体，用于断言「转发出去的东西确实是原文」。 */
  public String lastRequestBody() {
    return lastRequestBody;
  }

  @Override
  public void close() {
    server.stop((int) STOP_TIMEOUT.toSeconds());
  }
}
