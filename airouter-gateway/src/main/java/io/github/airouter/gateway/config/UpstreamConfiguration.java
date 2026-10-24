package io.github.airouter.gateway.config;

import io.github.airouter.core.CompletionPort;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.transport.OutboundWire;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把「谁来完成一次调用」这件事定下来。
 *
 * <p>这是 Face 与 Provider 唯一发生关系的地方，而且关系是**单向的、写在配置里的** ——
 * Face 侧只知道有一个 {@link CompletionPort}，它拿到的是哪一个实现由这里决定。
 * 之所以把它做成一个显式的方法而不是让 Face 自己去 new：那样会把「用哪个 Provider」
 * 从一个配置问题变成一个需要读代码才能回答的问题。
 *
 * <p>此刻只有一个实现。等到有多个 Provider 时，选择逻辑会在别处（routing）做完，
 * 这里仍然是唯一把它们连起来的地方。
 */
@Configuration(proxyBeanMethods = false)
public final class UpstreamConfiguration {

  private UpstreamConfiguration() {
  }

  /**
   * 完成端口的实现。
   *
   * @param properties Endpoint 的地址、凭证、默认模型与超时 —— 全部来自环境变量
   * @return Provider
   */
  @Bean
  public CompletionPort completionPort(EndpointProperties properties) {
    OutboundWire wire = new OutboundWire(properties.baseUrl(), properties.requestTimeout());
    return new OpenAiCompatibleProvider(wire, properties.apiKey(), properties.model());
  }
}
