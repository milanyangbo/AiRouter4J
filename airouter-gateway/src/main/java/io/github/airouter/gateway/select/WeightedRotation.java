package io.github.airouter.gateway.select;

import java.util.ArrayList;
import java.util.List;

/**
 * 平滑加权轮转：给每个候选一个**有效权重**，回答「下一个该谁」。
 *
 * <p>它的两个使用者是 {@link LeastConnectionsSelector} 与 {@link LatencySelector} ——
 * 两个都是「先看一个外部输入，再由权重说话」，差别只在有效权重怎么算。
 * 算完之后「下一个是谁」是同一件事，于是这一段只写一份：
 * 上一版它逐字写了两遍，连光标字段都各声明了一份。
 *
 * <p>为什么不是「把光标在 {@link WeightedCandidates} 的区间上 ++」（轮询那么做）：
 * 那个走法在权重是几个小整数的时候是对的（3:1 → A,A,A,B），
 * 但有效权重一旦是**分数**（延迟反比就是），量化后的区间会长到几千几万，
 * 于是游标会在第一个候选的区间里连走几千次才换人，
 * 表现为「一段时间内流量全在同一台机器上」—— 而这恰恰会被读成「延迟感知没生效」。
 * 这里每轮把权重加进各自的累积值、取最大的那一个、再从它身上扣掉一份，
 * 于是**任意长度的窗口里**比例都接近权重占比，与权重本身有多大无关。
 *
 * <p>累积值记的是**次数**（权重先归一成占比，每轮加的是占比、扣的是 1），
 * 不是「欠了多少权重」。这一点是必须的：延迟样本每来一条都在改有效权重的绝对大小，
 * 记成次数就不必跟着换算 —— 记成权重的话，一次量级上的变化会变成一笔要还很久的旧账
 * （实测：从「都没有历史」到「量到 315 毫秒」，旧账够那个实例多吃三次流量）。
 *
 * <p>有效权重为 0 的候选不参与这一轮（累积值归零，也不参加比较）。
 * 「排除」原则上是第 ③ 步的事，但 {@code least-connections} 的「不挑更忙的」
 * 是那个策略自己的定义：那不是降权，是它本来就这么定义。
 *
 * <p>累积值是**进程级共享状态**（与轮询的光标同一种东西），
 * 所以断言必须写成与起点无关：比例、窗口内的计数，不写「这一次一定是谁」。
 * 也正因为它是共享状态，这里是 {@code synchronized}：一次选择会在若干线程上同时发生，
 * 而约束区里只有几次浮点加法 —— 没有 IO、没有等待、不调任何外部对象。
 */
final class WeightedRotation {

  private List<String> shape = List.of();

  private double[] accumulated = new double[0];

  /**
   * @param candidates 第 ④ 步交出来的候选
   * @param weights 与候选**一一对应**的有效权重，非负；0 = 这一轮不参与
   * @return 下一个候选的 id
   * @throws IllegalArgumentException 两个入参长度对不上，或没有一个权重为正时抛出
   */
  synchronized String next(List<Candidate> candidates, double[] weights) {
    if (candidates.size() != weights.length) {
      throw new IllegalArgumentException("候选有 " + candidates.size() + " 个、权重有 "
          + weights.length + " 个：两者必须一一对应，对不上说明调用方把两批东西对错了位");
    }
    rebaseIfShapeChanged(candidates);

    double total = 0d;
    for (double weight : weights) {
      total += Math.max(0d, weight);
    }
    if (total <= 0d) {
      throw new IllegalArgumentException("至少得有一个候选的有效权重大于零："
          + "全为零说的是「这一轮谁都不该被选中」，而那是链该拒绝的事（第 ③ 步排空了全部候选），"
          + "不是这里悄悄退回第一个候选");
    }

    int best = -1;
    for (int index = 0; index < weights.length; index++) {
      if (weights[index] <= 0d) {
        accumulated[index] = 0d;
        continue;
      }
      accumulated[index] += weights[index] / total;
      // 并列时取靠前的那一个：与轮询「取第一个命中的区间」同一种取向，
      // 于是「候选顺序稳定 = 序列稳定」（spec 故事 49）在这里也成立。
      if (best < 0 || accumulated[index] > accumulated[best]) {
        best = index;
      }
    }
    accumulated[best] -= 1d;
    return candidates.get(best).id();
  }

  /**
   * 候选集合变了（实例增减、顺序变了）就从头记：累积值是**这一批**候选的账，
   * 不是全局的账。换了一批候选还接着记，等于让新加入的实例替上一批还债。
   */
  private void rebaseIfShapeChanged(List<Candidate> candidates) {
    if (sameShape(candidates)) {
      return;
    }
    List<String> ids = new ArrayList<>(candidates.size());
    for (Candidate candidate : candidates) {
      ids.add(candidate.id());
    }
    shape = List.copyOf(ids);
    accumulated = new double[candidates.size()];
  }

  private boolean sameShape(List<Candidate> candidates) {
    if (shape.size() != candidates.size()) {
      return false;
    }
    for (int index = 0; index < candidates.size(); index++) {
      if (!shape.get(index).equals(candidates.get(index).id())) {
        return false;
      }
    }
    return true;
  }
}
