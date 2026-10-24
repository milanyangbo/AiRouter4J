package io.github.airouter.gateway.governance;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 可用性相关的全部默认值与阈值（ADR-0007 第 3 节）。
 *
 * <p>来源项目的问题不是没有阈值，而是**同一个默认值在两个方法里各写了一遍且取值不一致**：
 * 不存在单一口径可查，改一处不会同步另一处。这里把它们收成一个类型，
 * 谁需要阈值就向它要，而不是自己再写一个常量。
 *
 * <p>它同时是配置绑定的目标：阈值属于「这一套部署愿意承受多少失败」，
 * 与端点地址一样来自环境，不来自代码。
 *
 * @param failureThreshold 连续多少次**计入的**失败之后打开。注意取消不计入 —— 见 {@link CircuitBreaker}
 * @param cooldown 打开之后到转入 {@link CircuitState#HALF_OPEN} 的时长
 */
@Validated
@ConfigurationProperties(prefix = AvailabilityPolicy.PREFIX)
public record AvailabilityPolicy(

    @Min(value = 1, message = "连续失败阈值至少为 1：请在环境变量 "
        + "AIROUTER_GOVERNANCE_FAILURE_THRESHOLD 里给出")
    int failureThreshold,

    @NotNull(message = "缺少熔断冷却时间：请在环境变量 AIROUTER_GOVERNANCE_COOLDOWN 里给出")
    Duration cooldown) {

  /**
   * 冷却时间必须是正数。
   *
   * <p>与 {@code EndpointProperties} 同样用构造器检查而不是 Bean Validation：
   * 手上的 validator 已经没有 Duration 范围的约束了，而「冷却时间为零」意味着
   * 熔断打开之后**下一次请求立刻又去探测**，熔断因此退化成一次延迟。
   *
   * @throws IllegalArgumentException 冷却时间为 0 或负数时抛出
   */
  public AvailabilityPolicy {
    if (cooldown != null && (cooldown.isZero() || cooldown.isNegative())) {
      throw new IllegalArgumentException(
          "环境变量 " + COOLDOWN_ENV + " 必须是正数，收到的是 " + cooldown);
    }
  }

  /** 属性前缀。写在常量里，让 yml、测试与出错信息引用的是同一个字符串。 */
  public static final String PREFIX = "airouter.governance";

  /** 连续失败阈值的环境变量名。 */
  public static final String THRESHOLD_ENV = "AIROUTER_GOVERNANCE_FAILURE_THRESHOLD";

  /** 冷却时间的环境变量名。 */
  public static final String COOLDOWN_ENV = "AIROUTER_GOVERNANCE_COOLDOWN";

  /** 默认的连续失败阈值。 */
  public static final int DEFAULT_FAILURE_THRESHOLD = 3;

  /**
   * 默认的冷却时间。
   *
   * <p>取 15 秒的依据是「一次上游抖动的时间尺度」：真实端点的 5xx 常常成簇出现，
   * 冷却短于这个量级等于在簇内反复试探；长于这个量级则把一次瞬时故障放大成一段不可用。
   * 它不是调优结论，是一个可配置的起点。
   */
  public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(15);
}
