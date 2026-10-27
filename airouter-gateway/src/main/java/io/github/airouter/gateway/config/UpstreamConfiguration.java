package io.github.airouter.gateway.config;

import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.gateway.observe.SettlementLog;
import io.github.airouter.gateway.providers.ProviderInputs;
import io.github.airouter.gateway.providers.UpstreamProvider;
import io.github.airouter.gateway.providers.UpstreamProviderFactory;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.ServiceView;
import io.github.airouter.gateway.registry.UpstreamRegistry;
import io.github.airouter.gateway.transport.OutboundWire;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * 把「谁来完成一次调用」这件事定下来。
 *
 * <p>这是 Face 与 Provider 唯一发生关系的地方，而且关系是**单向的、写在配置里的** ——
 * Face 侧只认识 core 的两个端口接口，它拿到的是哪一个实现由这里决定。
 * 之所以把它做成显式的方法而不是让 Face 自己去 new：那样会把「用哪个 Provider」
 * 从一个配置问题变成一个需要读代码才能回答的问题。
 *
 * <p>装配的输入是**三级配置模型**（{@link ServicesProperties}），产出是
 * {@link UpstreamRegistry} —— 每实例一个 Provider，而不是一个全局的、按调用参数分派的实现
 * （ADR-0009 第 4 节的 B 路）。这样 {@code core} 的端口签名与 {@code faces} 都不必为「多实例」改一行。
 *
 * <p>按形态挑实现这一步发生在 {@link UpstreamProviderFactory} 里，这里只做翻译：
 * 把每个 {@link InstanceDefinition} 变成工厂要的那份入参。
 * 于是「新增一种形态」不会碰到这个文件 —— 它碰的是工厂与那种形态自己的 codec。
 */
@Configuration(proxyBeanMethods = false)
public final class UpstreamConfiguration {

  private UpstreamConfiguration() {
  }

  /**
   * **注册表**：把三级配置翻译成一堆已经就绪的对象，然后统一交给它持有。
   *
   * <p>每一个实例得到自己的 {@link OutboundWire}：地址与总超时是实例自己的属性，
   * 共用一条出站口就意味着两个实例被迫共用同一个超时 ——
   * 而「给慢的那个实例单独配一个大超时」恰恰是多实例存在的理由之一。
   *
   * @param services 三级配置模型，进来时已经过启动期校验
   * @param stream 流式的块间上限、累积内容上限与字节缓冲
   * @param factory 形态工厂
   * @param observer 结算的唯一收口点
   * @return 注册表，「配置 → 运行时对象」的唯一持有者
   */
  @Bean
  public UpstreamRegistry upstreamRegistry(ServicesProperties services, StreamProperties stream,
      UpstreamProviderFactory factory, OutcomeObserver observer) {
    // 结算在这里被降成一个动作交给 Provider：它需要知道的只是「把结论递出去」——
    // 于是每一种形态的构造器也不必认识「结算器」这个角色。
    Consumer<Outcome> settle = observer::settle;
    Map<String, ServiceView> views = new LinkedHashMap<>();
    services.services().forEach((serviceId, definition) -> {
      List<RegisteredInstance> instances = definition.instances().stream()
          .map(instance -> register(instance, stream, factory, settle))
          .toList();
      views.put(serviceId, new ServiceView(serviceId, instances));
    });
    return new UpstreamRegistry(views);
  }

  /**
   * **形态工厂**：形态 → 构造器的登记处。
   *
   * <p>它不依赖配置，所以它可以在没有任何环境的情况下被构造与自检 ——
   * 「有一种形态忘了登记构造器」因此是一条不需要容器的测试。
   *
   * @return 形态工厂
   */
  @Bean
  public UpstreamProviderFactory upstreamProviderFactory() {
    return new UpstreamProviderFactory();
  }

