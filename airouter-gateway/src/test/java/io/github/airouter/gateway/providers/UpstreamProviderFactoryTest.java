package io.github.airouter.gateway.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.core.Outcome;
import java.util.function.Consumer;
import io.github.airouter.gateway.config.InstanceDefinition;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.ServiceView;
import io.github.airouter.gateway.registry.UpstreamRegistry;
import io.github.airouter.gateway.transport.ActiveCallMeter;
import io.github.airouter.gateway.transport.OutboundWire;
import io.github.airouter.gateway.transport.SseFramer;
import io.github.airouter.gateway.transport.UpstreamFrame;
import io.github.airouter.gateway.transport.UpstreamFramer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 形态自述的三件事，以及把形态变成出网能力的工厂。两者放进同一个文件：
 * 自述是**声明**，工厂是**兑现** —— 「登记的构造器覆盖了每一种形态」这条断言
 * 只有挨着自述本身看才读得懂，分开写会让「新增一种形态要动哪几处」变成两份各自为政的清单。
 *
 * <p>**形态不等于厂商**：新增一个 OpenAI 兼容厂商是配置，不是类（ADR-0006 第 1 节），
 * 而这里守的正是「形态那一层值不值得一个取值」的判断 —— 三件事里只要有一件不同，才配一个新取值。
 *
 * <p>工厂这一侧的两条挨在一起的事实，缺一条另一条就成了空话：
 *
 * <ol>
 *   <li>每一种形态都有登记的构造器 —— 忘了登记，这里是**启动期**就炸
 *   <li>两个实例得到的是**两个** Provider，而不是一个按参数分派的共享对象
 *       （ADR-0009 第 4 节的 B 路）
 * </ol>
 *
 * <p>第 1 条之所以要断言，是因为它的失败表现极难发现：测试用的总是已经登记的那一种，
 * 于是「第二种形态没登记构造器」要等到真有人配上那种形态才炸。
 */
@Tag("unit")
final class UpstreamProviderFactoryTest {

  private static final Duration IDLE = Duration.ofSeconds(15);

  private static final int MAX_CONTENT_CHARS = 1 << 16;

  /** 结算缺席时的占位。本文件断言的是形态与 Provider 的装配，不是结算本身。 */
  private static final Consumer<Outcome> NO_SETTLEMENT = outcome -> { };

  // ---- 形态自述：地址后缀、凭证要求、分帧方式 ----

  @Test
  @DisplayName("openai 形态自述三件事：地址以 /v1 结尾、需要凭证、流式走 SSE 分帧")
  void openAiDescribesItself() {
    assertThat(UpstreamShape.OPENAI.baseUrlSuffix()).isEqualTo("/v1");
    assertThat(UpstreamShape.OPENAI.requiresCredential()).isTrue();
    assertThat(UpstreamShape.OPENAI.framerFactory().apply(1024)).isInstanceOf(SseFramer.class);
    // 「什么算异常结束」的解释也由形态提供，不写在共享的错误映射里（ADR-0006 第 4 节）。
    assertThat(UpstreamShape.OPENAI.describeStreamEnding()).isNotBlank();
  }

  /**
   * 地址形态是**启动期校验**，不是约定。
   *
   * <p>少了 {@code /v1} 后缀不会在启动期报错，只会变成一次深入运行期的 404 ——
   * 一种只在「第一次真实调用发生时」才暴露、而那时候已经难以归因的失败。
   */
  @Test
  @DisplayName("地址不以形态要求的后缀结尾：启动期就红，且指名收到的那个值")
  void baseUrlWithoutRequiredSuffixIsRejectedNamingTheValue() {
    String given = "http://127.0.0.1:11434";

    assertThatThrownBy(() -> UpstreamShape.OPENAI.requireValidBaseUrl(given))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(given)
        .hasMessageContaining("/v1");
  }

  @Test
  @DisplayName("地址带尾斜杠：同样在启动期红，且指名收到的那个值")
  void baseUrlWithTrailingSlashIsRejectedNamingTheValue() {
    String given = "http://127.0.0.1:11434/v1/";

    assertThatThrownBy(() -> UpstreamShape.OPENAI.requireValidBaseUrl(given))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(given);
  }

