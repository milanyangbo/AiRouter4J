package io.github.airouter.gateway.routing;

/**
 * 第 ③ 步：**可用性闸门** —— 熔断与限流（阶段 7）在这一步把候选筛掉。
 *
 * <p>它是一条**判定**而不是一个状态：链把候选交给它，它回答「这一个现在能不能用」。
 * 状态在别处（熔断器自己的计数），这一层只取结论 ——
 * 于是「策略不许读健康状态」那条约束有着落：策略拿到的候选已经全是可用的。
 *
 * <p>本阶段它恒开（{@code config} 装配时给的是一个恒真的实现）：
 * 熔断与限流都还没有。它先在这里占位，是因为「有闸门」与「闸门现在说什么」是两件事 ——
 * 等到阶段 7 换上真的实现，链、策略与它们的测试一行都不用改。
 */
@FunctionalInterface
public interface AvailabilityGate {

  /**
   * 这个候选现在能不能被挑中。
   *
   * @param candidate 候选
   * @return 能就是 true
   */
  boolean admits(Candidate candidate);
}
