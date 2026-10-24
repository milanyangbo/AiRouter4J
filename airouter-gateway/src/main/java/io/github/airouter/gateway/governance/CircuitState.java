package io.github.airouter.gateway.governance;

/**
 * 熔断器的三种状态（ADR-0007 第 5 节）。
 *
 * <p>{@code HALF_OPEN} 与 {@code OPEN} 的区别不在「放不放行」而在**放多少**：
 * 打开之后完全不放行会把一个已经恢复的实例永久关在外面 —— 从第一次失败到冷却结束之间
 * 没有任何东西能证明它好了。半开就是那一次证明的机会。
 */
public enum CircuitState {

  /** 正常放行。失败在这里累计。 */
  CLOSED,

  /** 不放行。它是**对下一次请求的决定**，不是对这一次的重试。 */
  OPEN,

  /**
   * 放行一次探测。
   *
   * <p>探测的结果只有两种：闭合，或者重新打开。不存在「半开期间继续计数」 ——
   * 那会让一个已经坏掉的实例在每次冷却结束时都拿到一批流量。
   */
  HALF_OPEN;

  /** 这个状态下是否允许把请求送出去。半开允许，因为它存在的意义就是放一次。 */
  public boolean allowsCalls() {
    return this != OPEN;
  }
}
