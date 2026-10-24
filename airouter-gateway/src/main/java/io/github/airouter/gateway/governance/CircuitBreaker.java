package io.github.airouter.gateway.governance;

import io.github.airouter.core.Outcome;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个实例的熔断器（ADR-0007 第 5 节）。
 *
 * <p>它**只消费 {@link Outcome}**，不知道 HTTP、不知道状态码、也不知道是谁发起了调用。
 * 这是 ADR-0007 第 1 节的落地形态：治理组件若自己去判断成败，同一个请求在熔断器看来失败、
 * 在限流看来成功就只是时间问题。
 *
 * <p>状态用 {@code AtomicReference + CAS} 更新，不用锁：治理状态在请求的热路径上，
 * 锁竞争会直接变成吞吐的损失。
 *
 * <p>三类结算里只有两类推进它：上游故障与超时计入失败；成功闭合；**取消完全不参与**
 * —— 取消反映的是调用方的处境，不是实例的健康（ADR-0006 第 3 节）。
 */
public final class CircuitBreaker {

  private static final System.Logger LOG = System.getLogger(CircuitBreaker.class.getName());

  private final String instanceId;

  private final AvailabilityPolicy policy;

  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.initial());

  /**
   * @param instanceId 它守护的那一实例
   * @param policy 阈值与冷却时间
   */
  public CircuitBreaker(String instanceId, AvailabilityPolicy policy) {
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public String instanceId() {
    return instanceId;
  }

  /**
   * 这一刻的状态。
   *
   * <p>冷却结束之后的 {@code OPEN} 在这里被读成 {@link CircuitState#HALF_OPEN}：
   * 「可以再试一次」是时间推出来的事实，不需要任何人先发一次请求来触发。
   * 少了这一层派生，冷却结束之后要等下一次上报才转半开，而那时已经放行或拒绝过一次了。
   */
  public CircuitState state(Instant now) {
    return advance(snapshot.get(), now).state();
  }

  /** 这一刻是否允许把请求送出去。 */
  public boolean allowsCall(Instant now) {
    return state(now).allowsCalls();
  }

  /** 连续失败的次数。成功与取消都不清零它之外的东西 —— 成功了归零，取消了原样保留。 */
  public int consecutiveFailures() {
    return snapshot.get().failures();
  }

  /**
   * 最近一次状态迁移。
   *
   * <p>迁移带着 {@code requestId} 是为了让一次失败能被追到它触发的那次状态变化：
   * 「熔断器什么时候开的」能回答，但「是谁把它打开的」在排查时才是有用的那一半。
   */
  public Optional<Transition> lastTransition() {
    return Optional.ofNullable(snapshot.get().transition());
  }

  /**
   * 收到一次结算结果。
   *
   * @param outcome 结算结果，不为 null
   * @param now 结算发生的时刻，由调用方给出：这里不该自己取时间，
   *             否则「冷却是否结束」在测试里就只能用真的等待来验证
   */
  public void record(Outcome outcome, Instant now) {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(now, "now");
    switch (outcome.kind()) {
      case SUCCESS -> succeed(outcome.requestId(), now);
      case UPSTREAM_FAILURE, UPSTREAM_TIMEOUT -> fail(outcome.requestId(), now);
      // 取消不改变任何计数：把它算作失败，一次共享网络的抖动就能熔断掉一个健康的实例。
      case CLIENT_CANCELLED -> LOG.log(System.Logger.Level.DEBUG,
          () -> "[" + outcome.requestId() + "] 取消不计入 " + instanceId + " 的熔断");
      default -> throw new IllegalArgumentException("未知的结算类别：" + outcome.kind());
    }
  }

  /** 一次失败（上游故障或超时）。够到阈值就打开，并记下是谁触发的。 */
  private void fail(String requestId, Instant now) {
    while (true) {
      Snapshot current = normalized(now);
      int failures = current.failures() + 1;
      Snapshot next = failures >= policy.failureThreshold()
          ? new Snapshot(current.version() + 1, CircuitState.OPEN, failures, now,
              new Transition(current.state(), CircuitState.OPEN, requestId, now))
          : new Snapshot(current.version() + 1, current.state(), failures,
              current.openedAt(), current.transition());
      if (snapshot.compareAndSet(current, next)) {
        return;
      }
    }
  }

  /** 一次成功。半开时的成功闭合；已经闭合时只是把计数归零。 */
  private void succeed(String requestId, Instant now) {
    while (true) {
      Snapshot current = normalized(now);
      if (current.state() == CircuitState.CLOSED && current.failures() == 0) {
        // 一次全无变化的成功不写：这是绝大多数请求走的路径，而 CAS 失败重试会让
        // 高并发下这里变成一堆无效的自旋。
        return;
      }
      Transition transition = current.state() == CircuitState.CLOSED
          ? current.transition()
          : new Transition(current.state(), CircuitState.CLOSED, requestId, now);
      Snapshot next = new Snapshot(current.version() + 1, CircuitState.CLOSED, 0, null, transition);
      if (snapshot.compareAndSet(current, next)) {
        return;
      }
    }
  }

  /**
   * 把「冷却已过」这件事落实下来。
   *
   * <p>读路径（{@link #state}）自己派生半开就够了，但**写路径必须先落实再改**：
   * 否则一次成功会从一个过期的 OPEN 直接闭合，中间那次半开在迁移记录里永远不出现，
   * 于是「它曾经打开过又自己好了」与「它从来没打开过」在观测面上无法区分。
   */
  private Snapshot normalized(Instant now) {
    while (true) {
      Snapshot current = snapshot.get();
      Snapshot advanced = advance(current, now);
      if (advanced == current) {
        return current;
      }
      if (snapshot.compareAndSet(current, advanced)) {
        return advanced;
      }
    }
  }

  /** 冷却结束时把 OPEN 推成 HALF_OPEN；其余原样返回同一个对象（引用相等，可以据此判断有没有变）。 */
  private Snapshot advance(Snapshot current, Instant now) {
    if (current.state() != CircuitState.OPEN || current.openedAt() == null) {
      return current;
    }
    if (now.isBefore(current.openedAt().plus(policy.cooldown()))) {
      return current;
    }
    return new Snapshot(
        current.version(), CircuitState.HALF_OPEN, current.failures(),
        current.openedAt(), current.transition());
  }

  /**
   * 状态的完整快照。一次 CAS 换掉的是这整个对象，因此不存在「状态改了计数没改」的中间态。
   *
   * @param version 单调递增的版本号。它的用途是让「状态没变但计数变了」可被区分：
   *                只看状态的话，窗口期里计数被清零又涨回来会被误判为一切照旧
   *                （ADR-0007 第 5 节的 ABA 场景）。CAS 本身比较的是引用，
   *                已经排除了引用层面的 ABA，版本号补上的是语义层面的那一半
   * @param state 当前状态
   * @param failures 连续失败次数
   * @param openedAt 进入 OPEN 的时刻，闭合之后为 null
   * @param transition 最近一次迁移，从未迁移过时为 null
   */
  private record Snapshot(long version,
                          CircuitState state,
                          int failures,
                          Instant openedAt,
                          Transition transition) {

    static Snapshot initial() {
      return new Snapshot(0, CircuitState.CLOSED, 0, null, null);
    }
  }

  /**
   * 一次状态迁移。
   *
   * @param from 迁移前的状态
   * @param to 迁移后的状态
   * @param requestId 触发这次迁移的那次请求的标识
   * @param at 迁移发生的时刻
   */
  public record Transition(CircuitState from, CircuitState to, String requestId, Instant at) {
  }
}
