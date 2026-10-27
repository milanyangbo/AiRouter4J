package io.github.airouter.gateway.transport;

/**
 * 上游以非 2xx 回答了这一次调用。
 *
 * <p>它只报告事实（状态码 + 原文），**不含任何判断**：4xx 与 5xx 各自该让客户端看到什么
 * 是网关的策略，由 Provider 决定。这里连「失败」这个词都不用 ——
 * 上游明确地拒绝了一次请求，与「够不着上游」是性质不同的两件事，
 * 后者在客户端那里应呈现为另一种结果。
 *
 * <p>它是 unchecked 的：调用链上的中间层都不打算处理它。
 *
 * <p>阶段 2 只有非流式那一条路（{@link UpstreamReply} 带着状态码回来），
 * 它随流式路径一起被使用 —— 流式一旦开始写帧，就再也改不了已经发出去的响应头。
 */
public final class UpstreamRejection extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  private final String body;

  /**
   * @param status 上游给出的状态码
   * @param body 响应体原文，空响应体是空串
   */
  public UpstreamRejection(int status, String body) {
    super("上游以 HTTP " + status + " 响应本次请求");
    this.status = status;
    this.body = body == null ? "" : body;
  }

  /** 上游给出的状态码。 */
  public int status() {
    return status;
  }

  /** 响应体原文。 */
  public String body() {
    return body;
  }
}
