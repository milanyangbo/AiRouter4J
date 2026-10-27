package io.github.airouter.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link EndpointProperties} 注册成 bean。
 *
 * <p>{@code @ConfigurationProperties} 本身没有被 {@code @Component} 元标注，组件扫描不会捡起它，
 * 因此注册必须显式写出来。这一步放在 gateway 而不是 app：谁定义契约，谁负责把它接进容器 ——
 * 否则每加一组属性就要回到 app 改一次注解，而那正是「app 知道得太多」的开端。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EndpointProperties.class)
public final class EndpointConfiguration {

  private EndpointConfiguration() {
  }
}
