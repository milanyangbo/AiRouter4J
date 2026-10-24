package io.github.airouter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.providers.UpstreamShape;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Endpoint 的几个字段什么时候算填错，以及**报错文案指向什么**。
 *
 * <p>这里断言的是「缺值时报错里出现的是环境变量名」。运维半夜看的是<em>环境</em>，不是一颗属性树：
 * 报 {@code airouter.endpoint.api-key 不能为空} 的人，得先把属性名换算成环境变量名才能动手。
 * 这个换算每一次都做得不必要地昂贵。
 *
 * <p>形态（{@code provider}）进来之后，有一类校验换了位置：**地址该长什么样**取决于形态，
 * 而 Bean Validation 的注解只看得见自己那一个字段，因此那一半挪进了构造器。
 * 本文件里对应地从「收集校验消息」改成「构造时就抛」 —— 时机上它们仍然是启动期，
 * 只是不再经过 validator。这也是为什么下面两种断言方式并存：
 * 与形态无关的那些仍在 validator 里，与形态有关的那些在构造器里。
 */
@Tag("unit")
final class EndpointPropertiesValidationTest {

  private static final String CLOUD_BASE_URL = "https://api.example.com/v1";

  private static final String OLLAMA_BASE_URL = "http://127.0.0.1:11434/api";

  /** 配置里的取值写成形态自己的名字：字面量一旦与 {@link UpstreamShape#id()} 分家，这一片用例立刻红。 */
  private static final String OPENAI = UpstreamShape.OPENAI.id();

