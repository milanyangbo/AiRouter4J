package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 最少连接：挑**当前压着的调用最少**的那一个实例。
 *
 * <p>在途数来自 {@code transport}（每条出站口在订阅时加一、任何结束方式都减一），
 * 但进来的时候它是**一个查表动作**，不是让这个策略自己去问 {@code transport} ——
 * 后者会让 {@code select} 带上 IO 的一侧，于是「给定候选与在途数，结果可复现」
 * 这条验收当场不成立（ADR-0011 第 2 节 (e)）。
 *
 * <p>**某个候选的在途数查不到时它报错，不把查不到当成 0。**
 * 把「不知道」当成 0 的后果是：所有实例看起来一样闲，
 * 这个策略静默退化成随机 ——
 * 那正是来源项目「配了最少连接，实际表现与随机无异」那类失效的形状。
 * 「不知道」与「知道是零」两者的差别由 {@code null} 承担，而不是由 0 承担。
 *
 * <p>并列的时候（好几个实例一样闲）交给第 ④ 步的加权在这一批里裁决，
 * 而不是赢者通吃、也不是把权重乘进分数：后者会让「这个实例到底排第几」无人答得出来。
 * {@code latency} 用的是同一套形状 —— 先按自己的输入排名，并列时由权重裁决。
 *
 * <p>光标是本类的一个字段，因此它是**进程级共享状态**：
 * 断言必须写成与起点无关（3:1 → 八次恰好 6:2），不能写「第一次一定挑第一个」。
 */
public final class LeastConnectionsSelector implements InstanceSelector {

  private final Function<String, Integer> activeCalls;

  private final AtomicLong cursor = new AtomicLong();

  /**
   * @param activeCalls 实例标识 → 在途数。**查不到时返回 null**，不要返回 0
   * @throws NullPointerException {@code activeCalls} 为 null 时抛出：
   *     少了这个来源，「查不到」与「根本没有接进来」两种失败会混成同一句空指针
   */
  public LeastConnectionsSelector(Function<String, Integer> activeCalls) {
    this.activeCalls = Objects.requireNonNull(activeCalls, "activeCalls");
  }

  /**
   * @param candidates 第 ④ 步交出来的加权候选
   * @param context 第 ① ② 步的产物；**本策略不读它** —— 最少连接不关心调用方是谁
   * @return 一样闲的那批候选里、按权重轮到的那一个
   * @throws ActiveCountMissingException 某个候选的在途数查不到时抛出：当成 0 会让这个策略恒等于随机
   */
  @Override
  public String select(WeightedCandidates candidates, SelectionContext context) {
    List<Candidate> all = candidates.candidates();
    int[] counts = new int[all.size()];
    int least = Integer.MAX_VALUE;
    for (int index = 0; index < all.size(); index++) {
      counts[index] = countOf(all.get(index).id());
      least = Math.min(least, counts[index]);
    }
    List<Candidate> leastLoaded = new ArrayList<>();
    for (int index = 0; index < all.size(); index++) {
      if (counts[index] == least) {
        leastLoaded.add(all.get(index));
      }
    }
    // 并列的那批仍然走第 ④ 步：权重在这两个信息型策略里有着同一个表现，
    // 于是「换一个策略权重还生效」是一条能被断言的事，不是一句承诺。
    return WeightedCandidates.of(leastLoaded).pick(cursor.getAndIncrement()).id();
  }

  private int countOf(String instanceId) {
    Integer active = activeCalls.apply(instanceId);
    if (active == null || active < 0) {
      throw new ActiveCountMissingException(instanceId);
    }
    return active;
  }
}
