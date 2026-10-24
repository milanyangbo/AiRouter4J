package io.github.airouter.core;

/**
 * 一次调用没能拿到可用结果。
 *
 * <p>用它而不是散落的异常类型，是为了让 Face 侧只有一处兜底：
 * 无论故障来自传输层、超时还是上游返回的非 2xx，最终都会带着一个 {@link GatewayError} 到达这里。
 *
 * <p>它是 checked 还是 unchecked 做过取舍：checked 会迫使每一层都声明它，
 * 而调用链上的每一个中间层都不打算处理它。因此这里用 RuntimeException，
 * 由 Face 在边界上一次收口。
 */
public final class UpstreamFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient GatewayError error;

  /**
   * @param error 对应的错误形态
   * @param cause 原始异常，允许为 null
   */
  public UpstreamFailure(GatewayError error, Throwable cause) {
    super(error.type() + ": " + error.message(), cause);
    this.error = error;
  }

  /** 这一次失败对外呈现的形态。 */
  public GatewayError error() {
    return error;
  }
}
