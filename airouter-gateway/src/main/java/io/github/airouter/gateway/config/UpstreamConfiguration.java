package io.github.airouter.gateway.config;

import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.gateway.observe.LatencySamples;
import io.github.airouter.gateway.observe.SettlementLog;
import io.github.airouter.gateway.providers.ProviderInputs;
import io.github.airouter.gateway.providers.UpstreamProvider;
import io.github.airouter.gateway.providers.UpstreamProviderFactory;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.ServiceView;
import io.github.airouter.gateway.registry.UpstreamRegistry;
import io.github.airouter.gateway.routing.AvailabilityGate;
import io.github.airouter.gateway.routing.SelectionChain;
import io.github.airouter.gateway.select.InstanceSelector;
import io.github.airouter.gateway.select.SelectionStrategyFactory;
import io.github.airouter.gateway.select.StickyKeyResolver;
import io.github.airouter.gateway.select.StickySelector;
import io.github.airouter.gateway.transport.ActiveCallMeter;
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
 * Face 侧认识的是 core 那两个端口（准确说是 {@link RoutedPort}：core 两端口加上
 * 「这一次的调用方是谁」），它拿到的是哪一个实现由这里决定。
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
      UpstreamProviderFactory factory, List<OutcomeObserver> observers, ActiveCallMeter active) {
    // 结算在这里被降成一个动作交给 Provider：它需要知道的只是「把结论递出去」——
    // 于是每一种形态的构造器也不必认识「结算器」这个角色。
    //
    // 收这个动作的人有若干个（票 04 的账本与票 10 的延迟样本），因此这里要的是**全体**观察者：
    // 只接一个会让第二个观察者悄悄接不上 —— 那种失效没有错误，只是那张表永远是空的。
    Consumer<Outcome> settle = outcome -> observers.forEach(observer -> observer.settle(outcome));
    Map<String, ServiceView> views = new LinkedHashMap<>();
    services.services().forEach((serviceId, definition) -> {
      List<RegisteredInstance> instances = definition.instances().stream()
          .map(instance -> register(instance, stream, factory, settle, active))
          .toList();
      views.put(serviceId, new ServiceView(serviceId, instances));
    });
    return new UpstreamRegistry(views);
  }

  /**
   * 在途计数：**每个实例当前压着几次调用**的唯一记录处。
   *
   * <p>它是一个 bean，而不是每个 {@link OutboundWire} 各有一个私有的计数器：
   * {@code least-connections} 要问的是「这一批候选各自压着几个」，
   * 一张只有每条出站口自己看得到的表回答不了这个问题 ——
   * 而把它做成全局可变状态又会让「谁改了这个数」无处解释。共用这一张表，
   * 键是实例标识，于是两个方向都对得上：写的人在自己的出站口上写，读的人按实例标识读。
   *
   * @return 在途计数
   */
  @Bean
  public ActiveCallMeter activeCallMeter() {
    return new ActiveCallMeter();
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
   * Face 的两个端口：**每次请求都重新解析一次**的那一份出站能力。
   *
   * <p>「每次请求」是这一版的要点。上一版在装配期就把实例定下来了（取默认服务的
   * 第一个启用着的实例），于是票 07 加进配置模型的权重 / 标签 / 启用标志
   * 全是有配置、无消费者的字段。现在每一次调用都跑一遍六步链
   * （{@link SelectionChain}），挑的是**实例 id**，再由这里按 id 取回能出网的对象。
   *
   * <p>这里只声明**一个** bean，而它自己就是两个端口：
   * 容器按运行期类型匹配候选，同一个对象实现两个端口时，
   * 拆成两个 bean 会让注入任何一侧都同时匹配两个候选。
   * 两个端口调的是同一个解析入口 —— 形状上不存在「流式挑另一条路」的可能。
   *
   * @param registry 注册表
   * @param services 默认服务名在这里
   * @param chain 六步链
   * @return 出网入口，同时是 {@link CompletionPort} 与 {@link StreamingCompletionPort}
   */
  @Bean
  public RoutedUpstream routedUpstream(UpstreamRegistry registry, ServicesProperties services,
      SelectionChain chain) {
    return new RoutedUpstream(registry::requireService, services.defaultService(), chain);
  }

  /**
   * 六步链：①②③ 由它编排，④⑤⑥ 由 {@link InstanceSelector} 承担。
   *
   * @param gate 第 ③ 步的闸门
   * @param selector 第 ⑥ 步的策略
   * @return 链
   */
  @Bean
  public SelectionChain selectionChain(AvailabilityGate gate, InstanceSelector selector) {
    return new SelectionChain(gate, selector);
  }

  /**
   * 第 ③ 步的闸门：**本阶段恒开**。
   *
   * <p>熔断与限流属阶段 7（ADR-0013），那时才有真正会排空它的东西。
   * 这个 bean 先在这里，是为了让「闸门排空 → 拒绝」这条路径现在就有断言，
   * 而不是等到有人接熔断时才第一次被想起来。
   *
   * @return 恒开的闸门
   */
  @Bean
  public AvailabilityGate availabilityGate() {
    return AvailabilityGate.OPEN;
  }

  /**
   * **策略工厂**：策略 → 构造器的登记处。
   *
   * <p>它不依赖任何环境，只依赖那两个**查表动作**：在途数与延迟样本的 EWMA。
   * 因此「有一种策略忘了登记构造器」是一条不需要容器的测试，
   * 而「策略自己去做了 IO」这件事在这里也当场不成立 ——
   * 它拿到的不是 {@code transport} 或 {@code observe}，是两个 JDK 函数（ADR-0011 第 2 节 (e)）。
   *
   * @param active 在途计数
   * @param samples 延迟样本
   * @param routing 虚拟节点数在这里
   * @return 策略工厂
   */
  @Bean
  public SelectionStrategyFactory selectionStrategyFactory(ActiveCallMeter active,
      LatencySamples samples, RoutingProperties routing) {
    return new SelectionStrategyFactory(active::activeOf, samples::ewmaMillis,
        routing.virtualNodesPerWeight());
  }

  /**
   * **延迟样本**：由 {@code Outcome} 养出来的那张 EWMA 表。
   *
   * <p>它是 {@link OutcomeObserver} 的第二个消费者，与 {@link SettlementLog} 并列：
   * 熔断、配额、调用历史随后接的仍然是同一个接口，不是这两个类。
   * 「从 {@code Outcome} 学延迟」这件事只有这一处 ——
   * 结算点在 providers，观测点在这里，策略只读数字。
   *
   * <p>返回类型写具体类：Diagnostics 与测试要按这个类取到它，
   * 声明成接口的话那一侧要绕一圈。
   *
   * @param routing alpha / 惩罚值 / 保留窗口都在它身上
   * @return 延迟样本表
   */
  @Bean
  public LatencySamples latencySamples(RoutingProperties routing) {
    RoutingProperties.Latency latency = routing.latency();
    return new LatencySamples(latency.alpha(), latency.failurePenaltyMillis(),
        latency.retentionMillis());
  }

  /**
   * 第 ⑥ 步的策略，**外加包在它外面的第 ⑤ 步粘滞**：两者都由 {@code airouter.routing} 决定。
   *
   * <p>策略**按配置里的名字取**，名字的主人首先是 {@link RoutingProperties}（错名字起不来），
   * 其次是这里 —— 工厂报错时会列出当时登记的名字。
 * 配起来的结果是：**换一个策略不用改一行 Java** —— 这件事的粒度从「发一次版」回到了「改一个值」。
 *
 * <p>默认值取**轮询而不是随机**，理由是验证而不是偏好：轮询可预测，
 * 于是「权重真的生效」能被**严格**断言（3:1 → 八次里恰好 6:2）；
 * 随机只能断言比例，而比例对的实现也可能压根没看权重。
   *
   * <p>粘滞**在这里包上去，不在链里**：链看的是「第 ⑥ 步交给它哪一个实例 id」，
   * 它不需要知道那个 id 是抽出来的还是上一次记住的。包在链外面同时也保持了
   * 包装器这个形状 —— 它可与任意策略组合，六个策略不必各实现一遍。
   *
   * @param routing 策略名与粘滞开关
   * @param factory 策略工厂
   * @return 第 ⑥ 步；开了粘滞时外面还包着一层第 ⑤ 步
   */
  @Bean
  public InstanceSelector instanceSelector(RoutingProperties routing,
      SelectionStrategyFactory factory) {
    InstanceSelector strategy = factory.create(routing.strategyKind());
    if (!routing.stickyEnabled()) {
      return strategy;
    }
    return new StickySelector(strategy, StickyKeyResolver.callerIdentity(),
        routing.stickyMaxEntries());
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
      UpstreamProviderFactory factory, Consumer<Outcome> settle, ActiveCallMeter active) {
    OutboundWire wire = new OutboundWire(instance.id(), active, instance.baseUrl(),
        instance.requestTimeout(), stream.outboundBufferBytes(), stream.maxFrameBytes());
    ProviderInputs inputs = new ProviderInputs(wire, instance.shape(), instance.apiKey(),
        instance.model(), instance.id(), stream.idleTimeout(), stream.maxContentChars(), settle);
    UpstreamProvider provider = factory.create(inputs);
    return new RegisteredInstance(instance.id(), instance.shape(), instance.vendor(),
        instance.weight(), instance.enabled(), Set.copyOf(instance.tags()), provider);
  }
}
