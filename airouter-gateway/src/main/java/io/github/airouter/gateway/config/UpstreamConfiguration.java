package io.github.airouter.gateway.config;

import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.gateway.governance.CircuitBreakerObserver;
import io.github.airouter.gateway.governance.CircuitBreakers;
import io.github.airouter.gateway.observe.SettlementObserver;
import io.github.airouter.gateway.providers.ChunkParseCounter;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.providers.ollama.OllamaProvider;
import io.github.airouter.gateway.routing.AvailabilityGate;
import io.github.airouter.gateway.routing.InstanceAvailability;
import io.github.airouter.gateway.transport.InFlightStreams;
import io.github.airouter.gateway.transport.OutboundWire;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把「谁来完成一次调用」这件事定下来。
 *
 * <p>这是 Face 与 Provider 唯一发生关系的地方，而且关系是**单向的、写在配置里的** ——
 * Face 侧只知道有 {@link CompletionPort} 与 {@link StreamingCompletionPort} 两个接口，
 * 它拿到的是哪一个实现由这里决定。之所以把它做成显式的方法而不是让 Face 自己去 new：
 * 那样会把「用哪个 Provider」从一个配置问题变成一个需要读代码才能回答的问题。
 *
 * <p>现在有两个实现（OpenAI 兼容与 Ollama 原生），由 {@code airouter.endpoint.provider}
 * 决定装配哪一个 —— 选择发生在装配期，不在调用路径上，因此「走哪一家」始终是一个配置问题。
 * 等到同一份流量要按模型分派到不同形态的端点时，选择逻辑会在别处（routing）做完，
 * 这里仍然是唯一把它们连起来的地方。
 */
@Configuration(proxyBeanMethods = false)
public final class UpstreamConfiguration {

  /**
   * 决定走哪一个 Provider 的那个属性。
   *
   * <p>两个取值是字面量而不是 {@code UpstreamShape} 的常量：注解的值必须是编译期常量，
   * 而枚举的方法调用不是。因此这里与 {@code UpstreamShape#id()} 的一致性靠测试守住 ——
   * 两个形态各有一条端到端用例按配置走通，字面量写错的那一种不会被装配出来，用例立刻红。
   */
  private static final String PROVIDER_PROPERTY = EndpointProperties.PREFIX + ".provider";

  private UpstreamConfiguration() {
  }

  /**
   * 帧解析计数。它守护 ADR-0005 的 CHUNK_PARSE_COUNT=1，
   * 因此是**容器里的一个 bean** 而不是 Provider 内部的私有字段：
   * 只有能从一个正在运行的应用里读到它，那条约束才是被验证着的，而不是被声称着的。
   */
  @Bean
  public ChunkParseCounter chunkParseCounter() {
    return new ChunkParseCounter();
  }

  /**
   * 结算点。它是**唯一**的 {@code OutcomeObserver} bean。
   *
   * <p>熔断器作为消费者登记在它上面，而不是自己成为一个 bean：
   * 那样「注入一个 {@code OutcomeObserver}」会同时匹配两个候选，装配歧义只能靠
   * {@code @Primary} 压下去，而那个注解的含义是「有多个候选时选我」 ——
   * 与「结算点只有一个」正好相反。
   *
   * @param breakers 熔断状态总表，治理侧的唯一消费者需要它
   * @return 结算点
   */
  @Bean
  public OutcomeObserver outcomeObserver(CircuitBreakers breakers) {
    return new SettlementObserver(List.of(new CircuitBreakerObserver(breakers)));
  }

  /**
   * 在途流的计数。
   *
   * <p>与 {@link #chunkParseCounter()} 同理，它也是一个 bean：只有能从一个正在运行的应用里
   * 读到它，「取消之后资源被收回」才是被验证着的，而不是被声称着的。
   */
  @Bean
  public InFlightStreams inFlightStreams() {
    return new InFlightStreams();
  }