  private static final String OLLAMA = UpstreamShape.OLLAMA.id();

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
  @DisplayName("地址与模型全缺时，两条报错各自指名对应的环境变量")
  void everyMissingFieldNamesItsOwnEnvironmentVariable() {
    Set<String> messages = messagesOf(new EndpointProperties("", "key", "", TIMEOUT, IDLE_TIMEOUT, OPENAI));

    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.BASE_URL_ENV));
    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.MODEL_ENV));
  }

  /**
   * 缺密钥只算违约于**需要凭证的形态**，因此它不是字段上的 {@code @NotBlank}，
   * 而是一条读得到形态的断言（见 {@link EndpointProperties#hasApiKeyWhenTheShapeNeedsOne()}）。
   * 放在校验器里而不是构造器里，是为了让它与「地址缺失」「模型缺失」能被同一次报错说全 ——
   * 构造即抛时后两条一条也拿不到。
   */
  @Test
  @DisplayName("OpenAI 兼容形态缺密钥时被拦住，报错指名环境变量")
  void missingApiKeyIsRejectedForAuthenticatedShapes() {
    assertThat(messagesOf(new EndpointProperties(CLOUD_BASE_URL, "", "model", TIMEOUT, IDLE_TIMEOUT, OPENAI)))
        .anyMatch(message -> message.contains(EndpointProperties.API_KEY_ENV));
  }

  @Test
  @DisplayName("形态 openai 的地址少了 /v1 后缀时，报错把期望的形态说出来")
  void baseUrlWithoutV1SuffixIsRejected() {
    assertThatThrownBy(() -> new EndpointProperties(
        "https://api.example.com", "key", "model", TIMEOUT, IDLE_TIMEOUT, OPENAI))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/v1")
        .hasMessageContaining(EndpointProperties.BASE_URL_ENV);
  }

  @Test
  @DisplayName("带尾斜杠同样不被接受：它没有 /v1 结尾这个性质")
  void trailingSlashIsRejected() {
    assertThatThrownBy(() -> new EndpointProperties(
        "https://api.example.com/v1/", "key", "model", TIMEOUT, IDLE_TIMEOUT, OPENAI))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/v1");
  }

  @Test
  @DisplayName("非 http(s) 的地址不被接受")
  void nonHttpSchemeIsRejected() {
    assertThatThrownBy(() -> new EndpointProperties(
        "ftp://host/v1", "key", "model", TIMEOUT, IDLE_TIMEOUT, OPENAI))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("形态正确的云端端点通过校验")
  void wellFormedCloudEndpointIsAccepted() {
    assertThat(messagesOf(
        new EndpointProperties(CLOUD_BASE_URL, "real-key", "deepseek-chat", TIMEOUT, IDLE_TIMEOUT, OPENAI)))
        .isEmpty();
  }

  /**
   * 本机 Ollama 同时提供两种接口：{@code /v1} 是兼容接口，{@code /api} 是原生接口。
   * 同一个地址在两个形态下的结论相反，这正是「形态必须由配置显式给出」的理由 ——
   * 按地址猜会在这种端点上必然猜错一次。
   */
  @Test
  @DisplayName("同一个本机端点：/v1 在 openai 形态下通过，在 ollama 形态下被拒")
  void sameHostIsJudgedByShapeNotByAddress() {
    assertThat(messagesOf(
        new EndpointProperties("http://127.0.0.1:11434/v1", "ollama", "q:", TIMEOUT, IDLE_TIMEOUT, OPENAI)))
        .isEmpty();

    assertThatThrownBy(() -> new EndpointProperties(
        "http://127.0.0.1:11434/v1", "ollama", "q:", TIMEOUT, IDLE_TIMEOUT, OLLAMA))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/api");
  }

  @Test
  @DisplayName("Ollama 原生形态接受 /api 地址，且不需要密钥")
  void ollamaShapeAcceptsNativeAddressWithoutApiKey() {
    assertThat(messagesOf(new EndpointProperties(OLLAMA_BASE_URL, "", "qwen2.5:0.5b", TIMEOUT, IDLE_TIMEOUT, OLLAMA)))
        .isEmpty();
  }

  @Test
  @DisplayName("未知的形态在启动期就失败：配错形态会让每一次调用打在错误的协议上")
  void unknownShapeIsRejectedAtStartup() {
    assertThatThrownBy(() -> new EndpointProperties(
        "https://api.example.com/v9", "key", "model", TIMEOUT, IDLE_TIMEOUT, "gemini"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(OPENAI)
        .hasMessageContaining(OLLAMA);
  }

  @Test
  @DisplayName("超时为 0 或负数时在启动期就失败：等到第一次调用才炸等于把配置错误伪装成运行期故障")
  void nonPositiveTimeoutIsRejectedAtStartup() {
    assertThatThrownBy(
        () -> new EndpointProperties(CLOUD_BASE_URL, "key", "model", Duration.ZERO, IDLE_TIMEOUT, OPENAI))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.TIMEOUT_ENV);
    assertThatThrownBy(() -> new EndpointProperties(
        CLOUD_BASE_URL, "key", "model", Duration.ofSeconds(-1), IDLE_TIMEOUT, OPENAI))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.TIMEOUT_ENV);
  }

  @Test
  @DisplayName("帧间空闲超时为 0 或负数时同样在启动期失败：它和总时长是两件事，都要拦")
  void nonPositiveIdleTimeoutIsRejectedAtStartup() {
    assertThatThrownBy(
        () -> new EndpointProperties(CLOUD_BASE_URL, "key", "model", TIMEOUT, Duration.ZERO, OPENAI))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(EndpointProperties.IDLE_TIMEOUT_ENV);
    assertThatThrownBy(() -> new EndpointProperties(
        CLOUD_BASE_URL, "key", "model", TIMEOUT, Duration.ofSeconds(-1), OPENAI))
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
        messagesOf(new EndpointProperties(CLOUD_BASE_URL, "key", "model", null, IDLE_TIMEOUT, OPENAI));

    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.TIMEOUT_ENV));
  }

  @Test
  @DisplayName("帧间空闲超时为空时被 @NotNull 拦住，报错指名它自己的环境变量")
  void missingIdleTimeoutIsNamed() {
    Set<String> messages =
        messagesOf(new EndpointProperties(CLOUD_BASE_URL, "key", "model", TIMEOUT, null, OPENAI));

    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.IDLE_TIMEOUT_ENV));
  }

  @Test
  @DisplayName("形态缺值时被 @NotBlank 拦住，报错给出可选值")
  void missingShapeIsNamed() {
    Set<String> messages =
        messagesOf(new EndpointProperties(CLOUD_BASE_URL, "key", "model", TIMEOUT, IDLE_TIMEOUT, ""));

    assertThat(messages).anyMatch(m -> m.contains(EndpointProperties.PROVIDER_ENV));
  }
}
