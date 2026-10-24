package io.github.airouter.gateway.config;

import io.github.airouter.gateway.providers.UpstreamShape;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 上游模型端点（GLOSSARY 的 {@code Endpoint} = baseUrl / 凭证 / 超时 / 形态）的配置绑定。
 *
 * <p>三个字段都标了 {@code @NotBlank} 而人们仅在两个 profile 之一里有默认值：
 * 缺省让值先绑定到空串，再由校验判红。这样做是为了拿到**属于本项目的报错文案** ——
 * 占位符不可解析时 Spring 自己的报错虽然会提到变量名，但它同时也提到一堆解析细节，
 * 而这条消息是要给半夜被叫起来的运维看的。
 *
 * <p>校验在容器刷新期进行（{@code @Validated} 由 ConfigurationProperties 绑定器触发），
 * 因此违约的后果是**启动失败**，而不是第一次调用时才炸。这一点是刻意的：
 * 配置形态错误属于「能不能起来」的问题，不属于「对不对」的问题。
 *
 * <p>形态（{@link #provider}）进来之后，有两件事改了做法：
 * <ul>
 *   <li>地址形态的规则不再写死成「必须以 {@code /v1} 结尾」。那条规则是 OpenAI 兼容端点的事实，
 *       不是所有端点的事实 —— Ollama 的原生接口在 {@code /api} 上。
 *       现在地址该长什么样由 {@link UpstreamShape} 回答，形态自己说它要求什么；
 *   <li>凭证不是每种形态都要。原生接口没有「证明你是谁」这一步，
 *       因此「缺密钥」只在 {@link UpstreamShape#needsApiKey()} 为真时才算违约。
 * </ul>
 * 两条都是**新增一个形态时不该回头改的地方**：改了就说明形态没有把自己说全。
 */
@Validated
@ConfigurationProperties(prefix = EndpointProperties.PREFIX)
public record EndpointProperties(

    /**
     * 端点根地址。它的形态由 {@link #provider} 决定：OpenAI 兼容端点以 {@code /v1} 结尾，
     * Ollama 原生以 {@code /api} 结尾。
     *
     * <p>结尾为什么这么要紧：少了它不会在启动期报错，只会变成一次深入运行期的 404。
     * 这里只保留「是不是一个 http(s) 地址」这一条与形态无关的约束，
     * 与形态有关的那一半在构造器里 —— 那里才知道对面说的是哪一种话。
     *
     * <p>正则留给空串一条通路（{@code ^$}），让缺失与形态错误由各自的规则各自报一次，
     * 而不是同一处既说「没给」又说「给错了」。
     */
    @NotBlank(message = "缺少端点地址：请在环境变量 AIROUTER_ENDPOINT_BASE_URL 里给出")
    @Pattern(regexp = "^$|^https?://.+$",
        message = "环境变量 AIROUTER_ENDPOINT_BASE_URL 必须是一个 http(s) 地址"
            + "（例如 https://api.example.com/v1）")
    String baseUrl,

    /**
     * 端点密钥。唯一来源也是环境变量；本地 Ollama 不校验密钥，因此本地档位给的是占位串，
     * 那不是凭证，任何受跟踪文件里都不该出现真实密钥。
     *
     * <p>非空不是无条件要求：只有需要凭证的形态才要求它（见 {@link UpstreamShape#needsApiKey()}）。
     */
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
    Duration idleTimeout,

    /**
     * 上游的 wire 形态：{@code openai}（默认，OpenAI 兼容端点）或 {@code ollama}（原生接口）。
     *
     * <p>它决定的是**对面说哪一种话**，不是「用哪个实现类」这种装配细节。
     * 之所以把它做成配置而不是让代码按地址猜：猜的依据（比如地址里有没有 {@code /v1}）
     * 在遇到一个同时提供两种接口的端点时会立刻失效 —— 本机 Ollama 正好是这样的端点，
     * 它既有 {@code /v1}（兼容）也有 {@code /api}（原生）。让它显式，两个接口可以同时被使用。
     */
    @NotBlank(message = "缺少端点形态：请在环境变量 AIROUTER_ENDPOINT_PROVIDER 里给出"
        + "（openai 或 ollama）")
    String provider) {

  /**
   * 两个超时都必须是正数，地址与形态必须相符，需要凭证的形态必须有凭证。
   *
   * <p>这里用构造器检查而不是 Bean Validation：这三件事都**依赖另一个字段的取值**
   * （形态决定地址规则与凭证要求），而 Bean Validation 的注解只能看见自己那一个字段。
   * 放在这里至少是起不来 —— 等到第一次调用才因为地址少了 {@code /v1} 或
   * 因为形态未知炸掉，正是本项目要避开的那类失败。
   *
   * @throws IllegalArgumentException 超时非正、地址与形态不符、或需要凭证却没有凭证时抛出
   */
  public EndpointProperties {
    requirePositive(requestTimeout, TIMEOUT_ENV);
    requirePositive(idleTimeout, IDLE_TIMEOUT_ENV);
    // 形态缺失时什么都不判：「没给」由 @NotBlank 报，「给错了」由这里报，
    // 同一处既说缺失又说给错，两条消息会互相盖住对方。
    if (provider != null && !provider.isBlank()) {
      UpstreamShape shape = UpstreamShape.of(provider);
      if (baseUrl != null && !baseUrl.isBlank() && !shape.matchesAddress(baseUrl)) {
        throw new IllegalArgumentException(
            "环境变量 " + BASE_URL_ENV + " 与形态 " + shape.id() + " 不符：应当是"
                + shape.addressExpectation() + "，收到的是 " + baseUrl);
      }
    }
  }

  /**
   * 需要凭证的形态必须有凭证。
   *
   * <p>它是一条 Bean Validation 断言而不是构造器里的检查，理由只有一条：
   * 构造器抛异常时，**同一个对象上其它字段的校验消息一条也拿不到** ——
   * 于是「三个变量全缺」会变成「只报缺密钥」，而读报错的人会以为另外两个是填了的。
   * 缺值要能被一次性报全，这条约束就必须留在校验器里，
   * 哪怕它读的是另一个字段（形态）。为此它长成一个方法而不是一个注解。
   *
   * @return 不需要凭证，或需要且已给出时返回 true
   */
  @AssertTrue(message = "缺少端点密钥：请在环境变量 AIROUTER_ENDPOINT_API_KEY 里给出；"
      + "Ollama 原生接口不需要密钥，切到那一形态（ollama）即可不用填")
  public boolean hasApiKeyWhenTheShapeNeedsOne() {
    if (provider == null || provider.isBlank()) {
      return true;
    }
    return !UpstreamShape.of(provider).needsApiKey() || (apiKey != null && !apiKey.isBlank());
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

  /** provider（wire 形态）的环境变量名。 */
  public static final String PROVIDER_ENV = "AIROUTER_ENDPOINT_PROVIDER";

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
