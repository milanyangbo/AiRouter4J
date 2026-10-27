package io.github.airouter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.providers.UpstreamShape;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 实例这一级的配置校验。
 *
 * <p>这里守的不是「字段有没有填」，而是**形态与地址、凭证之间的依赖**：
 * 形态决定地址该长什么样、以及凭证是不是必填。把这三条拆成三个互不相关的注解是做不到的 ——
 * 注解只看得见自己那一个字段。
 *
 * <p>后果一律是**启动失败**。配错了属于「能不能起来」的问题，不属于「对不对」的问题：
 * 等到第一次调用才炸，正是本项目要避开的那类失败。
 *
 * <p>出错信息一律**指名实例 id**：有了多实例之后，「某一个实例配错了」是最常见的一种，
 * 一句不带 id 的「地址不合法」会让人把每一个实例都看一遍。
 */
@Tag("unit")
final class InstanceDefinitionTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
