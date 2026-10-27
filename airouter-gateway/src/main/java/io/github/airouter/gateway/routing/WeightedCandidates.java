package io.github.airouter.gateway.routing;

import java.util.Arrays;
import java.util.List;

/**
 * 第 ④ 步的产物：候选被**折算成一段一段的区间**，策略只在区间上取值。
 *
 * <p>权重为什么是独立的一步而不是每个策略各写一遍（ADR-0011 第 2 节 (b)）：
 * legacy 的加权只被轮询引用过一次，最少连接根本没有加权 ——
 * 运维以为「配了权重就会生效」，实际是「只有恰好用了轮询才生效」。
 * 把加权放进链里的一步，六个策略就都吃同一份加权，
 * 而「加权对不对」也只有一处答案。
 *
 * <p>用**区间**而不是把候选按权重复制若干份：复制要按权重之和分配空间，
 * 权重写成 1000 就是一千份；区间是候选数那么长的一段数组，取值一次二分查找。
 * 「展开」在这里指的是这个折算，不是真的复制。
 *
 * <p>区间按候选在配置里的顺序排列 —— 顺序稳定是这一层给出的东西：
 * 同一份配置在不同进程里挑出不同的实例，是「配置没改而行为变了」那一类最难查的问题。
 */
public final class WeightedCandidates {

  private final List<Candidate> candidates;

  /** 每个候选的上界（不含）：第 i 段是 [前一个上界, bounds[i])。权重都 ≥ 1，所以它严格递增。 */
  private final long[] upperBounds;

  private final long total;

  private WeightedCandidates(List<Candidate> candidates) {
    this.candidates = List.copyOf(candidates);
    long[] bounds = new long[candidates.size()];
    long running = 0L;
    for (int i = 0; i < candidates.size(); i++) {
      running += candidates.get(i).weight();
      bounds[i] = running;
    }
    this.upperBounds = bounds;
    this.total = running;
  }

  /**
   * 把候选折算成区间（第 ④ 步）。
   *
   * @param candidates 第 ③ 步筛剩下的候选，至少一个
   * @return 加权之后的候选
   * @throws IllegalArgumentException 一个候选都没有时抛出。
   *     「挑一个」在空集合上没有答案，而返回 null 会把这件事推到调用方的某一行去炸
   */
  public static WeightedCandidates of(List<Candidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      throw new IllegalArgumentException(
          "加权展开至少要有一个候选：一个都没有的时候「挑哪一个」没有答案，"
              + "而让它返回空会让这个结论静默地变成「谁也没挑」");
    }
    return new WeightedCandidates(candidates);
  }

  /** 区间总长，也就是权重之和。策略在 {@code [0, total)} 上取值。 */
  public long total() {
    return total;
  }

  /** 参与这一次选择的候选，保持配置里的顺序。 */
  public List<Candidate> candidates() {
    return candidates;
  }

  /**
   * 取给定位置落在哪一个候选的区间里。
   *
   * <p>它是六个策略共用的那一次取值：轮询把它当成一个循环的光标，
   * 随机把它当成一次抽签的结果，`ip-hash` 把它当成哈希的落点。
   * 「权重怎么影响结果」因此只有这一处实现。
   *
   * @param position 区间上的位置
   * @return 那一个候选
   * @throws IllegalArgumentException 位置不在 {@code [0, total)} 内时抛出，并**报出区间长度** ——
   *     越界几乎总是「拿上一个候选集合的长度取这一个集合的位置」，报出长度立刻可见
   */
  public Candidate at(long position) {
    if (position < 0 || position >= total) {
      throw new IllegalArgumentException(
          "位置 " + position + " 不在区间 [0," + total + ") 内：区间长度是权重之和，候选变了它就变了");
    }
    int found = Arrays.binarySearch(upperBounds, position);
    // 区间不含上界：正好命中上界说明它落在**下一段**的起点上。
    // 没命中时插入点就是那一段 —— 这两行是「权重如何影响结果」唯一的实现处。
    int index = found >= 0 ? found + 1 : -found - 1;
    return candidates.get(index);
  }
}