  /**
   * Face 的两个端口：**默认服务的第一个启用着的实例**的那一份出站能力。
   *
   * <p>「第一个」这一句是一个**阶段性的东西**：选择链与规则路由（ADR-0011 / 0012）进来之后，
   * 挑实例不再由这里决定，这个方法要么消失、要么换成真正的入口。
   * 现阶段没有那一层，所以把它写成一个显式的方法而不是藏在一句
   * {@code registry.requireService(...)} 里 —— 后者会让「网关把请求打给谁」
   * 变成一件要读 registry 才知道的事。
   *
   * <p>这里只声明**一个** bean，而它自己就是两个端口：
   * 容器按运行期类型匹配候选，同一个对象实现两个端口时，
   * 拆成两个 bean 会让注入任何一侧都同时匹配两个候选。
   *
   * @param registry 注册表
   * @param services 默认服务名在这里
   * @return 默认服务的出站能力，同时是 {@link CompletionPort} 与 {@link StreamingCompletionPort}
   */
  @Bean
  public UpstreamProvider upstreamProvider(UpstreamRegistry registry, ServicesProperties services) {
    return defaultProvider(registry, services);
  }

  /** 默认服务的第一个启用着的实例所提供的出站能力。 */
  private static UpstreamProvider defaultProvider(UpstreamRegistry registry,
      ServicesProperties services) {
    return registry.requireService(services.defaultService())
        .enabledInstances()
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "服务 " + services.defaultService() + " 没有任何启用着的实例：它的请求无处可去"))
        .provider();
  }

  /**
   * 结算账本：{@link OutcomeObserver} 在本阶段的唯一实现。
   *
   * <p>熔断、配额、调用历史随后接的是**同一个接口**，不是这个类 ——
   * 它们接进来时这个类仍然在（诊断要看最近几次调用的结算），但不再是唯一的消费者。
   * 把它定义成 bean 而不是让 Provider 自己 new 一个，正是为了那一天不用改 Provider。
   *
   * <p>返回类型写具体类：Provider 那边按 {@link OutcomeObserver} 注入（只有一个候选），
   * 而诊断与测试要按 {@link SettlementLog} 取到它 —— 声明成接口的话后者取不到。
   *
   * @return 结算收口点
   */
  @Bean
  public SettlementLog settlementLog() {
    return new SettlementLog();
  }

  /**
   * 入站缓冲区的上限。
   *
   * <p>它是 SLO 三角里「字节缓冲」这一条腿的入站那一半：客户端送来的请求体最多允许多少字节进内存。
   * 不设它，一个巨大的请求体会先被完整地读进来才轮到业务代码说话。
   *
   * <p>写在这里而不是写进 {@code spring.codec.max-in-memory-size}：前者是一处框架配置，
   * 谁都能在别处再改一次；后者是网关自己的 SLO，值与变量名都在 {@link StreamProperties} 里，
   * 校验也在那里。
   *
   * @param stream 流式的字节上限
   * @return 只做这一件事的装配器
   */
  @Bean
  public WebFluxConfigurer inboundBufferConfigurer(StreamProperties stream) {
    return new WebFluxConfigurer() {
      @Override
      public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(stream.inboundBufferBytes());
      }
    };
  }

  /**
   * 把一个实例配置变成注册表里的一项：**它带自己的出站口与自己的 Provider**。
   *
   * @param instance 实例配置，进来时已经过了它与形态共同的检查
   * @param stream 流式的块间上限与累积内容上限
   * @param factory 形态工厂
   * @param settle 结算的唯一收口点
   * @return 注册表里的一项
   */
  private static RegisteredInstance register(InstanceDefinition instance, StreamProperties stream,
      UpstreamProviderFactory factory, Consumer<Outcome> settle) {
    OutboundWire wire = new OutboundWire(instance.baseUrl(), instance.requestTimeout(),
        stream.outboundBufferBytes(), stream.maxFrameBytes());
    ProviderInputs inputs = new ProviderInputs(wire, instance.shape(), instance.apiKey(),
        instance.model(), instance.id(), stream.idleTimeout(), stream.maxContentChars(), settle);
    UpstreamProvider provider = factory.create(inputs);
    return new RegisteredInstance(instance.id(), instance.shape(), instance.vendor(),
        instance.weight(), instance.enabled(), Set.copyOf(instance.tags()), provider);
  }
}
