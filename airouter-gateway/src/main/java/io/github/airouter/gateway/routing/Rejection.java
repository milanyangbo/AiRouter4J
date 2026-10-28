package io.github.airouter.gateway.routing;

/**
 * 链走完六步仍选不出实例时的三种原因。
 *
 * <p>三种原因对应三种不同的处置：第一种是配置问题（去改配置），
 * 第二种是标签写错了（去看看那个 Service 到底有什么），
 * 第三种是实例当前不可用（等它冷却，或者去看熔断为什么打开）。
 * 压成一个「没有可用实例」，运维就只能从「不知道该干什么」开始查。
 *
 * <p>三种都**拒绝**，没有一条更宽的路可退：
 * 退回不筛等于把运维写的标签静默丢掉；退回全部候选等于让熔断形同虚设；
 * 静默挑第一个，则是那种只在流量不均时才看得出来、最难归因的偶发错路由。
 */
public enum Rejection {

  /** 服务一个启用着的实例都没有（全部被关掉）。 */
  NO_ENABLED_INSTANCES("服务 %s 没有任何启用着的实例：它的请求无处可去"),

  /** 有启用着的实例，但带齐要求标签的一个也没有。 */
  NO_TAG_MATCH("服务 %s 里没有实例同时带齐要求的标签"),

  /** 标签筛过了，但可用性闸门把剩下的全部排空。 */
  GATE_EXHAUSTED("服务 %s 的候选被可用性闸门全部排空");

  private final String template;

  Rejection(String template) {
    this.template = template;
  }

  /**
   * @param serviceId 目标 Service 名
   * @return 带服务名的说明。带上它是因为一次拒绝最贵的地方在于不知道自己配错了哪一个字
   */
  public String describe(String serviceId) {
    return String.format(template, serviceId);
  }
}
