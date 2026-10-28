package io.github.airouter.gateway.routing;

/**
 * 第 ③ 步：把不可用的实例**排除**。
 *
 * <p>排除不是降权：降权会让一个已经熔断的实例继续拿到一小部分真实流量，
 * 而那一小部分正是用来验证「熔断到底生效了没有」的流量 —— 让它继续进来，等于取消熔断。
 *
 * <p>它只回答「这个实例现在能不能用」，不回答「为什么不能」：
 * 熔断、限流、健康登记各有各的口径，而它们各自的理由由 {@code governance} 自己讲（ADR-0013）。
 *
 * <p>**本票恒开**（{@link #OPEN}）：熔断与限流属阶段 7，那时才有真正会排空它的东西。
 * 接口先在这里，是为了让「闸门排空 → 拒绝」这条路径现在就有断言，
 * 而不是等到阶段 7 才第一次被人想到（票 08 的验收）。
 */
@FunctionalInterface
public interface AvailabilityGate {

  /**
   * @param instanceId 候选实例 id
   * @return true 表示这个实例现在仍然可用
   */
  boolean allows(String instanceId);

  /** 恒开的闸门：现阶段的实现。 */
  AvailabilityGate OPEN = instanceId -> true;
}
