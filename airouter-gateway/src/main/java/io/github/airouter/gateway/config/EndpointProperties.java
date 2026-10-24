package io.github.airouter.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 上游模型端点（GLOSSARY 的 {@code Endpoint} = baseUrl / 凭证 / 超时）的配置绑定。
 *
 * <p>三个字段都标了 {@code @NotBlank} 而人们仅在两个 profile 之一里有默认值：
 * 缺省让值先绑定到空串，再由校验判红。这样做是为了拿到**属于本项目的报错文案** ——
 * 占位符不可解析时 Spring 自己的报错虽然会提到变量名，但它同时也提到一堆解析细节，
 * 而这条消息是要给半夜被叫起来的运维看的。
 *
 * <p>校验在容器刷新期进行（{@code @Validated} 由 ConfigurationProperties 绑定器触发），
 * 因此违约的后果是**启动失败**，而不是第一次调用时才炸。这一点是刻意的：
 * 配置形态错误属于「能不能起来」的问题，不属于「对不对」的问题。
 */
@Validated
@ConfigurationProperties(prefix = EndpointProperties.PREFIX)
public record EndpointProperties(

    /**
     * 端点根地址。必须以 {@code /v1} 结尾 —— Spring AI 2.x 不再自动补这个后缀，
     * 少了它不会报错，只会变成一次深入运行期的 404。
     *
     * <p>正则留给空串一条通路（{@code ^$}），让缺失与形态错误由各自的规则各自报一次，
     * 而不是同一处既说「没给」又说「给错了」。
     */
    @NotBlank(message = "缺少端点地址：请在环境变量 AIROUTER_ENDPOINT_BASE_URL 里给出")
    @Pattern(regexp = "^$|^https?://.+/v1$",
        message = "环境变量 AIROUTER_ENDPOINT_BASE_URL 必须以 /v1 结尾且不带尾斜杠"
            + "（例如 https://api.example.com/v1）：Spring AI 2.x 不再自动补这个后缀，"
            + "少了它不报错，只会变成一次运行期 404")
    String baseUrl,

    /**
     * 端点密钥。唯一来源也是环境变量；本地 Ollama 不校验密钥，因此本地档位给的是占位串，
     * 那不是凭证，任何受跟踪文件里都不该出现真实密钥。
     */
    @NotBlank(message = "缺少端点密钥：请在环境变量 AIROUTER_ENDPOINT_API_KEY 里给出；"
        + "本地 Ollama 用不到密钥，给任意非空占位即可")
    String apiKey,

    /** 默认模型名。 */
    @NotBlank(message = "缺少模型名：请在环境变量 AIROUTER_ENDPOINT_MODEL 里给出")
    String model,

    /**
     * 单次调用的总上限（GLOSSARY 的 {@code Endpoint} = baseUrl / 凭证 / 超时）。
     *
     * <p>超时必须显式存在。留空让它掉进某个默认值，等于把「一条请求最多占多久」
     * 变成一件只有看过实现才知道的事；流式接进来之后还要在它旁边区分「帧间空闲」，
     * 那时更不能有一个含糊的默认值挡在前面。
     */
    @NotNull(message = "缺少调用超时：请在环境变量 AIROUTER_ENDPOINT_TIMEOUT 里给出")
    Duration requestTimeout,

    /**
     * 流式调用的帧间静默上限（GLOSSARY 的 {@code IdleTimeout}）。**它不是总时长**：
     * 一条流可以持续很久，只要上游一直在说话。给流设总时长上限等于替调用方决定
     * 「模型最多想多久」，而那件事网关无权决定。
     *
     * <p>默认值给了它，与 {@code requestTimeout} 不同：帧间静默是一个只有流式路径才有的概念，
     * 让它跟着总时长走会得到一个要么太松（长流被掐断）要么太紧（冷启动被判死）的数。
     */
    @NotNull(message = "缺少帧间空闲超时：请在环境变量 AIROUTER_ENDPOINT_IDLE_TIMEOUT 里给出")
    Duration idleTimeout) {

  /**
   * 两个超时都必须是正数。
   *
   * <p>这里用构造器检查而不是 Bean Validation：手上的 validator 已经没有 Duration 范围的约束了，
   * 而「等到第一次调用才因为 0 或负数炸掉」正是本项目要避开的那类失败。放在这里至少是起不来。
   *
   * @throws IllegalArgumentException 任一个超时为 0 或负数时抛出
   */
  public EndpointProperties {
    requirePositive(requestTimeout, TIMEOUT_ENV);
    requirePositive(idleTimeout, IDLE_TIMEOUT_ENV);
  }

  private static void requirePositive(Duration value, String env) {
    if (value != null && (value.isZero() || value.isNegative())) {
      throw new IllegalArgumentException("环境变量 " + env + " 必须是正数，收到的是 " + value);
    }
  }

  /** 属性前缀。写在常量里，让 yml、测试与出错信息引用的是同一个字符串。 */
  public static final String PREFIX = "airouter.endpoint";

  /** base-url 的环境变量名。缺值时报错要指名道姓，不能只给属性路径 —— 运维看的是环境，不是属性树。 */
  public static final String BASE_URL_ENV = "AIROUTER_ENDPOINT_BASE_URL";

  /** api-key 的环境变量名。 */
  public static final String API_KEY_ENV = "AIROUTER_ENDPOINT_API_KEY";

  /** model 的环境变量名。 */
  public static final String MODEL_ENV = "AIROUTER_ENDPOINT_MODEL";

  /** request-timeout 的环境变量名。 */
  public static final String TIMEOUT_ENV = "AIROUTER_ENDPOINT_TIMEOUT";

  /** idle-timeout 的环境变量名。 */
  public static final String IDLE_TIMEOUT_ENV = "AIROUTER_ENDPOINT_IDLE_TIMEOUT";

  /** 默认总超时。它只是兜底，不替代显式配置。 */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * 默认的帧间静默上限。
   *
   * <p>取 15 秒的依据是「上游停下来思考」与「上游没了」的分界：
   * 真实端点在冷启动与长推理之间出现十几秒静默是常态，而超过这个量级还不说话通常已经是故障。
   * 它不是调优结论，是一个可配置的起点 —— 真正的取值要等有了流式样本之后再定。
   */
  public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(15);
}
