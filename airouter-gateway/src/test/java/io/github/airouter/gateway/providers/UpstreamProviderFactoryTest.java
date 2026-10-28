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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 形态工厂与「每实例一个 Provider」。
 *
 * <p>两条挨在一起的事实，缺一条另一条就成了空话：
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
   * <p>它**没有**证明「每实例一条 {@code OutboundWire}」：下面两条 wire 是本文件
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
   * 注册表把这些已经就绪的对象收在一处 —— 它是唯一持有者。
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

  private static RegisteredInstance registered(String id, String baseUrl,
      UpstreamProviderFactory factory) {
    InstanceDefinition definition = new InstanceDefinition(id, UpstreamShape.OPENAI, null, baseUrl,
        "key", "qwen2.5:0.5b", Duration.ofSeconds(30), 1, true, List.of());
    return new RegisteredInstance(definition.id(), definition.shape(), definition.vendor(),
        definition.weight(), definition.enabled(), Set.copyOf(definition.tags()),
        factory.create(inputs(definition.shape(), baseUrl, id)));
  }

  private static ProviderInputs inputs(UpstreamShape shape, String baseUrl, String instanceId) {
    OutboundWire wire = new OutboundWire("shape-probe", new ActiveCallMeter(), baseUrl, Duration.ofSeconds(30),
        1 << 16, 1 << 20);
    return new ProviderInputs(wire, shape, "key", "qwen2.5:0.5b", instanceId, IDLE,
        MAX_CONTENT_CHARS, NO_SETTLEMENT);
  }
}
