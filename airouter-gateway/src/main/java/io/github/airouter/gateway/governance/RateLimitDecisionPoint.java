package io.github.airouter.gateway.governance;

/**
 * 限流的**唯一**决策点（ADR-0007 第 4 节）。
 *
 * <p>本票只把它留出来，不实现算法也不定阈值：在结果结算尚未接入之前讨论限流参数没有意义，
 * 而把接口先定下来的价值在于——将来那个算法只能出现在这一个地方。
 * 它已经挂在 {@code routing} 那条唯一的闸门上，因此「限流与熔断由同一条管道消费」
 * 在类型上是成立的，不需要等到算法实现那天再回头改结构。
 *
 * <p>实现出现时要一并回答两件事：Redis 可用时如何原子判定（Lua，单次判定无读改写竞态），
 * 以及 Redis 不可用时本地降级窗口的额度上限与 TTL。两者缺一个，
 * 「依赖故障即完全不限流」就会在某一处悄悄回来。
 */
@DecisionPoint
public interface RateLimitDecisionPoint {

  /**
   * 这一次请求是否被放行。
   *
   * @param serviceId 目标 Service
   * @param instanceId 目标实例
   * @return 判定结果，含一句可对外解释的理由：不放行却没有理由的拒绝在运维上无从处理
   */
  Decision permit(String serviceId, String instanceId);

  /**
   * 一次限流判定。
   *
   * @param permitted 是否放行
   * @param reason 理由，放行时为空串
   */
  record Decision(boolean permitted, String reason) {

    /** 放行的判定。 */
    public static final Decision PERMITTED = new Decision(true, "");

    /**
     * @param reason 为什么不放行
     * @return 拒绝的判定
     */
    public static Decision rejected(String reason) {
      return new Decision(false, reason);
    }
  }
}
