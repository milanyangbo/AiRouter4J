package io.github.airouter.gateway.config;

import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.gateway.observe.SettlementLog;
import io.github.airouter.gateway.providers.UpstreamProviderFactory;
import io.github.airouter.gateway.registry.UpstreamRegistry;
import io.github.airouter.gateway.routing.AvailabilityGate;
import io.github.airouter.gateway.routing.InstanceSelector;
import io.github.airouter.gateway.routing.RoundRobinSelector;
import io.github.airouter.gateway.routing.SelectionChain;
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
 * <p>按形态挑实现这一步发生在 {@link UpstreamProviderFactory} 里，把每个
 * {@link InstanceDefinition} 变成工厂要的那份入参是 {@link InstanceAssembler} 的事 ——
 * 它还要在热重载时再跑一次。于是「新增一种形态」不会碰到这个文件 ——
 * 它碰的是工厂与那种形态自己的 codec。
 *
 * <p>本文件还定下了**变更怎么进来**：{@link ConfigurationLedger} 记账、
 * {@link RegistryReloader} 换注册表、而给 Face 的那一份端口每次请求重新解析当前生效的那一版。
 * 三件事的形状由 ADR-0010 第 5、6 节给出。
 *
 * <p>它同时定下了**请求怎么被挑中一个实例**：{@link SelectionChain} 是六步那一处，
 * 策略与闸门在这里被交给它，而 {@link RoutedUpstream} 把每一次调用都先送进链里
 * （ADR-0011）。「用哪个策略」因此是一个装配决定，不是一个散落在调用方的决定。
 */
@Configuration(proxyBeanMethods = false)
public final class UpstreamConfiguration {

  private UpstreamConfiguration() {
  }

  /**
   * **注册表**：把三级配置翻译成一堆已经就绪的对象，然后统一交给它持有。
   *
   * <p>装配本身不在这一段里 —— 它在 {@link InstanceAssembler} 里，因为它还要在热重载时被
   * 再跑一次，而且那一次要回答「沿用还是重建」。这里只负责把第一版装进注册表。
   *
   * @param services 三级配置模型，进来时已经过启动期校验
   * @param assembler 实例装配器
   * @return 注册表，「配置 → 运行时对象」的唯一持有者
   */
  @Bean
  public UpstreamRegistry upstreamRegistry(ServicesProperties services,
      InstanceAssembler assembler) {
    return new UpstreamRegistry(assembler.assemble(services));
  }

  /**
   * **实例装配器**：配置里的实例 → 注册表里的一项，并记住上一回给出去的出网对象。
   *
   * <p>它是一个 bean 而不是一段静态方法，正是因为它**有记忆**：热重载时「这个实例能不能沿用
   * 上一回那个 Provider」只有它答得出来。让每一次重载 new 一个装配器，等于让每一次重载
   * 都把全部实例重建一遍 —— 那正是 ADR-0010 第 5 节要挡掉的那件事。
   *
   * @param stream 流式的块间上限、累积内容上限与字节缓冲
   * @param factory 形态工厂
   * @param observer 结算的唯一收口点
   * @return 实例装配器
   */
  @Bean
  public InstanceAssembler instanceAssembler(StreamProperties stream,
      UpstreamProviderFactory factory, OutcomeObserver observer) {
    return new InstanceAssembler(stream, factory, observer);
  }

  /**
   * **版本账本**：现在生效的是哪一版，能回滚到哪一版（ADR-0010 第 6 节）。
   *
   * <p>启动时那一版就是 v1。它因此也回答了一个容易被忽略的问题：
   * 「当前生效的配置是从哪来的」—— 账本里第一个版本是启动时绑定的那一版，
   * 之后每一版都是一次变更，谁改的、改成什么都在里面。
   *
   * @param services 启动时那一版配置
   * @return 版本账本
   */
  @Bean
  public ConfigurationLedger configurationLedger(ServicesProperties services) {
    return new ConfigurationLedger(services);
  }