  /**
   * 唯一的出站口。
   *
   * @param properties 地址与两个超时，全部来自环境变量
   * @param inFlight 在途流的计数
   * @return 出站管道
   */
  @Bean
  public OutboundWire outboundWire(EndpointProperties properties, InFlightStreams inFlight) {
    return new OutboundWire(
        properties.baseUrl(), properties.requestTimeout(), properties.idleTimeout(), inFlight);
  }

  /**
   * Face 看到的两个端口：形态是 {@code openai} 时的装配。
   *
   * <p>它是**闸门**而不是 Provider 本身：不放行的请求在闸门这里就结束，Provider 甚至不会被问到。
   * Provider 因此**不是 bean** —— 让它同时成为一个 bean，注入 {@code CompletionPort}
   * 就会同时匹配闸门与 Provider 两个候选，装配歧义只能靠 {@code @Primary} 压下去。
   *
   * <p>返回类型是具体类 {@link AvailabilityGate} 而不是两个接口之一，
   * 这是**容器的类型预测**决定的：按某一个接口声明返回类型，另一个接口在注入点就找不到候选 ——
   * 容器在实例化之前只知道方法声明的返回类型。而一个同时实现两个接口的类，
   * 两个注入点各自都只匹配它一个。
   *
   * <p>实例标识先用端点地址：现阶段没有实例注册表，而地址确实唯一地标识了一个上游。
   * 注册表出现之后这里换成它的 id，其它地方一行都不用改。
   *
   * @param wire 出站口
   * @param properties Endpoint 的地址、凭证、默认模型与超时 —— 全部来自环境变量
   * @param observer 结算点
   * @param counter 帧解析计数
   * @param availability 唯一那道闸门
   * @return 两个端口
   */
  @Bean
  @ConditionalOnProperty(name = PROVIDER_PROPERTY, havingValue = "openai", matchIfMissing = true)
  public AvailabilityGate openAiCompletionPort(OutboundWire wire,
                                                EndpointProperties properties,
                                                OutcomeObserver observer,
                                                ChunkParseCounter counter,
                                                InstanceAvailability availability) {
    OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
        wire, properties.apiKey(), properties.model(), properties.baseUrl(), observer, counter);
    return new AvailabilityGate(provider, provider, availability, properties.baseUrl());
  }

  /**
   * 形态是 {@code ollama} 时的装配：同一个闸门、同一套结算，只有 Provider 换了一个。
   *
   * <p>两个方法并列而不是在一个方法里按形态 {@code switch}，理由就是这一票要验证的那句话：
   * **走哪一个由配置决定，而不是由代码里的分支决定。**
   * {@code @ConditionalOnProperty} 是容器在装配期读配置后的取舍，它不在调用路径上；
   * 而方法里的 {@code if} 是每一次装配都会被走过的代码分支 —— 后者一旦出现，
   * 「新增一种形态」就变成「回到这里加一个分支」，形态差异随之渗进装配代码。
   *
   * <p>不传凭证不是漏项：Ollama 原生接口没有凭证这一项（见 {@code OllamaProvider}）。
   * 把本地档位那个占位串发上去只会误导读日志的人，因此这里连这个参数都不给它。
   *
   * @param wire 出站口
   * @param properties Endpoint 的地址、默认模型与超时 —— 全部来自环境变量
   * @param observer 结算点
   * @param counter 帧解析计数
   * @param availability 唯一那道闸门
   * @return 两个端口
   */
  @Bean
  @ConditionalOnProperty(name = PROVIDER_PROPERTY, havingValue = "ollama")
  public AvailabilityGate ollamaCompletionPort(OutboundWire wire,
                                               EndpointProperties properties,
                                               OutcomeObserver observer,
                                               ChunkParseCounter counter,
                                               InstanceAvailability availability) {
    OllamaProvider provider = new OllamaProvider(
        wire, properties.model(), properties.baseUrl(), observer, counter);
    return new AvailabilityGate(provider, provider, availability, properties.baseUrl());
  }
}
