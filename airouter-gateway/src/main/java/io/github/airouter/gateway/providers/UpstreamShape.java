package io.github.airouter.gateway.providers;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 上游 wire 形态的自述。
 *
 * <p>它回答的是「对面说的是哪一种话」，而这件事此前是**隐含在唯一那个 Provider 里的**：
 * 地址必须以 {@code /v1} 结尾、请求要带 {@code Authorization} 头、流式是 SSE ——
 * 三条都是 OpenAI 兼容端点的事实，被写成了全局规则。只有一个 Provider 时看不出问题，
 * 因为隐含与显式给出同一个答案；第二个 Provider 一出现，隐含的那部分立刻变成错的：
 * Ollama 的原生接口在 {@code /api} 上、不认凭证、流式是逐行 JSON 而不是 SSE。
 *
 * <p>因此形态不是配置项的一个枚举值，而是一组**由形态自己回答的问题**：
 * 地址长什么样、要不要凭证。把它们放在这里而不是散在配置校验与装配代码里，
 * 是为了让「新增一种形态要改哪些地方」有一个可数的答案；
 * 反过来，出现第三个形态时如果需要回到别处加分支，说明这里漏了问题。
 *
 * <p>它不回答「路径是什么」「帧怎么切」：那两件事属于各自的 codec 与分帧器 ——
 * 那里才是形态差异真正落地的位置，把它们也收进这个枚举会让它变成半个 Provider。
 */
public enum UpstreamShape {

  /** OpenAI 兼容端点。地址以 {@code /v1} 结尾，凭证是协议的一部分。 */
  OPENAI("openai", "以 /v1 结尾", Pattern.compile("^https?://.+/v1$"), true),

  /**
   * Ollama 原生接口（{@code /api/chat}）。地址以 {@code /api} 结尾。
   *
   * <p>不认凭证：原生接口没有「证明你是谁」这一步，鉴权是外层的事。
   * 把本地档位那个与凭证无关的占位串当作 bearer 发出去，只会让读日志的人以为
   * 这一次调用是被授权过的。
   */
  OLLAMA("ollama", "以 /api 结尾", Pattern.compile("^https?://.+/api$"), false);

  private final String id;

  private final String addressExpectation;

  private final Pattern addressPattern;

  private final boolean authenticated;

  UpstreamShape(String id, String addressExpectation, Pattern addressPattern, boolean authenticated) {
    this.id = id;
    this.addressExpectation = addressExpectation;
    this.addressPattern = addressPattern;
    this.authenticated = authenticated;
  }

  /**
   * 配置值 → 形态。
   *
   * <p>未知取值直接抛而不是回落到一个默认形态：配错形态的后果是每一次调用都打在错误的协议上，
   * 而「打错了还一直在打」比「起不来」难查得多。启动期发现是这里唯一合理的时机。
   *
   * @param raw 配置里给出的形态名
   * @return 对应形态
   * @throws IllegalArgumentException 取值不属于任何一种已知形态时抛出
   */
  public static UpstreamShape of(String raw) {
    String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    for (UpstreamShape shape : values()) {
      if (shape.id.equals(normalized)) {
        return shape;
      }
    }
    throw new IllegalArgumentException(
        "未知的 upstream 形态：" + raw + "（可选值为 " + OPENAI.id + " / " + OLLAMA.id + "）");
  }

  /** 配置里写它的那个名字。环境变量与 yml 用的是它，不是枚举常量名。 */
  public String id() {
    return id;
  }

  /** 地址形态的人话版本，用于把报错写成「应该是……而你给的是……」而不是一段正则。 */
  public String addressExpectation() {
    return addressExpectation;
  }

  /**
   * 地址是不是这个形态要求的样子。
   *
   * @param baseUrl 配置给出的端点根地址
   * @return 符合返回 true
   */
  public boolean matchesAddress(String baseUrl) {
    return baseUrl != null && addressPattern.matcher(baseUrl).matches();
  }

  /** 这个形态的协议里有没有凭证这一项。 */
  public boolean needsApiKey() {
    return authenticated;
  }
}
