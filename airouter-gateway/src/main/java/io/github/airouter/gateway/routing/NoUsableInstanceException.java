package io.github.airouter.gateway.routing;

import java.util.Objects;

/**
 * 链走完六步仍然选不出一个实例。
 *
 * <p>它带 serviceId 与原因，而不是只有一句「没有可用实例」：
 * 三种原因对应三种不同的处置（见 {@link Rejection}），
 * 少一个字段就等于让运维从「不知道该干什么」开始查。
 *
 * <p>由 Face 在边界上收口成一个错误响应 —— **不出网、也不结算**
 * （ADR-0013 (e)）：这一次调用根本没发生过，把它算作失败，
 * 会让它继续喂熔断器，而打开的熔断器就再也等不到冷却结束那次成功探测。
 */
public final class NoUsableInstanceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String serviceId;

  private final Rejection rejection;

  /**
   * @param serviceId 目标 Service 名
   * @param rejection 原因
   */
  public NoUsableInstanceException(String serviceId, Rejection rejection) {
    super(rejection.describe(serviceId));
    this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
    this.rejection = Objects.requireNonNull(rejection, "rejection");
  }

  /** 请求要去的那个服务。 */
  public String serviceId() {
    return serviceId;
  }

  /** 为什么选不出实例。 */
  public Rejection rejection() {
    return rejection;
  }
}