  /**
   * **热重载**：换掉注册表里的对象，不重建上下文（ADR-0010 第 5 节）。
   *
   * <p>它是管理面、文件监听与副本广播（阶段 13）将来共同的那一个入口：
   * 本阶段还没有那些来源，但入口先落地 —— 「配置怎么被换掉」与「谁触发了变更」是两件事，
   * 后者来了不该改动前者。
   *
   * @param registry 注册表
   * @param assembler 实例装配器
   * @param ledger 版本账本
   * @return 热重载的入口
   */
  @Bean
  public RegistryReloader registryReloader(UpstreamRegistry registry, InstanceAssembler assembler,
      ConfigurationLedger ledger) {
    return new RegistryReloader(assembler, ledger, registry::replace);
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
   * Face 的两个端口：**每一次调用都先走一遍选择链**（ADR-0011）。
   *
   * <p>「默认服务的第一个实例」那一个写死的答案到此为止：
   * 它在只有一个实例的时候看不出问题，而多实例一出现，
   * 「请求打给谁」就变成一个没有测试、没有记录、也没有人能改的决定。
   *
   * <p>这里只声明**一个** bean，而它自己就是两个端口：
   * 容器按运行期类型匹配候选，同一个对象实现两个端口时，
   * 拆成两个 bean 会让注入任何一侧都同时匹配两个候选（票 02 踩过一次）。
   * 挑实例那一步在 {@link RoutedUpstream} 里 —— 它属于「每一次调用」，
   * 不属于「装配一次」。
   *
   * @param registry 注册表
   * @param ledger 版本账本：默认服务名来自**当前生效的那一版**，不是启动时那一版
   * @param chain 六步选择链
   * @return 两个端口，同时是 {@link CompletionPort} 与 {@link StreamingCompletionPort}
   */
  @Bean
  public RoutedUpstream routedUpstream(UpstreamRegistry registry, ConfigurationLedger ledger,
      SelectionChain chain) {
    return new RoutedUpstream(registry::requireService, ledger, chain);
  }

  /**
   * **六步选择链**：一次请求打给同一个 Service 下的哪一个实例。
   *
   * <p>它不依赖容器，也不依赖注册表 —— 它只认识候选与上下文。
   * 把装配它的权力放在这里，是为了让「链用哪个策略、哪一道闸门」
   * 仍然是**一处配置决定**而不是散落在调用方。
   *
   * @param gate 第 ③ 步的闸门
   * @param selector 第 ⑥ 步的策略
   * @return 选择链
   */
  @Bean
  public SelectionChain selectionChain(AvailabilityGate gate, InstanceSelector selector) {
    return new SelectionChain(gate, selector);
  }

  /**
   * 第 ③ 步的闸门：**本阶段恒开**。
   *
   * <p>熔断与限流（阶段 7）才有东西要说。它先在这里占位，是因为
   * 「链上有这一道闸」与「这道闸现在说什么」是两件事：
   * 等到那天换上真的实现，链、策略与它们的测试一行都不用改。
   *
   * @return 恒开的闸门
   */
  @Bean
  public AvailabilityGate availabilityGate() {
    return candidate -> true;
  }

  /**
   * 第 ⑥ 步的策略：**轮询**。
   *
   * <p>选它而不是随机，是因为它在多实例下是**可预测**的：权重 3:1 表现为「三步前者、一步后者」，
   * 于是「权重有没有真的生效」是一条能被严格断言的事（端到端那一条）。
   * 随机策略只能断言比例，而比例对的那一种实现也可能根本没看权重。
   *
   * <p>策略按名可配与其余五个随下一张票进来 —— 那时这里换成从配置里取的那个。
   *
   * @return 轮询
   */
  @Bean
  public InstanceSelector instanceSelector() {
    return new RoundRobinSelector();
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
}
