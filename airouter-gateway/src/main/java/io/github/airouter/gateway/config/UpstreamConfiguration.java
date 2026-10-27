package io.github.airouter.gateway.config;

import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.gateway.observe.SettlementLog;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.transport.OutboundWire;
import io.github.airouter.gateway.transport.UpstreamFramer;
import java.util.function.IntFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * 把「谁来完成一次调用」这件事定下来。
 *
 * <p>这是 Face 与 Provider 唯一发生关系的地方，而且关系是**单向的、写在配置里的** ——
 * Face 侧只知道有 {@link CompletionPort} / {@link StreamingCompletionPort} 这么两个接口，
 * 它拿到的是哪一个实现由这里决定。
 * 之所以把它做成显式的方法而不是让 Face 自己去 new：那样会把「用哪个 Provider」
 * 从一个配置问题变成一个需要读代码才能回答的问题。
 *
 * <p>两个端口由**同一个 Provider 类**实现，用同一份参数、挂在同一个出站口上：
 * 流式与非流式是同一件事的两个形态。分成两个类去实现，
 * 就等于承认「流式那条路可以少挂一样东西」—— 而那正是来源项目最贵的那个缺陷的入口。
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
   * @param endpoint 地址与非流式的总超时，全部来自环境变量
   * @param stream 流式的字节上限，全部来自环境变量
   * @return 出站管道
   */
  @Bean
  public OutboundWire outboundWire(EndpointProperties endpoint, StreamProperties stream) {
    return new OutboundWire(endpoint.baseUrl(), endpoint.requestTimeout(),
        stream.outboundBufferBytes(), stream.maxFrameBytes());
  }

  /**
   * 「把字节切成帧」用哪一种读法：由形态自述，不是由这里挑。
   *
   * <p>它作为参数进 Provider，而不是让 Provider 自己去找形态 ——
   * 后者会让「换一种读法」变成一次要翻遍 provider 的改动。
   * 等形态工厂（ADR-0009）进来，这一行换成「按实例的形态取它自述的那一个」。
   *
   * @param endpoint 形态由配置显式给出
   * @return 这一份形态自述的分帧器构造器
   */
  @Bean
  public IntFunction<UpstreamFramer> streamFramerFactory(EndpointProperties endpoint) {
    return endpoint.shape().framerFactory();
  }

  /**
   * Face 看到的两个端口由**同一个对象**提供。
   *
   * <p>返回类型写具体类而不是接口，是这里唯一能成立的形式：同一个类实现两个端口时，
   * 分成两个 {@code @Bean}（各声明一个接口）会让**两个** bean 都同时匹配**两个**接口，
   * 按类型注入立刻变成二选一。而「二选一」在这件事上是最坏的结果 ——
   * 装配变成看运气，两条路究竟共用什么反而没人说得清。
   * 写成一个 bean，注入点（{@code OpenAiFace} 的构造器）看到的就仍然只有接口。
   *
   * <p>之所以坚持「两个端口、一个对象」而不是建两次：流式绕过治理的入口正是
   * 「流式那条路单独有一套东西」。同一个对象实现两个端口，少挂一样东西的机会就少一处。
   *
   * @param wire 出站口
   * @param endpoint Endpoint 的凭证与默认模型，全部来自环境变量
   * @param stream 块间上限与累积内容上限
   * @param observer 结算的唯一收口点
   * @return 这一份形态的实现，同时是 {@link CompletionPort} 与 {@link StreamingCompletionPort}
   */
  @Bean
  public OpenAiCompatibleProvider openAiCompatibleProvider(OutboundWire wire,
      IntFunction<UpstreamFramer> framerFactory,
      EndpointProperties endpoint, StreamProperties stream, OutcomeObserver observer) {
    // 实例身份现阶段就是端点地址：还没有实例注册表，而「这一次打在谁身上」必须有值 ——
    // 结算记录里少了它，事后就分不清一次失败属于哪一个实例。
    return new OpenAiCompatibleProvider(wire, framerFactory, endpoint.apiKey(), endpoint.model(),
        endpoint.baseUrl(), stream.idleTimeout(), stream.maxContentChars(), observer);
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
