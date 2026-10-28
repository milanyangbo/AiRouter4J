package io.github.airouter.gateway.observe;

import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.OutcomeObserver;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 每实例的**延迟样本**：由 {@code Outcome} 养出来的一份 EWMA 表，{@code latency} 策略的输入。
 *
 * <p>它住在这里、由 {@link OutcomeObserver} 喂养，而不是挂在注册表上、
 * 更不是让 {@code latency} 策略自己去观测：后者会让策略带上副作用，
 * 于是「给定候选与样本，结果可复现」这条验收当场不成立（ADR-0003 要求 {@code select} 是纯函数）。
 * 这里的分工是 —— **观测是 {@code observe} 的事，打分是策略的事，两者之间只隔着一个数字**。
 *
 * <p>三个口径，缺一个这个数就不再是「这台机器有多快」：
 *
 * <ul>
 *   <li>**成功**用 {@link Outcome#latency()}：它算到终态为止（ADR-0005），
 *       于是流式与非流式的样本具有可比性 —— 来源项目在响应头写出时就结算，
 *       流式样本因此恒为零，延迟感知拿到的输入是假的
 *   <li>**失败**用惩罚值（{@code UPSTREAM_FAILURE} / {@code UPSTREAM_TIMEOUT}）：
 *       一次失败比一次慢更值得降权，而它没有延迟可言 ——
 *       不记账的话，一台正在报错的实例反倒显得「没有任何样本」，于是被当成最好
 *   <li>**取消不采纳**：客户端临时不在了，这一次调用根本没有机会产出关于实例的观测。
 *       把它记成惩罚值，一个爱取消的调用方会把实例永久压在最差那一档
 * </ul>
 *
 * <p>第一个样本**直接作为初值**，不是从 0 开始收敛：从 0 起步会让一台本来就慢的机器
 * 在收敛期里显得比它实际快，而收敛期有多长还要取决于多久才轮到它一次。
 *
 * <p>条目会**惰性清理**：某个实例不再产生结算（被摘掉、被关掉）超过保留窗口之后，
 * 它的条目在下一次记账时被丢掉。为什么必须是自动的：
 * 「不再有结算」就是这里唯一能拿到的「它已经不在了」的信号，
 * 没有人会在实例下线时回来通知这张表，而一张只增不减的表会一直长到无从解释。
 *
 * <p>清理的触发点只有「又有一次结算进来了」：这里没有定时任务、没有回调、没有订阅 ——
 * 任何额外的机制都会让「这条目什么时候消失」变成一件要读实现才知道的事。
 */
public final class LatencySamples implements OutcomeObserver {

  private final double alpha;

  private final double failurePenaltyMillis;

  private final long retentionMillis;

  private final LongSupplier clock;

  private final ConcurrentHashMap<String, Sample> samples = new ConcurrentHashMap<>();

  /**
   * @param alpha EWMA 的平滑系数，取值 {@code (0, 1]}。它决定「新样本 vs 历史」的权重：
   *              接近 1 时只看最近的一次，接近 0 时几乎不听最新的消息。
   *              **它是显式的、可配的**：藏起来的话「这个数为什么这么久才转过来」无从解释
   * @param failurePenaltyMillis 一次失败当作多少毫秒的延迟记进去
   * @param retentionMillis 一条样本在没有新样本的情况下可以留多久
   */
  public LatencySamples(double alpha, double failurePenaltyMillis, long retentionMillis) {
    this(alpha, failurePenaltyMillis, retentionMillis, System::currentTimeMillis);
  }

  /**
   * @param alpha 平滑系数
   * @param failurePenaltyMillis 失败的惩罚值（毫秒）
   * @param retentionMillis 样本保留窗口（毫秒）
   * @param clock 当前时间的来源。**注入而不是直接读系统时间**：
   *              保留多久这件事要能在测试里被走过一遍，而不是靠真的睡上几分钟
   */
  public LatencySamples(double alpha, double failurePenaltyMillis, long retentionMillis,
      LongSupplier clock) {
    if (alpha <= 0d || alpha > 1d) {
      throw new IllegalArgumentException("alpha 必须在 (0, 1] 之内，收到的是 " + alpha + "："
          + "等于 0 的表永远不变，大于 1 的表会在两次样本之间来回过冲");
    }
    if (failurePenaltyMillis <= 0d) {
      throw new IllegalArgumentException("失败的惩罚值必须是正数，收到的是 " + failurePenaltyMillis + "："
          + "它是「一次失败记作多慢」，为零等于把失败记成完美的响应");
    }
    if (retentionMillis <= 0L) {
      throw new IllegalArgumentException("样本保留窗口必须是正数，收到的是 " + retentionMillis
          + "毫秒：为零的表会在每一次记账时把刚刚学到的东西立刻丢掉");
    }
    this.alpha = alpha;
    this.failurePenaltyMillis = failurePenaltyMillis;
    this.retentionMillis = retentionMillis;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 收下一次结算，把它变成一条样本。
   *
   * <p>它是 {@link OutcomeObserver} 的实现，因此**只接结论**：这次调用是多快（或多惨）
   * 由产生终态的那一处说了算，这里不读状态码、也不看异常形态。
   */
  @Override
  public void settle(Outcome outcome) {
    Objects.requireNonNull(outcome, "outcome");
    String instanceId = outcome.instanceId();
    long now = clock.getAsLong();
    if (instanceId != null && isObservable(outcome)) {
      double sample = sampleMillisOf(outcome);
      samples.compute(instanceId, (ignoredId, previous) -> previous == null
          ? new Sample(sample, now)
          : new Sample(alpha * sample + (1d - alpha) * previous.ewmaMillis(), now));
    }
    dropStaleEntries(now);
  }

  /**
   * 这个实例当前的 EWMA。
   *
   * @param instanceId 实例标识
   * @return 毫秒数；**还没有任何样本时返回 null**。「不知道」与「知道它是 0 毫秒」不是一回事，
   *         留给 {@code select} 去决定「没有历史的实例算多快」——
   *         这里只负责把「有没有」如实说出来
   */
  public Double ewmaMillis(String instanceId) {
    Sample sample = samples.get(instanceId);
    return sample == null ? null : sample.ewmaMillis();
  }

  /**
   * 现在表里有样本的实例。诊断与测试用它；选择路径读的是 {@link #ewmaMillis} 那一个数。
   *
   * @return 有样本的实例标识
   */
  public Set<String> instances() {
    return new LinkedHashSet<>(samples.keySet());
  }

  /**
   * 丢弃超过保留窗口没有更新的条目。
   *
   * <p>它在**每一次记账时顺手做**，没有独立线程也没有定时器：
   * 「不再有新样本」本身就是下线这件事在这里唯一的表现形式，
   * 于是发现条目过期的时机与清理它的时机是同一个。
   *
   * @param now 当前时间（毫秒）
   */
  private void dropStaleEntries(long now) {
    samples.entrySet().removeIf(entry -> now - entry.getValue().updatedAtMillis() > retentionMillis);
  }

  /**
   * 这一次结算要不要变成一条样本。
   *
   * <p>成功与失败都采纳；**取消不采纳** —— 客户端已经不在了，
   * 那一次调用没有走到「实例用多久才有答案」这一步。
   */
  private static boolean isObservable(Outcome outcome) {
    return outcome.kind() != OutcomeKind.CLIENT_CANCELLED;
  }

  private double sampleMillisOf(Outcome outcome) {
    return outcome.kind() == OutcomeKind.SUCCESS
        ? Math.max(0d, outcome.latency().toMillis())
        : failurePenaltyMillis;
  }

  /**
   * 一个实例已经学到的东西：**当前的 EWMA 与最后一次更新它的时刻**。
   *
   * <p>它是不变的，更新靠整体替换（{@code ConcurrentHashMap#compute} 对同一个键是原子的），
   * 于是两个并发的结算不会各自覆盖掉对方那一半 ——
   * 「一半 alpha 一半历史」在这种写法下要么整体生效，要么整体不生效。
   */
  private static final class Sample {

    private final double ewmaMillis;

    private final long updatedAtMillis;

    private Sample(double ewmaMillis, long updatedAtMillis) {
      this.ewmaMillis = ewmaMillis;
      this.updatedAtMillis = updatedAtMillis;
    }

    private double ewmaMillis() {
      return ewmaMillis;
    }

    private long updatedAtMillis() {
      return updatedAtMillis;
    }
  }
}
