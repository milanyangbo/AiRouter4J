package io.github.airouter.gateway.governance;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个实例一个熔断器的注册表。
 *
 * <p>它是容器里的一个 bean 而不是 Provider 内部的字段，理由与 {@code ChunkParseCounter} 相同：
 * 只有能从一个正在运行的应用里读到它，「熔断器真的开了」才是被验证着的，而不是被声称着的。
 */
public final class CircuitBreakers {

  private final AvailabilityPolicy policy;

  private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

  public CircuitBreakers(AvailabilityPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /**
   * 取一个实例的熔断器，没有就建一个。
   *
   * <p>惰性建立而不是要求先注册：**「先注册再使用」会引入一个顺序依赖**，
   * 而顺序错了的表现不是拒绝而是空指针 —— 那正是 fail-open 的另一种形态。
   * 真正决定一个实例能不能接流量的是 {@code routing} 那一侧有没有把它纳入健康管理，
   * 熔断状态只是第二道。
   *
   * @param instanceId 实例标识
   * @return 它的熔断器，不为 null
   */
  public CircuitBreaker forInstance(String instanceId) {
    Objects.requireNonNull(instanceId, "instanceId");
    return breakers.computeIfAbsent(instanceId, id -> new CircuitBreaker(id, policy));
  }

  /**
   * 取一个实例的熔断器，**不存在时返回空**而不是建一个。
   *
   * <p>与 {@link #forInstance} 的区别是刻意的：这一个给读路径（运维面）用，
   * 查一个不该存在的实例不该顺手把它变成存在的。
   */
  public Optional<CircuitBreaker> find(String instanceId) {
    return Optional.ofNullable(breakers.get(instanceId));
  }
}
