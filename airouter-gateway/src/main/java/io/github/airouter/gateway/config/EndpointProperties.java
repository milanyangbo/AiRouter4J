package io.github.airouter.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上游模型端点（GLOSSARY 的 {@code Endpoint} = baseUrl / 凭证 / 超时 / 形态）的配置绑定。
 *
 * <p>四个字段都在这里做检查，且报错**指名环境变量名**：运维看的是环境，不是属性树，
 * 一句「属性 airouter.endpoint.base-url 不能为空」要让人先去查它对应哪个变量。
 *
 * <p>检查放在构造器里而不是 Bean Validation 的注解上，是因为这些规则**互相依赖**
 * （形态决定地址规则与凭证要求，而形态这一位随票 06 进来），注解只看得见自己那一个字段。
 * 构造器检查的代价是「几个值全缺」只报第一个 —— 那是这一阶段的取舍，
 * 等到形态进来需要「一次报全」时再引入校验器，那时它才有用武之地。
 *
 * @param baseUrl 端点根地址。OpenAI 兼容端点以 {@code /v1} 结尾。
 *                少了后缀不会在启动期报错，只会变成一次深入运行期的 404 ——
 *                地址形态的**启动期校验**随形态一起进来（票 06）
 * @param apiKey 端点密钥。唯一来源是环境变量；配置文件里只能是占位符，不是凭证
 * @param model 默认模型名。客户端没指定模型时用它
 * @param requestTimeout 单次调用的总上限。它必须显式存在：
 *                       留空让它掉进某个默认值，等于把「一条请求最多占多久」
 *                       变成一件只有看过实现才知道的事
 */
@ConfigurationProperties(prefix = EndpointProperties.PREFIX)
public record EndpointProperties(
    String baseUrl,
    String apiKey,
    String model,
    Duration requestTimeout) {

  /** 属性前缀。写在常量里，让 yml、测试与出错信息引用的是同一个字符串。 */
  public static final String PREFIX = "airouter.endpoint";

  /** base-url 的环境变量名。 */
  public static final String BASE_URL_ENV = "AIROUTER_ENDPOINT_BASE_URL";

  /** api-key 的环境变量名。 */
  public static final String API_KEY_ENV = "AIROUTER_ENDPOINT_API_KEY";

  /** model 的环境变量名。 */
  public static final String MODEL_ENV = "AIROUTER_ENDPOINT_MODEL";

  /** request-timeout 的环境变量名。 */
  public static final String TIMEOUT_ENV = "AIROUTER_ENDPOINT_TIMEOUT";

  /** 默认总超时。它只是兜底，不替代显式配置。 */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * @throws IllegalArgumentException 地址缺失或不是 http(s)、凭证缺失、模型名缺失、
   *     或超时不是正数时抛出。后果是**启动失败**：配置形态错误属于「能不能起来」的问题，
   *     不属于「对不对」的问题 —— 等到第一次调用才炸，正是本项目要避开的那类失败
   */
  public EndpointProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException(
          "缺少端点地址：请在环境变量 " + BASE_URL_ENV + " 里给出（例如 http://127.0.0.1:11434/v1）");
    }
    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
      throw new IllegalArgumentException(
          "环境变量 " + BASE_URL_ENV + " 必须是一个 http(s) 地址，收到的是 " + baseUrl);
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException(
          "缺少端点密钥：请在环境变量 " + API_KEY_ENV + " 里给出");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException(
          "缺少模型名：请在环境变量 " + MODEL_ENV + " 里给出");
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "环境变量 " + TIMEOUT_ENV + " 必须是正数，收到的是 " + requestTimeout);
    }
  }
}
