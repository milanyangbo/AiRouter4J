package io.github.airouter.gateway.routing;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * `random`：在区间上抽一次签。
 *
 * <p>随机源是**从外面给进来的**，不是这里 new 一个（ADR-0011 第 4 节）：
 * 给定候选与固定随机源，结果可复现 —— 这一条断言正是「策略是纯函数」的证据。
 * 自己造一个随机源，这条断言就写不出来，只能退化成「跑很多次看比例」，
 * 而比例对的那一种实现也可能根本没看权重。
 *
 * <p>加权由第 ④ 步统一做掉：这里只是在区间上取一个位置，
 * 权重大的实例区间长，于是被抽中的频率高 —— 这一句不需要在这里实现一遍。
 */
public final class RandomSelector implements InstanceSelector {

  private final RandomGenerator random;

  /**
   * @param random 随机源。实现必须线程安全（{@link java.util.Random} 是）：
   *               选择发生在请求线程上，而这个对象只有一个
   */
  public RandomSelector(RandomGenerator random) {
    this.random = Objects.requireNonNull(random, "随机源：不给它「随机」无从谈起");
  }

  @Override
  public Candidate select(WeightedCandidates candidates, SelectionContext context) {
    return candidates.at(random.nextLong(candidates.total()));
  }
}
