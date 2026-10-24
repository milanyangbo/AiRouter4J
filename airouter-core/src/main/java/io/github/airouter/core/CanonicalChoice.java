package io.github.airouter.core;

import java.util.Map;

/**
 * 一条候选结果。
 *
 * <p>{@code passthrough} 挂在候选上而不只是响应上：厂商的特有字段很多是**逐候选**出现的
 * （日志概率、引用、拒答原因等），只放在响应顶层的话，这些字段会在翻译中被丢掉，
 * 而丢掉它们恰恰是「网关让厂商能力变差」最常见的形式。
 *
 * @param index 候选序号，与上游给出的一致
 * @param message 这一候选的内容
 * @param finishReason 停止的原因
 * @param passthrough 这一候选上未被建模的字段，值是 JSON 原文
 */
public record CanonicalChoice(
    int index,
    CanonicalMessage message,
    CanonicalFinishReason finishReason,
    Map<String, String> passthrough) {

  /** 缺字段在上游一侧是常态，这里统一成空映射，让下游不必判断 null。 */
  public CanonicalChoice {
    passthrough = passthrough == null ? Map.of() : Map.copyOf(passthrough);
  }
}
