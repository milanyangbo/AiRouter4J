package io.github.airouter.core;

import java.util.List;

/**
 * 生成参数。
 *
 * <p>这里只放网关要理解的参数（要做配额换算或要改写上限的那些）。
 * 其余参数不被建模，它们以 JSON 原文留在 {@link CanonicalRequest#passthrough()} 里被原样带到上游 ——
 * 这样新增一个厂商自定义参数不需要改动 Canonical。
 *
 * @param temperature 采样温度，未指定时为 null
 * @param topP 核采样概率，未指定时为 null
 * @param maxTokens 生成上限，未指定时为 null
 * @param stop 停止词，未指定时为空列表
 */
public record GenerationParams(Double temperature, Double topP, Integer maxTokens, List<String> stop) {

  /** 未指定任何参数时的取值。 */
  public static final GenerationParams EMPTY = new GenerationParams(null, null, null, List.of());

  /** stop 为空时统一成空列表，让下游只面对一种形态。 */
  public GenerationParams {
    stop = stop == null ? List.of() : List.copyOf(stop);
  }
}
