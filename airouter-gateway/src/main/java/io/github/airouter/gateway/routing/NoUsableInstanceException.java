package io.github.airouter.gateway.routing;

/**
 * 六步走完一个候选都不剩：**拒绝**，而不是挑一个凑合的。
 *
 * <p>它带着「在哪一步、因为什么」两层信息，是因为这两种拒绝的处置完全不同：
 * 「没有带齐标签的实例」是配置与规则的问题，改规则才修得好；
 * 「全部被闸门排除」是健康问题，等熔断恢复自然会好。
 * 混成一句「没有可用实例」，运维在故障现场就只能靠猜。
 *
 * <p>为什么是异常而不是返回空：**「没有可用实例」是一条要被说出来的结论**。
 * 返回空会让调用方在某个 `.get()` 上以另一种形态炸掉，
 * 而那一行离「为什么没有」已经隔了几层 —— 状态码也会从「明确的 503」变成「内部错误」。
 */
public final class NoUsableInstanceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String serviceId;

  private final String reason;

  /**
   * @param serviceId 目标服务名。出错信息里要指名它：
   *                  一个网关通常不止一个服务，「没有可用实例」少了服务名等于没说
   * @param reason 为什么一个都不剩
   */
  public NoUsableInstanceException(String serviceId, String reason) {
    super("服务 " + serviceId + " 现在没有可用实例：" + reason);
    this.serviceId = serviceId;
    this.reason = reason;
  }

  /** 目标服务名。 */
  public String serviceId() {
    return serviceId;
  }

  /** 为什么一个都不剩。 */
  public String reason() {
    return reason;
  }
}
