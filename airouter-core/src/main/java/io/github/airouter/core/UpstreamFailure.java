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

  private final OutcomeKind kind;

  /**
   * @param error 对应的错误形态
   * @param cause 原始异常，允许为 null
   */
  public UpstreamFailure(GatewayError error, Throwable cause) {
    this(OutcomeKind.UPSTREAM_FAILURE, error, cause);
  }

  /**
   * @param kind 这一次失败属于哪一类结果。超时与「上游拒绝了」是两类，
   *             它们的后果不同（前者计入熔断的口径与后者一致，但排查方向完全不同），
   *             而在异常层层包裹之后，只有抛出点还说得清它是哪一类 —— 所以在这里带上
   * @param error 对应的错误形态
   * @param cause 原始异常，允许为 null
   */
  public UpstreamFailure(OutcomeKind kind, GatewayError error, Throwable cause) {
    super(error.type() + ": " + error.message(), cause);
    this.kind = kind == null ? OutcomeKind.UPSTREAM_FAILURE : kind;
    this.error = error;
  }

  /** 这一次失败对外呈现的形态。 */
  public GatewayError error() {
    return error;
  }

  /** 这一次失败属于哪一类结果，供结算点直接采用，不必再从错误类型字符串反推。 */
  public OutcomeKind kind() {
    return kind;
  }
}
