package io.github.airouter.gateway.select;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 第 ④ 步：权重 → 区间，取值一次二分查找。
 *
 * <p>为什么不是「按权重把候选复制若干份」：`1000 : 1` 会造出 1001 个元素的列表，
 * 而权重是给人写「三比一」这种话用的，不是给机器当数组长度用的。
 *
 * <p>这一处被**随机抽签、轮询光标、`ip-hash` 的落点**共用（spec 的「④ 加权展开」）：
 * 加权因此只有一份实现，六个策略不必各写一遍 —— 来源项目正是各写了一遍，
 * 于是最少连接根本没有加权，想加权的运维以为它生效了。
 */
public final class WeightedCandidates {

  private final List<Candidate> candidates;

  /** 前缀和。第 i 项是第 i 个候选区间的**上界**，而区间**不含**它。 */
  private final long[] upperBounds;

  private final long total;

  private WeightedCandidates(List<Candidate> candidates, long[] upperBounds, long total) {
    this.candidates = candidates;
    this.upperBounds = upperBounds;
    this.total = total;
  }

  /**
   * 按权重展开成一串首尾相接的区间。
   *
   * <p>累加不会溢出：权重是 {@code int}（上限约 21 亿），而前缀和是 {@code long} ——
   * 要约 43 亿个实例才可能溢出，那个数量级下先出问题的一定不是这一行。
   * 这也正是 spec 故事 48 要的性质（极大权重相加不溢出、不截断）被满足的方式。
   *
   * @param candidates 已经过 ②③ 的候选，顺序即配置里的顺序
   * @return 加权后的候选
   * @throws IllegalArgumentException 候选为空时抛出：长度为 0 的区间会让取值变成除零
   */
  public static WeightedCandidates of(List<Candidate> candidates) {
    Objects.requireNonNull(candidates, "candidates");
    List<Candidate> snapshot = List.copyOf(candidates);
    if (snapshot.isEmpty()) {
      throw new IllegalArgumentException("候选不能为空：走完六步仍然一个不剩时应当由链拒绝，"
          + "而不是让这里拿着一个长度为 0 的区间继续往下走");
    }
    long[] bounds = new long[snapshot.size()];
    long running = 0L;
    for (int i = 0; i < snapshot.size(); i++) {
      running += snapshot.get(i).weight();
      bounds[i] = running;
    }
    return new WeightedCandidates(snapshot, bounds, running);
  }

  /** 区间总长，也就是权重之和。抽签的人拿它把任意 long 归一化到 {@code [0, total)}。 */
  public long total() {
    return total;
  }

  /**
   * 参与这次选择的候选，顺序与交进来时一致。
   *
   * <p>它的长度**等于候选个数**，不等于权重之和 —— 这正是「按区间而不是按复制」的外在证据，
   * 诊断的时候看它就知道加权有没有把候选表撑大。
   */
  public List<Candidate> candidates() {
    return List.copyOf(candidates);
  }

  /**
   * 这个 id 还在不在候选里。
   *
   * <p>第 ⑤ 步（粘滞）拿它判「上次粘的那个实例还在不在」：
   * 它会被关掉、被标签筛掉、被第 ③ 步排空 —— 三种情况对这个询问的回答都一样，
   * 而粘滞不需要区分它们，只要在答案变成「不在」的时候按缺席处置。
   *
   * @param instanceId 实例 id
   * @return 在这一批候选里就是 true
   */
  public boolean contains(String instanceId) {
    return candidates.stream().anyMatch(candidate -> candidate.id().equals(instanceId));
  }

  /**
   * 取落点：{@code [0, total)} 里的一个值落在哪个候选的区间里。
   *
   * @param draw 任意 long，内部会归一化
   * @return 落点所在的那个候选
   */
  public Candidate pick(long draw) {
    long value = Math.floorMod(draw, total);
    int found = Arrays.binarySearch(upperBounds, value);
    // 正好命中某个上界时，那个值属于**下一段**：区间不含上界。
    // 这一处第一次写成了 `found`（忘了 +1），于是权重为 3 的那一段只有 2/4 —— 记在这里。
    int index = found >= 0 ? found + 1 : -found - 1;
    return candidates.get(index);
  }
}
