package io.github.airouter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Endpoint 的三个字段什么时候算填错，以及**报错文案指向什么**。
 *
 * <p>这里断言的是「缺值时报错里出现的是环境变量名」。运维半夜看的是<em>环境</em>，不是一颗属性树：
 * 报 {@code airouter.endpoint.api-key 不能为空} 的人，得先把属性名换算成环境变量名才能动手。
 * 这个换算每一次都做得不必要地昂贵。
 */
final class EndpointPropertiesValidationTest {

  private static final String CLOUD_BASE_URL = "https://api.example.com/v1";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(15);

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private Set<String> messagesOf(EndpointProperties properties) {
    Set<ConstraintViolation<EndpointProperties>> violations = validator.validate(properties);
    return violations.stream()
        .map(ConstraintViolation::getMessage)
        .collect(Collectors.toSet());
  }

  @Test
  @DisplayName("三项全缺时，三条报错各自指名对应的环境变量")
  void everyMissingFieldNamesItsOwnEnvironmentVariable() {
    Set<String> messages = messagesOf(new EndpointProperties("", "", "", TIMEOUT, IDLE_TIMEOUT));

    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.BASE_URL_ENV));
    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.API_KEY_ENV));
    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.MODEL_ENV));
  }

  @Test
  @DisplayName("base-url 少了 /v1 后缀时，报错给出「补上 /v1」级别的提示")
  void baseUrlWithoutV1SuffixIsRejected() {
    Set<String> messages =
        messagesOf(new EndpointProperties("https://api.example.com", "key", "model", TIMEOUT, IDLE_TIMEOUT));

    assertThat(messages).anyMatch(m -> m.contains("/v1"));
    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.BASE_URL_ENV));
  }

  @Test
  @DisplayName("带尾斜杠同样不被接受：它没有 /v1 结尾这个性质")
  void trailingSlashIsRejected() {
    Set<String> messages =
        messagesOf(new EndpointProperties("https://api.example.com/v1/", "key", "model", TIMEOUT, IDLE_TIMEOUT));

    assertThat(messages).anyMatch(m -> m.contains("/v1"));
  }

  @Test
  @DisplayName("非 http(s) 的地址不被接受")
  void nonHttpSchemeIsRejected() {
    Set<String> messages = messagesOf(new EndpointProperties("ftp://host/v1", "key", "model", TIMEOUT, IDLE_TIMEOUT));

    assertThat(messages).isNotEmpty();
  }

  @Test
  @DisplayName("形态正确的云端端点通过校验")
  void wellFormedCloudEndpointIsAccepted() {
    assertThat(messagesOf(
        new EndpointProperties(CLOUD_BASE_URL, "real-key", "deepseek-chat", TIMEOUT, IDLE_TIMEOUT)))
        .isEmpty();
  }

  @Test
  @DisplayName("超时为 0 或负数时在启动期就失败：等到第一次调用才炸等于把配置错误伪装成运行期故障")
  void nonPositiveTimeoutIsRejectedAtStartup() {
    assertThatThrownBy(
        () -> new EndpointProperties(CLOUD_BASE_URL, "key", "model", Duration.ZERO, IDLE_TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.TIMEOUT_ENV);
    assertThatThrownBy(() -> new EndpointProperties(
        CLOUD_BASE_URL, "key", "model", Duration.ofSeconds(-1), IDLE_TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.TIMEOUT_ENV);
  }

  @Test
  @DisplayName("帧间空闲超时为 0 或负数时同样在启动期失败：它和总时长是两件事，都要拦")
  void nonPositiveIdleTimeoutIsRejectedAtStartup() {
    assertThatThrownBy(
        () -> new EndpointProperties(CLOUD_BASE_URL, "key", "model", TIMEOUT, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.IDLE_TIMEOUT_ENV);
    assertThatThrownBy(() -> new EndpointProperties(
        CLOUD_BASE_URL, "key", "model", TIMEOUT, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.IDLE_TIMEOUT_ENV);
  }

  @Test
  @DisplayName("帧间空闲超时有默认值，且它与总时长不是同一个数")
  void idleTimeoutHasItsOwnDefault() {
    assertThat(EndpointProperties.DEFAULT_IDLE_TIMEOUT).isPositive();
    assertThat(EndpointProperties.DEFAULT_IDLE_TIMEOUT)
        .isNotEqualTo(EndpointProperties.DEFAULT_REQUEST_TIMEOUT);
  }

  @Test
  @DisplayName("超时为空时被 @NotNull 拦住")
  void missingTimeoutIsNamed() {
    Set<String> messages =
        messagesOf(new EndpointProperties(CLOUD_BASE_URL, "key", "model", null, IDLE_TIMEOUT));

    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.TIMEOUT_ENV));
  }

  @Test
  @DisplayName("帧间空闲超时为空时被 @NotNull 拦住，报错指名它自己的环境变量")
  void missingIdleTimeoutIsNamed() {
    Set<String> messages =
        messagesOf(new EndpointProperties(CLOUD_BASE_URL, "key", "model", TIMEOUT, null));

    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.IDLE_TIMEOUT_ENV));
  }

  @Test
  @DisplayName("本地 Ollama 端点通过校验：环回地址同样要满足 /v1 约定")
  void localOllamaEndpointIsAccepted() {
    assertThat(messagesOf(
        new EndpointProperties("http://127.0.0.1:11434/v1", "ollama", "q:", TIMEOUT, IDLE_TIMEOUT)))
        .isEmpty();
  }
}
