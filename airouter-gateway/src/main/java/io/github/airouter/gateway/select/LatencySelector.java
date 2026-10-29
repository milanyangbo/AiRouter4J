package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 延迟感知：**每实例一个与延迟成反比的权重**（ADR-0011 第 2 节 (d) 的口径）。
 *
 * <p>每实例的 EWMA 由 {@code observe} 从 {@code Outcome} 养出来
 * （成功用实际延迟、失败用惩罚值、取消不采纳），进来的时候它是**一个查表动作**：
 * 分数仍由这里算，样本不由这里观测。这样 {@code select} 仍然是纯函数 ——
 * 给定候选与这一组样本，结果是确定的，不需要任何进程外的东西就能断言。
 *
 * <p>分数是 {@code 1 / (1 + ewma)}，而这个分数**乘进第 ④ 步的权重**：
 * 有效权重 = 声明权重 / {@code (1 + ewma)}。
 * 它不是「挑最快的那一个、赢者通吃」—— 后者是这一处第一次落地的形状，
 * 而它把「慢的实例少拿流量」变成了「慢的实例一次也拿不到」。
 * 少了的那一半正是 ADR 要的性质：份额随延迟**下降**，而不是掉到零。
 * 一个被降权却仍在拿流量的实例才有机会证明自己恢复了；被硬排除的那个没有。
 * 这也是本策略**不引入随机抽签**的理由：份额要能被严格断言（随机源只有 {@code random} 才有）。
 *
 * <p>真正要说清楚的不是这个式子，而是**还没有样本的实例算多少**：
 * 它与**已知最快的那一个同档**。它没有历史，所以不该优于任何被量过的实例
 * （那等于宣布「没量过 = 最快」，于是刚启动的一段时间内流量全在没被量过的那台上）；
 * 它也不该被当成最差的一档 —— 没有历史 → 不被选中 → 还是没有历史，
 * 那是一把自己锁上的锁。全都还没有样本时（刚启动）这一档就是 0 毫秒，
 * 于是本策略退化为按声明权重轮转，那条性质因此仍然成立。
 *
 * <p>取值走 {@link WeightedRotation}：延迟反比是个分数，
 * 用游标在区间上 ++ 会让某个候选连拿几千次（那里写了原因）。
 * 与 {@code least-connections} 共用同一处，两个策略的差别只在权重怎么算。
 *
 * <p>累积值是**进程级共享状态**，断言要写成与起点无关（比例、窗口计数）。
 */
public final class LatencySelector implements InstanceSelector {

  /** 还没有样本。EWMA 不会是负数，于是 {@code -1} 可以安全地当这个记号用。 */
  private static final double NOT_OBSERVED = -1d;

  private final Function<String, Double> ewmaMillis;

  private final WeightedRotation rotation = new WeightedRotation();

  /**
   * @param ewmaMillis 实例标识 → EWMA（毫秒）。**还没有样本时返回 null**，
   *                   那一个实例随后与已知最快的那一个同档 —— 见本类开头的裁决
   * @throws NullPointerException {@code ewmaMillis} 为 null 时抛出
   */
  public LatencySelector(Function<String, Double> ewmaMillis) {
    this.ewmaMillis = Objects.requireNonNull(ewmaMillis, "ewmaMillis");
  }

  /**
   * @param candidates 第 ④ 步交出来的加权候选
   * @param context 第 ① ② 步的产物；**本策略不读它** —— 快慢不看调用方是谁
   * @return 有效权重最大的那一个；权重相等时取靠前的那个
   */
  @Override
  public String select(WeightedCandidates candidates, SelectionContext context) {
    List<Candidate> all = candidates.candidates();
    double[] millis = new double[all.size()];
    double fastestKnown = NOT_OBSERVED;
    for (int index = 0; index < all.size(); index++) {
      Double ewma = ewmaMillis.apply(all.get(index).id());
      if (ewma == null) {
        millis[index] = NOT_OBSERVED;
      } else {
        millis[index] = Math.max(0d, ewma);
        fastestKnown = fastestKnown < 0d ? millis[index] : Math.min(fastestKnown, millis[index]);
      }
    }
    // 一个都没被量过时这一档就是 0 毫秒：那时所有候选同档，退化为按声明权重轮转。
    double probingTier = fastestKnown < 0d ? 0d : fastestKnown;

    double[] weights = new double[all.size()];
    for (int index = 0; index < all.size(); index++) {
      double latency = millis[index] < 0d ? probingTier : millis[index];
      weights[index] = all.get(index).weight() / (1d + latency);
    }
    return rotation.next(all, weights);
  }
}
