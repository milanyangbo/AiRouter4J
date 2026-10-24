package io.github.airouter.gateway.routing;

import io.github.airouter.gateway.governance.CircuitBreaker;
import io.github.airouter.gateway.governance.CircuitBreakers;
import io.github.airouter.gateway.governance.CircuitState;
import io.github.airouter.gateway.governance.RateLimitDecisionPoint;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个实例此刻接不接流量。
 *
 * <p>它是**唯一那道闸门**：熔断、健康管理、限流都在这里被问一次，别处不再有第二处判断。
 * 三者的关系是「与」：没被纳管的不接，熔断开着的不接，限流拒绝的不接。
 * 分开在三个地方各判一次的后果不是重复劳动，而是「漏掉其中一个」时没有任何一处会报错。
 *
 * <p>它只操作抽象状态：不看 HTTP，不发请求，因此整个类可以在没有容器、没有网络的情况下被单测。
 */
public final class InstanceAvailability {

  private final InstanceHealth health;

  private final CircuitBreakers breakers;

  private final List<RateLimitDecisionPoint> limiters;

  private final Clock clock;

  /**
   * @param health 哪些实例被纳入了健康管理
   * @param breakers 每个实例的熔断状态
   * @param limiters 限流的决策点。**本票没有实现**，因此运行时这里是空列表；
   *                 它出现在构造参数里是为了让「限流与熔断由同一条管道消费」在类型上成立，
   *                 而不是等算法实现那天再回头改结构
   * @param clock 取当前时刻的钟。注入而不是直接取系统时间，是为了让「冷却是否结束」
   *              不需要靠真的等一段来验证
   */
  public InstanceAvailability(InstanceHealth health,
                              CircuitBreakers breakers,
                              List<RateLimitDecisionPoint> limiters,
                              Clock clock) {
    this.health = Objects.requireNonNull(health, "health");
    this.breakers = Objects.requireNonNull(breakers, "breakers");
    this.limiters = List.copyOf(limiters);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 这个实例现在是否可以接收这一次请求。
   *
   * @param instanceId 实例标识
   * @param serviceId 目标 Service，限流的判定按 Service 计额度
   * @return 三道都过才为 true
   */
  public boolean accepts(String instanceId, String serviceId) {
    if (!health.isEnrolled(instanceId)) {
      return false;
    }
    Optional<CircuitBreaker> breaker = breakers.find(instanceId);
    // 纳管过但从未结算过 → 没有熔断器，也就没有任何证据说明它坏过。
    if (breaker.isPresent() && !breaker.get().allowsCall(clock.instant())) {
      return false;
    }
    for (RateLimitDecisionPoint limiter : limiters) {
      if (!limiter.permit(serviceId, instanceId).permitted()) {
        return false;
      }
    }
    return true;
  }

  /**
   * 一个实例的可用性快照。未纳管的实例也回答，只是 {@code managed} 为 false ——
   * 「从来没有这个实例」与「这个实例坏了」在运维上是两种不同的事，查询面必须能分开说。
   *
   * @param instanceId 实例标识
   * @return 它的快照
   */
  public AvailabilitySnapshot describe(String instanceId) {
    Instant now = clock.instant();
    boolean managed = health.isEnrolled(instanceId);
    if (!managed) {
      return new AvailabilitySnapshot(instanceId, false, null, false, null);
    }
    Optional<CircuitBreaker> breaker = breakers.find(instanceId);
    CircuitState state = breaker.map(candidate -> candidate.state(now)).orElse(CircuitState.CLOSED);
    String requestId = breaker.flatMap(CircuitBreaker::lastTransition)
        .map(CircuitBreaker.Transition::requestId)
        .orElse(null);
    return new AvailabilitySnapshot(instanceId, true, state, state.allowsCalls(), requestId);
  }

  /** 全部被纳入健康管理实例的快照，按标识排序 —— 顺序稳定，运维面上的 diff 才是可读的。 */
  public List<AvailabilitySnapshot> all() {
    List<AvailabilitySnapshot> snapshots = new ArrayList<>();
    health.enrolled().stream().sorted().forEach(instanceId -> snapshots.add(describe(instanceId)));
    return List.copyOf(snapshots);
  }
}
