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
 * 配置模型的校验，两级各守各的：**服务这一级**（集合的形状）与**实例这一级**
 * （形态与地址、凭证之间的依赖）。放进同一个文件，因为它们是同一件事的两半 ——
 * 一份配置要么整体成立、要么整体起不来，拆成两个文件会让人以为这是两条独立的防线。
 *
 * <p>服务这一级最重要的两条在 ADR-0010 第 4 节：
 * **同一个 Service 下实例 id 重复**要起不来，**一个服务却没有启用着的实例**要起不来。
 * 它们各自的后果都一样严重：那个实例/那个服务会被静默跳过，
 * 而「配了两个实例，实际只有一个在干活」在流量上的表现只是负载偏高。
 *
 * <p>实例这一级守的不是「字段有没有填」，而是**形态决定地址该长什么样、
 * 以及凭证是不是必填**。把这三条拆成三个互不相关的注解是做不到的 ——
 * 注解只看得见自己那一个字段。
 *
 * <p>后果一律是**启动失败**。配错了属于「能不能起来」的问题，不属于「对不对」的问题：
 * 等到第一次调用才炸，正是本项目要避开的那类失败。
 *
 * <p>出错信息一律**指名实例 id（或服务名）**：有了多实例之后，
 * 「某一个实例配错了」是最常见的一种，一句不带 id 的「地址不合法」会让人把每个实例都看一遍。
 */
@Tag("unit")
final class ServicesPropertiesTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  // ---- 服务这一级：集合的形状 ----

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

  // ---- 实例这一级：形态与地址、凭证之间的依赖 ----

  @Test
  @DisplayName("形态缺失：起不来，且指名那个实例 —— 形态不能由地址猜")
  void missingShapeFailsNamingTheInstance() {
    assertThatThrownBy(() -> instance("local-ollama", null, "http://127.0.0.1:11434/v1", "key"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("local-ollama");
  }

  @Test
  @DisplayName("openai 形态缺凭证：起不来，且指名那个实例")
  void missingCredentialFailsWhenTheShapeRequiresOne() {
    assertThatThrownBy(() -> instance("no-key-instance", UpstreamShape.OPENAI,
        "http://127.0.0.1:11434/v1", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no-key-instance");
  }

  @Test
  @DisplayName("地址不符合形态：起不来，出错信息指名收到的那个值")
  void baseUrlAgainstTheShapeFailsNamingTheValue() {
    String given = "http://127.0.0.1:11434";

    assertThatThrownBy(() -> instance("short-suffix", UpstreamShape.OPENAI, given, "key"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(given);
  }

  @Test
  @DisplayName("id 缺失：起不来 —— 它是区分同一个 Service 下两个实例的唯一东西")
  void missingIdFails() {
    assertThatThrownBy(() -> instance("", UpstreamShape.OPENAI, "http://127.0.0.1:11434/v1", "key"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  /**
   * 形态不需要凭证的那一种（例如 Ollama 原生接口）不该逼着运维去申请一个用不上的密钥。
   *
   * <p>本阶段只有 openai 一种形态、它也确实要凭证，所以这条断言直接问形态自己 ——
   * 等第二种形态进来，它就不用改。
   */
  @Test
  @DisplayName("凭证要求由形态说了算：形态不要凭证时，配置里没有凭证也能过")
  void credentialRequirementComesFromTheShape() {
    assertThat(UpstreamShape.OPENAI.requiresCredential()).isTrue();
    assertThatThrownBy(
        () -> instance("no-key-instance", UpstreamShape.OPENAI, "http://127.0.0.1:11434/v1", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("权重为 0 或负数：起不来 —— 它是加权选择的除数，在那里炸就太晚了")
  void nonPositiveWeightFails() {
    assertThatThrownBy(() -> instance("zero-weight", UpstreamShape.OPENAI,
        "http://127.0.0.1:11434/v1", "key", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero-weight");
  }

  @Test
  @DisplayName("形态、地址、凭证都对得上时：配置成立，且标签封成不可变集合")
  void validConfigurationIsAccepted() {
    InstanceDefinition definition =
        instance("local-ollama", UpstreamShape.OPENAI, "http://127.0.0.1:11434/v1", "key");

    assertThat(definition.shape()).isEqualTo(UpstreamShape.OPENAI);
    assertThat(definition.baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
    assertThat(definition.enabled()).isTrue();
    assertThatThrownBy(() -> definition.tags().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---- 装配助手 ----

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

  private static InstanceDefinition instance(String id, UpstreamShape shape, String baseUrl,
      String apiKey) {
    return instance(id, shape, baseUrl, apiKey, 1);
  }

  private static InstanceDefinition instance(String id, UpstreamShape shape, String baseUrl,
      String apiKey, int weight) {
    return new InstanceDefinition(id, shape, null, baseUrl, apiKey, "qwen2.5:0.5b", TIMEOUT,
        weight, true, List.of());
  }
}