  /**
   * 形态**不按地址猜**。
   *
   * <p>同一台本机 Ollama 上 {@code /v1}（OpenAI 兼容）与 {@code /api}（原生）两种地址都在，
   * 猜必然猜错；而猜错的代价不是报错，是一整条流一条事件都产不出来 ——
   * 用错分帧器读另一种 wire format，连「为什么没有内容」都看不出来。
   */
  @Test
  @DisplayName("形态不按地址猜：给一个地址当形态名，一律报错并列出合法取值")
  void shapeIsNeverGuessedFromAnAddress() {
    String address = "http://127.0.0.1:11434/v1";

    assertThatThrownBy(() -> UpstreamShape.of(address))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(address)
        .hasMessageContaining(UpstreamShape.OPENAI.id());
    assertThatThrownBy(() -> UpstreamShape.of(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(UpstreamShape.of("OPENAI")).isEqualTo(UpstreamShape.OPENAI);
    assertThat(UpstreamShape.of(" openai ")).isEqualTo(UpstreamShape.OPENAI);
  }

  /**
   * 分帧器**一次订阅一个**。
   *
   * <p>它带着「上一行没攒完」的状态。共享一个实例的后果不是报错，而是两次调用互相污染对方的半行 ——
   * 表现是「偶发少一段内容」，而且只在并发时发生。
   */
  @Test
  @DisplayName("形态给的分帧器每次都是新的：两次订阅不共享半行")
  void framerIsCreatedPerSubscription() {
    UpstreamFramer first = UpstreamShape.OPENAI.framerFactory().apply(1024);
    UpstreamFramer second = UpstreamShape.OPENAI.framerFactory().apply(1024);

    assertThat(first).isNotSameAs(second);

    assertThat(first.push(bytes("data: {\"a\":"))).isEmpty();
    assertThat(second.push(bytes("data: {\"b\":1}\n\n"))).hasSize(1);
    // 第二个分帧器的半行没有跑到第一个上面去：第一个补齐自己的那一行之后仍然只产出一帧。
    assertThat(first.push(bytes("1}\n\n"))).hasSize(1);
  }

  /**
   * 形态自述的第三件事里「流怎么结束」这一半是**可执行**的：它给出来的分帧器真能切出帧。
   *
   * <p>顺带钉住 ADR-0006 第 5 节：哨兵在传输层只是一份载荷。
   * 认它是 codec 的事 —— 传输层认下哨兵，等于替某一家的协议做决定。
   */
  @Test
  @DisplayName("形态给的分帧器能切出帧；[DONE] 在它眼里只是一份载荷，不是结束")
  void framerFromTheShapeCutsFramesAndDoesNotInterpretTheSentinel() {
    UpstreamFramer framer = UpstreamShape.OPENAI.framerFactory().apply(1024);

    List<UpstreamFrame> frames = framer.push(bytes("data: {\"a\":1}\n\ndata: [DONE]\n\n"));

    assertThat(frames).hasSize(2);
    assertThat(payload(frames.get(0))).isEqualTo("{\"a\":1}");
    // 它不是「结束」，只是这一帧的原文。是不是结束由形态的 codec 判断。
    assertThat(payload(frames.get(1))).isEqualTo("[DONE]");
  }

  // ---- 形态工厂：登记的构造器与每实例一个 Provider ----

  @Test
  @DisplayName("每一种形态都有登记的构造器：忘了登记在这里就炸，不会拖到某次请求")
  void everyShapeHasARegisteredConstructor() {
    UpstreamProviderFactory factory = new UpstreamProviderFactory();

    for (UpstreamShape shape : UpstreamShape.values()) {
      UpstreamProvider provider = factory.create(inputs(shape, "http://127.0.0.1:11434/v1", "a"));

      assertThat(provider.streaming()).as("形态 %s 应当给得出流式端口", shape.id()).isNotNull();
      assertThat(provider.completion()).as("形态 %s 应当给得出非流式端口", shape.id()).isNotNull();
    }
    assertThat(factory.describeSupportedShapes()).contains(UpstreamShape.OPENAI.id());
  }

  /**
   * 工厂给两份不同的入参造出两个不同的 Provider —— 它证明的是**工厂不共用对象**。
   *
   * <p>它**没有**证明「每实例一条 {@code OutboundWire}」：这两条 wire 是本文件
   * {@code inputs(...)} 自己 new 出来的，真正决定这一点的
   * {@code UpstreamConfiguration.register()} 不在本断言经过的路径上 ——
   * 把那里改成两个实例共用一条 wire，这条测试照样绿。
   * 端到端的那一条记在票 07 的「补记：新增测试项」里，等两个实例真能各打各的时候补。
   */
  @Test
  @DisplayName("两个实例得到两个不同的 Provider：地址与超时是实例自己的属性")
  void eachInstanceGetsItsOwnProvider() {
    UpstreamProviderFactory factory = new UpstreamProviderFactory();

    UpstreamProvider first =
        factory.create(inputs(UpstreamShape.OPENAI, "http://127.0.0.1:11434/v1", "first"));
    UpstreamProvider second =
        factory.create(inputs(UpstreamShape.OPENAI, "http://127.0.0.1:11435/v1", "second"));

    assertThat(first.streaming()).isNotSameAs(second.streaming());
    assertThat(first.completion()).isNotSameAs(second.completion());
  }

  /**
   * 注册表把这些已经就绪的对象收在一处 —— 「配置 → 运行时对象」的唯一持有者（ADR-0010 §2）。
   *
   * <p>这条断言拦不到的事情发生在未来：一旦有别处缓存了服务与实例的映射，
   * 热重载必然漏改那一处，而那时谁都不会想起这里有一条测试。
   */
  @Test
  @DisplayName("注册表收下两个实例，按 id 能取回各自的那个")
  void registryHoldsOneEntryPerInstance() {
    UpstreamProviderFactory factory = new UpstreamProviderFactory();
    List<RegisteredInstance> instances = List.of(
        registered("first", "http://127.0.0.1:11434/v1", factory),
        registered("second", "http://127.0.0.1:11435/v1", factory));
    UpstreamRegistry registry =
        new UpstreamRegistry(Map.of("chat", new ServiceView("chat", instances)));

    assertThat(registry.serviceIds()).containsExactly("chat");
    assertThat(registry.requireService("chat").instance("second").orElseThrow().id())
        .isEqualTo("second");
    assertThat(registry.requireService("chat").enabledInstances()).hasSize(2);
  }

  @Test
  @DisplayName("取一个不存在的服务：报错并把已注册的名字列出来")
  void unknownServiceFailsListingTheCandidates() {
    UpstreamProviderFactory factory = new UpstreamProviderFactory();
    UpstreamRegistry registry = new UpstreamRegistry(Map.of("chat",
        new ServiceView("chat",
            List.of(registered("first", "http://127.0.0.1:11434/v1", factory)))));

    assertThatThrownBy(() -> registry.requireService("typoed"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chat");
  }

  // ---- 装配助手 ----

  private static RegisteredInstance registered(String id, String baseUrl,
      UpstreamProviderFactory factory) {
    InstanceDefinition definition = new InstanceDefinition(id, UpstreamShape.OPENAI, null, baseUrl,
        "key", "qwen2.5:0.5b", Duration.ofSeconds(30), 1, true, List.of());
    return new RegisteredInstance(definition.id(), definition.shape(), definition.vendor(),
        definition.weight(), definition.enabled(), Set.copyOf(definition.tags()),
        factory.create(inputs(definition.shape(), baseUrl, id)));
  }

  private static ProviderInputs inputs(UpstreamShape shape, String baseUrl, String instanceId) {
    OutboundWire wire = new OutboundWire("shape-probe", new ActiveCallMeter(), baseUrl,
        Duration.ofSeconds(30), 1 << 16, 1 << 20);
    return new ProviderInputs(wire, shape, "key", "qwen2.5:0.5b", instanceId, IDLE,
        MAX_CONTENT_CHARS, NO_SETTLEMENT);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String payload(UpstreamFrame frame) {
    return ((UpstreamFrame.Data) frame).payload();
  }
}
