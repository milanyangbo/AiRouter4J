package io.github.airouter.gateway.config;

import io.github.airouter.gateway.providers.UpstreamShape;
import java.time.Duration;
import java.util.List;

/**
 * 一个**实例**（GLOSSARY 的 {@code ModelInstance}）：三级模型的最底层一级。
 *
 * <p>它是 `{@code Endpoint}`（baseUrl / 凭证 / 超时）加上身份与治理位：
 * id、形态、厂商、权重、启用标志、标签。形态是**实例的属性**，不是全局设置 ——
 * 同一 Service 下两个实例可以有不同形态，那是合法配置（ADR-0010 第 1 节）。
 *
 * <p>检查放在构造器里而不是 Bean Validation 的注解上，因为这些规则**互相依赖**：
 * 形态决定地址长什么样、要不要凭证，而注解只看得见自己那一个字段。
 * 构造器检查的代价是「几个值全错」只报第一个 —— 那是这一阶段的取舍，
 * 需要「一次报全」时再引入校验器，那时它才有用武之地。
 *
 * <p>**为什么实例集合是列表而不是 Map**：用 `Map<String, InstanceDefinition>` 时，
 * 重复 id 在语法上不可能出现 —— 第二个静默覆盖第一个，写错的那个实例会被丢掉而不报错。
 * ADR-0010 第 4 节要求「同一 Service 下实例 id 重复」在**启动期**判红，
 * 只有当 id 是值而不是键的时候，这一条才有一条测试能替它说话。
 *
 * <p>列表的代价就写在这里，因为下次还会撞上：Spring Boot 绑定**列表元素**时不跨配置来源合并 ——
 * 在命令行里单独覆盖 `instances[0].base-url`，这个元素的其余字段不会被别的来源补齐，
 * 于是它会以一个几乎全空的形态到这里，表现为「id 缺失」而不是「地址被改了」。
 * 所以要改其中的字段，请在**同一个来源**里把这个实例的字段写全（local 档位就是这么做的）。
 *
 * @param id 实例 id。同一 Service 下必须唯一，重复则起不来
 * @param shape 这个实例说哪一种协议。**必须由配置显式给出**，不许按地址猜
 * @param vendor 厂商声明的名字。为 null 表示没有引用任何声明。
 *               **本阶段不校验它** —— 厂商声明的清单本身还没落地（ADR-0009 第 2 节的声明式厂商
 *               + 阶段 6 的凭据管理），而校验一个不存在的清单只能恒过。
 *               所以 ADR-0010 第 4 节列的那一条「引用了未声明的厂商」目前**尚未实现**，
 *               本类只把这个值搬给 {@link io.github.airouter.gateway.registry.RegisteredInstance}，
 *               不解释它。按 AGENTS.md「与 ADR 冲突要显式指出」，这处偏离写在这里也写在票 07 里
 * @param baseUrl 端点根地址。校验由形态做，见 {@link UpstreamShape#requireValidBaseUrl}
 * @param apiKey 实例自己的凭证。出网用的就是它，**不是调用方带来的那把**（凭据不透传）。
 *               形态不需要凭证的那一种（例如 Ollama 原生接口）可以没有它
 * @param model 默认模型名。客户端没指定模型时用它
 * @param requestTimeout 单次调用的总上限。它必须显式存在：留空让它掉进某个默认值，
 *                       等于把「一次请求最多占多久」变成一件只有看过实现才知道的事
 * @param weight 权重。选择链做加权时要拿它做除数，为 0 会在那里而不是在这里炸
 * @param enabled 启用标志。关闭的实例不参与选择
 * @param tags 标签。规则匹配（ADR-0012）按它挑目标
 */
public record InstanceDefinition(
    String id,
    UpstreamShape shape,
    String vendor,
    String baseUrl,
    String apiKey,
    String model,
    Duration requestTimeout,
    int weight,
    boolean enabled,
    List<String> tags) {

  /** 默认总超时。它只是兜底，不替代显式配置。 */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * @throws IllegalArgumentException id / 形态 / 地址 / 凭证 / 模型名缺失或不合形态时抛出。
   *     后果是**启动失败**：配置形态错误属于「能不能起来」的问题，不属于「对不对」的问题 ——
   *     等到第一次调用才炸，正是本项目要避开的那类失败
   */
  public InstanceDefinition {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("实例的 id 不能为空：它是区分同一个 Service 下两个实例的唯一东西。"
          + "顺便核对一下是不是在别处（命令行、另一个配置来源）只覆盖了这个实例的某一个字段 ——"
          + "列表元素不跨来源合并，那样会让这个实例只剩下被覆盖的那一项");
    }
    if (shape == null) {
      throw new IllegalArgumentException(
          "实例 " + id + " 缺少形态：请显式给出（本阶段支持 " + UpstreamShape.OPENAI.id()
              + "）。形态不能由地址猜 —— 同一台本机 Ollama 上 /v1 与 /api 两种地址都在");
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("实例 " + id + " 缺少端点地址");
    }
    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
      throw new IllegalArgumentException(
          "实例 " + id + " 的地址必须是一个 http(s) 地址，收到的是 " + baseUrl
              + "。本机地址写 127.0.0.1 不写 localhost");
    }
    // 地址形态由形态自己判：它是**启动期校验**而不是约定（ADR-0006 第 2 节）。
    // Spring AI 2.x 不再自动补 /v1，少了它不在启动期报错，只会变成一次运行期的 404。
    shape.requireValidBaseUrl(baseUrl);
    if (shape.requiresCredential() && (apiKey == null || apiKey.isBlank())) {
      throw new IllegalArgumentException(
          "实例 " + id + " 的形态 " + shape.id() + " 需要凭证，但没给出："
              + "凭证的唯一来源是环境变量，配置文件里只能是占位符");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("实例 " + id + " 缺少模型名");
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "实例 " + id + " 的总超时必须是正数，收到的是 " + requestTimeout);
    }
    if (weight <= 0) {
      throw new IllegalArgumentException(
          "实例 " + id + " 的权重必须为正数，收到的是 " + weight + "：它是加权选择的除数");
    }
    // 标签对外不可变：它会被交给规则匹配与限流作用域，谁都不该改得动同一份集合。
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
