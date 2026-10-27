package io.github.airouter.core;

import java.util.Map;

/**
 * token 用量。
 *
 * <p>整个对象缺失表示上游没有给出用量 —— 这是与「给出了但都是零」不同的两件事。
 * 网关不替上游补数字：编造出来的用量会被拿去做成本核销，而且事后没人能区分它是算出来的还是编出来的。
 *
 * <p>厂商常在 usage 里嵌套自己的明细（缓存命中、thinking token、按模态拆分等）。
 * 这些字段网关同样不解释，但要把它们带出去：计费侧关心的往往正是明细。
 *
 * @param promptTokens 提示侧 token 数
 * @param completionTokens 生成侧 token 数
 * @param totalTokens 合计
 * @param passthrough usage 内部未被建模的字段，值是 JSON 原文
 */
public record CanonicalUsage(long promptTokens, long completionTokens, long totalTokens,
                             Map<String, String> passthrough) {

  /** 没有厂商明细时的常用写法。 */
  public CanonicalUsage(long promptTokens, long completionTokens, long totalTokens) {
    this(promptTokens, completionTokens, totalTokens, Map.of());
  }

  /** 缺字段在上游一侧是常态，这里统一成空映射。 */
  public CanonicalUsage {
    passthrough = passthrough == null ? Map.of() : Map.copyOf(passthrough);
  }
}
