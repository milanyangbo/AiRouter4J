package io.github.airouter.gateway.config;

import io.github.airouter.gateway.governance.AvailabilityPolicy;
import io.github.airouter.gateway.governance.CircuitBreakers;
import io.github.airouter.gateway.routing.InstanceAvailability;
import io.github.airouter.gateway.routing.InstanceHealth;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 治理侧的装配：健康管理、熔断状态与那道唯一的闸门。
 *
 * <p>阈值来自环境变量（{@link AvailabilityPolicy}），实例名单来自端点配置
 * —— 后者是 fail-closed 的关键：**配置里没有出现的实例不会被登记，
 * 于是它表现为不可用而不是静默放行**（ADR-0007 第 2 节）。
 * 漏配一项的后果因此是「第一次请求就被拒绝」，而不是「潜伏到流量高峰才出事」。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AvailabilityPolicy.class)
public final class GovernanceConfiguration {

  private GovernanceConfiguration() {
  }

  /**
   * 熔断状态的总表。
   *
   * @param policy 阈值与冷却时间，来自环境变量
   * @return 注册表
   */
  @Bean
  public CircuitBreakers circuitBreakers(AvailabilityPolicy policy) {
    return new CircuitBreakers(policy);
  }

  /**
   * 被纳入健康管理的实例。
   *
   * <p>现阶段只有一个上游，名单就是它。等有实例注册表时，登记的来源换成那份配置，
   * 这里以外的代码一行都不用改 —— 判闸只问 {@link InstanceHealth#isEnrolled}，
   * 不问「你是从哪份配置来的」。
   *
   * @param properties 端点配置，它的地址就是实例标识
   * @return 名单
   */
  @Bean
  public InstanceHealth instanceHealth(EndpointProperties properties) {
    InstanceHealth health = new InstanceHealth();
    health.enroll(properties.baseUrl());
    return health;
  }

  /**
   * 唯一的闸门。
   *
   * <p>限流那一栏是空列表而不是省略掉：{@link InstanceAvailability} 的构造参数里留着它，
   * 是为了让「限流的判定挂在同一条管道上」在类型上成立。本票没有实现任何限流算法，
   * 因此运行时没有任何判定发生 —— 这与「没有这个参数」的区别是，
   * 将来实现算法时不需要碰判闸这一侧的结构。
   *
   * @param health 健康管理名单
   * @param breakers 熔断状态
   * @return 闸门
   */
  @Bean
  public InstanceAvailability instanceAvailability(InstanceHealth health, CircuitBreakers breakers) {
    return new InstanceAvailability(health, breakers, List.of(), Clock.systemUTC());
  }
}
