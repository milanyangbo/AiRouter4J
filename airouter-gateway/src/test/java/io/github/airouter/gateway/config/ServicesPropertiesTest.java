package io.github.airouter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.providers.UpstreamShape;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 服务这一级的配置校验与注册表的形状。
 *
 * <p>这里最重要的两条是 ADR-0010 第 4 节里的：
 * **同一个 Service 下实例 id 重复**要起不来，**一个服务却没有启用着的实例**要起不来。
 * 它们各自的后果都一样严重：那个实例/那个服务会被静默跳过，
 * 而「配了两个实例，实际只有一个在干活」在流量上的表现只是负载偏高。
 */
@Tag("unit")
final class ServicesPropertiesTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Test
  @DisplayName("同一个 Service 下实例 id 重复：起不来，且指名重复的那个 id")
  void duplicatedInstanceIdFailsNamingTheId() {
    assertThatThrownBy(() -> new ServiceDefinition(List.of(instance("twice"), instance("twice"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("twice");
  }

  @Test
  @DisplayName("一个 Service 至少有一个实例：空清单起不来")
  void emptyServiceFails() {
    assertThatThrownBy(() -> new ServiceDefinition(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("一个服务声明了实例却全部关闭：起不来，且指名那个服务")
  void serviceWithoutAnyEnabledInstanceFails() {
    ServiceDefinition allDisabled = new ServiceDefinition(List.of(instance("a", false)));

    assertThatThrownBy(() -> services("chat", Map.of("chat", allDisabled), "chat"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chat");
  }

  @Test
  @DisplayName("默认服务不在服务清单里：起不来，且把已声明的都列出来")
  void unknownDefaultServiceFailsListingTheCandidates() {
    ServiceDefinition chat = new ServiceDefinition(List.of(instance("a")));

    assertThatThrownBy(() -> services("chat", Map.of("chat", chat), "typoed-name"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chat")
        .hasMessageContaining("typoed-name");
  }

  @Test
  @DisplayName("没有配置任何服务：起不来")
  void noServiceAtAllFails() {
    assertThatThrownBy(() -> services("chat", Map.of(), "chat"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("两个实例共存：id 不同、形态各自属于自己的那一项，两个都在清单里")
  void twoInstancesCoexistUnderOneService() {
    ServiceDefinition chat = new ServiceDefinition(List.of(instance("first"), instance("second")));

    assertThat(chat.instances()).hasSize(2);
    assertThat(chat.instances().stream().map(InstanceDefinition::id))
        .containsExactly("first", "second");
    assertThat(chat.enabledInstances()).hasSize(2);
    assertThat(new ServicesProperties("chat", Map.of("chat", chat)).defaultServiceDefinition())
        .isEqualTo(chat);
  }

  private static ServicesProperties services(String name, Map<String, ServiceDefinition> services,
      String defaultService) {
    return new ServicesProperties(defaultService, services);
  }

  private static InstanceDefinition instance(String id) {
    return instance(id, true);
  }

  private static InstanceDefinition instance(String id, boolean enabled) {
    return new InstanceDefinition(id, UpstreamShape.OPENAI, null, "http://127.0.0.1:11434/v1",
        "key", "qwen2.5:0.5b", TIMEOUT, 1, enabled, List.of());
  }
}
