package io.github.airouter.gateway.routing;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 哪些实例被纳入了健康管理（ADR-0007 第 2 节）。
 *
 * <p>默认值是**不可用**，不是可用：一个不知道是否健康的节点与一个已知故障的节点，
 * 对调用方造成的损害是一样的 —— 都是在把一个请求送进不确定的结果里。
 * fail-open 在这里没有任何收益，只有「看起来还在跑」的假象。
 *
 * <p>因此这个集合的语义是「谁被显式登记过」，而不是「谁坏过」：
 * 配置漏项、注册之后还没登记、拼错的地址，一律落进「没登记」那一侧，
 * 表现为不可用，而不是静默通过。
 *
 * <p>主动健康探测（定期打一次探针）在这一票里还不存在：登记是配置的动作，
 * 撤销登记同样是。等有了探测，它改的是同一个集合，判闸那一侧一行都不用动。
 */
public final class InstanceHealth {

  private final Set<String> enrolled = ConcurrentHashMap.newKeySet();

  /**
   * 把一个实例纳入健康管理。
   *
   * @param instanceId 实例标识
   */
  public void enroll(String instanceId) {
    Objects.requireNonNull(instanceId, "instanceId");
    enrolled.add(instanceId);
  }

  /**
   * 这个实例是否被纳入了健康管理。
   *
   * @param instanceId 实例标识
   * @return 没被登记过一律是 false —— 这是 fail-closed 的全部内容
   */
  public boolean isEnrolled(String instanceId) {
    return enrolled.contains(instanceId);
  }

  /** 全部被纳入的实例。运维面按它列出实例：没被登记的不出现在名单上，也就无从被误认为可用。 */
  public Set<String> enrolled() {
    return Set.copyOf(enrolled);
  }
}
