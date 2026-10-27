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
 * Face 侧只知道有 {@link CompletionPort} 这么一个接口，它拿到的是哪一个实现由这里决定。
 * 之所以把它做成显式的方法而不是让 Face 自己去 new：那样会把「用哪个 Provider」
 * 从一个配置问题变成一个需要读代码才能回答的问题。
 *
 * <p>现阶段只有 OpenAI 兼容一种形态。等到同一份流量要按形态分派到不同端点时，
 * 选择逻辑会在别处（routing）做完，这里仍然是唯一把它们连起来的地方 ——
 * 而新增一种形态是「在这里再加一个被条件挑中的方法」，不是「回到这里加一个分支」（ADR-0006）。
 */
@Configuration(proxyBeanMethods = false)
public final class UpstreamConfiguration {

  private UpstreamConfiguration() {
  }

  /**
   * 唯一的出站口。
   *
   * @param properties 地址与超时，全部来自环境变量
   * @return 出站管道
   */
  @Bean
  public OutboundWire outboundWire(EndpointProperties properties) {
    return new OutboundWire(properties.baseUrl(), properties.requestTimeout());
  }

  /**
   * Face 看到的那个端口。
   *
   * <p>返回类型是接口 {@link CompletionPort} 而不是具体类：注入点只有一个候选，
   * 而「Face 只认识端口」这件事因此在装配代码里也是成立的。
   *
   * @param wire 出站口
   * @param properties Endpoint 的凭证与默认模型，全部来自环境变量
   * @return 非流式端口
   */
  @Bean
  public CompletionPort completionPort(OutboundWire wire, EndpointProperties properties) {
    return new OpenAiCompatibleProvider(wire, properties.apiKey(), properties.model());
  }
}
