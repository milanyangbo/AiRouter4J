package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 延迟感知：**谁最近答得快，就把这次调用给谁**（ADR-0011 第 2 节 (d) 的口径）。
 *
 * <p>每实例的 EWMA 由 {@code observe} 从 {@code Outcome} 养出来
 * （成功用实际延迟、失败用惩罚值、取消不采纳），进来的时候它是**一个查表动作**：
 * 分数仍由这里算，样本不由这里观测。这样 {@code select} 仍然是纯函数 ——
 * 给定候选与这一组样本，结果是确定的，不需要任何进程外的东西就能断言。
 *
 * <p>打分用 {@code 1 / (1 + ewma)} 换算成权重；真正要说清楚的不是这个式子，
 * 而是**还没有样本的实例算多少**：这里把它当作 0 毫秒（最好的那一档），
 * 而不是「不知道就不要选它」。后者会让一台新机器永远得不到第一次抽样，
 * 于是它永远停留在没有历史的状态 —— 一把自己锁上的锁。
 *
 * <p>并列的时候（好几台一样快，或都没有历史）交给第 ④ 步的加权在这一批里裁决，
 * 与 {@code least-connections} 同一个形状：**先按自己的输入排名，再由权重裁决并列**。
 * 它的一个直接后果是「样本全空时，这个策略退化成按权重的轮询」——
 * 一条能被断言的性质，而不是「理论上差不多」。
 *
 * <p>光标是本类的一个字段，因此它是**进程级共享状态**，断言要写成与起点无关。
 */
public final class LatencySelector implements InstanceSelector {

  private final Function<String, Double> ewmaMillis;

  private final AtomicLong cursor = new AtomicLong();

  /**
   * @param ewmaMillis 实例标识 → EWMA（毫秒）。**还没有样本时返回 null**，
   *                   那一个实例随后被当作 0 毫秒 —— 见本类开头的理由
   * @throws NullPointerException {@code ewmaMillis} 为 null 时抛出
   */
  public LatencySelector(Function<String, Double> ewmaMillis) {
    this.ewmaMillis = Objects.requireNonNull(ewmaMillis, "ewmaMillis");
  }

  /**
   * @param candidates 第 ④ 步交出来的加权候选
   * @param context 第 ① ② 步的产物；**本策略不读它** —— 快慢不看调用方是谁
   * @return 最快（或一样没有历史）的那批候选里、按权重轮到的那一个
   */
  @Override
  public String select(WeightedCandidates candidates, SelectionContext context) {
    List<Candidate> all = candidates.candidates();
    double[] observed = new double[all.size()];
    double fastest = Double.MAX_VALUE;
    for (int index = 0; index < all.size(); index++) {
      observed[index] = latencyOf(all.get(index).id());
      fastest = Math.min(fastest, observed[index]);
    }
    List<Candidate> fastestSet = new ArrayList<>();
    for (int index = 0; index < all.size(); index++) {
      if (observed[index] == fastest) {
        fastestSet.add(all.get(index));
      }
    }
    return WeightedCandidates.of(fastestSet).pick(cursor.getAndIncrement()).id();
  }

  /**
   * 这个实例已知有多快。
   *
   * <p>没有样本时返回 0（最快），而不是返回「最慢」或抛错：
   * 一台还没有被量过的机器必须有机会被量到。
   */
  private double latencyOf(String instanceId) {
    Double ewma = ewmaMillis.apply(instanceId);
    if (ewma == null) {
      return 0d;
    }
    return Math.max(0d, ewma);
  }
}
