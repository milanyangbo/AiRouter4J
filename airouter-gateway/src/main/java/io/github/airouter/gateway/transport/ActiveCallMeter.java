package io.github.airouter.gateway.transport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每个实例**当前有多少次调用还没走完**（{@code least-connections} 策略要的那一个数）。
 *
 * <p>它住在这里而不是住在策略里：在途这件事发生的位置是出站口，而唯一知道「一次调用什么时候算走完」
 * 的也正是那一层 —— 订阅开始算起、到终态或取消为止。由策略自己去问 {@code transport}，
 * 就等于把 IO 的一侧放进了 {@code select}，而那条路走到的是
 * 「策略再也不是纯函数」（ADR-0011 第 2 节 (e)）。这里只**记数**，不问为什么要用这个数。
 *
 * <p>键是实例的标识：一张出站口对应一个实例（每实例一条 {@link OutboundWire}），
 * 于是「某实例上压着几个请求」不需要把每一条连接列一遍就能回答。
 *
 * <p>**登记过才有值**。{@link #activeOf} 返回 {@code null} 表达「我不知道这个实例上压着几个」，
 * 返回 {@code 0} 表达「我知道一个都没有」。两者的区别是这一处的要点：
 * 把「不知道」当成 0 会让所有实例看起来一样闲，于是 {@code least-connections}
 * 静默退化成随便挑一个 —— 那正是来源项目「配了最少连接实际是随机」那类失效的形状。
 *
 * <p>登记是一次性的，发生在装配期（{@code OutboundWire} 的构造器里，
 * 于是「有一条出站口」与「表里有这一项」不可能对不上）；
 * 运行期发生的是加一与减一，两者都不做 IO。
 *
 * <p>归属（票 13 裁决，2026-10-30）：它是**观测到的**每实例状态，住产生它的
 * {@code transport}，不挂注册表 —— ADR-0010 第 2 节的修订写了这条分界线。
 */
public final class ActiveCallMeter {

  /** 实例标识 → 在途计数。并发表：加一与减一来自不同请求的不同线程。 */
  private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

  /**
   * 登记一个实例：此后它的在途数是**已知的**（初值 0），而不是未知的。
   *
   * <p>这一步必须在装配期完成。少了它，一个进程刚起来、还没有任何请求打过去的实例
   * 会被 {@link #activeOf} 判成「不知道」，而 {@code least-connections} 会因此拒绝第一次调用 ——
   * 一种只在冷启动的第一个请求上出现、且很难复现的失败。
   *
   * @param instanceId 实例标识
   */
  public void register(String instanceId) {
    Objects.requireNonNull(instanceId, "instanceId");
    inFlight.computeIfAbsent(instanceId, ignored -> new AtomicInteger());
  }

  /**
   * 一次调用开始了（订阅发生）。
   *
   * @param instanceId 这一次调用打给哪个实例
   */
  public void enter(String instanceId) {
    countsOf(instanceId).incrementAndGet();
  }

  /**
   * 一次调用走完了：正常结束、失败、被取消都算走完。
   *
 * <p>它必须覆盖异常路径，而这也正是它能喂给 {@code least-connections} 的理由 ——
 * 一条只在正常结束时才减一的计数，会让失败的请求在表上越累越多，
 * 于是这个数慢慢变成「历史失败数」而不是「当前连接数」。
   *
   * @param instanceId 这一次调用打给哪个实例
   */
  public void exit(String instanceId) {
    countsOf(instanceId).decrementAndGet();
  }

  /**
   * 这个实例上现在压着几次调用。
   *
   * @param instanceId 实例标识
   * @return 在途数；**这个实例没有登记过就返回 null**，而不是 0
   */
  public Integer activeOf(String instanceId) {
    AtomicInteger counts = inFlight.get(instanceId);
    return counts == null ? null : counts.get();
  }

  /**
   * 全表的快照。诊断与测试用它，生产路径（选择）读的是单个实例那一个数 ——
   * 快照的主要用处是「这一整批实例现在是什么样」这一类问题要能回答。
   *
   * @return 实例标识 → 在途数
   */
  public Map<String, Integer> snapshot() {
    Map<String, Integer> copy = new LinkedHashMap<>();
    inFlight.forEach((instanceId, counts) -> copy.put(instanceId, counts.get()));
    return copy;
  }

  private AtomicInteger countsOf(String instanceId) {
    AtomicInteger counts = inFlight.get(instanceId);
    if (counts == null) {
      throw new IllegalStateException(
          "实例 " + instanceId + " 没有被登记过：它的出站口没有经过装配，或者一次调用打给了一个不存在的实例");
    }
    return counts;
  }
}
