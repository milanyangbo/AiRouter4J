package io.github.airouter.core;

/**
 * 结果结算点的唯一入口（ADR-0006 第 2 节）。
 *
 * <p>成功与失败只有一个来源：终态。订阅它是廉价的 —— 投递发生在收到终态之后，不在热路径上。
 *
 * <p>实现方只允许**消费**这个结果，不允许反过来影响这一次调用的走向：
 * 熔断、限流、降级都是对「下一次」的决定。
 */
public interface OutcomeObserver {

  /**
   * 一次调用结算完成。
   *
   * @param outcome 结算结果，不为 null
   */
  void onTerminal(Outcome outcome);
}
