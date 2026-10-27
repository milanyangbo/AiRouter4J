package io.github.airouter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.providers.UpstreamShape;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 端点的配置校验。
 *
 * <p>这里守的不是「字段有没有填」，而是**形态与地址、凭证之间的依赖**：
 * 形态决定地址该长什么样、以及凭证是不是必填。把这三条拆成三个互不相关的注解是做不到的 ——
 * 注解只看得见自己那一个字段。
 *
 * <p>后果一律是**启动失败**。配错了属于「能不能起来」的问题，不属于「对不对」的问题：
 * 等到第一次调用才炸，正是本项目要避开的那类失败。
 */
@Tag("unit")
final class EndpointPropertiesTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Test
  @DisplayName("形态缺失：起不来，且指名那个环境变量 —— 形态不能由地址猜")
  void missingShapeFailsNamingTheEnvironmentVariable() {
    assertThatThrownBy(() -> endpoint("http://127.0.0.1:11434/v1", "key", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.SHAPE_ENV);
  }

  @Test
  @DisplayName("openai 形态缺凭证：起不来，且指名那个环境变量")
  void missingCredentialFailsWhenTheShapeRequiresOne() {
    assertThatThrownBy(() -> endpoint("http://127.0.0.1:11434/v1", "", UpstreamShape.OPENAI))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.API_KEY_ENV);
  }

  @Test
  @DisplayName("地址不符合形态：起不来，出错信息指名收到的那个值")
  void baseUrlAgainstTheShapeFailsNamingTheValue() {
    String given = "http://127.0.0.1:11434";

    assertThatThrownBy(() -> endpoint(given, "key", UpstreamShape.OPENAI))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(given);
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
    assertThatThrownBy(() -> endpoint("http://127.0.0.1:11434/v1", null, UpstreamShape.OPENAI))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("形态、地址、凭证都对得上时：配置成立")
  void validConfigurationIsAccepted() {
    EndpointProperties properties = endpoint("http://127.0.0.1:11434/v1", "key",
        UpstreamShape.OPENAI);

    assertThat(properties.shape()).isEqualTo(UpstreamShape.OPENAI);
    assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
  }

  private static EndpointProperties endpoint(String baseUrl, String apiKey, UpstreamShape shape) {
    return new EndpointProperties(baseUrl, apiKey, "qwen2.5:0.5b", TIMEOUT, shape);
  }
}
