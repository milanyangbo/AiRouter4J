package io.github.airouter.gateway.routing;

import java.util.concurrent.atomic.AtomicLong;

/**
 * `round-robin`：光标在区间上向后走，走到头回到起点。
 *
 * <p>光标是**共享状态**，所以它必须在这里显式存在并被写成原子的：
 * 选择发生在请求线程上，而「下一个」的定义要求所有线程看到同一个序列。
 * 这是六个策略里唯一有状态的一个 —— 其余五个给定同样的输入永远给同样的输出，
 * 而这个的输入里包含着「已经走过几个」。
 *
 * <p>加权同样由第 ④ 步做掉：光标走过的是加权之后的区间，
 * 权重 3:1 的两个实例于是表现为「走三步前者、一步后者」，
 * 而不是「一人一次」—— 后者正是 legacy 里「配了权重却不生效」的那一种表现。
 */
public final class RoundRobinSelector implements InstanceSelector {

  /** 从 -1 起步，于是第一次取到的是位置 0。 */
  private final AtomicLong cursor = new AtomicLong(-1L);

  @Override
  public Candidate select(WeightedCandidates candidates, SelectionContext context) {
    long total = candidates.total();
    long next = cursor.updateAndGet(previous -> previous + 1L);
    // floorMod 而不是 %：光标走足够久之后会绕到负数，那时 % 给出一个负位置。
    return candidates.at(Math.floorMod(next, total));
  }
}
