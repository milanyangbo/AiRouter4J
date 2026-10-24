package io.github.airouter.core;

import java.util.List;
import java.util.Map;

/**
 * 一次非流式调用的结果。
 *
 * <p>它是 Face 与 Provider 之间响应的唯一形状：Provider 只负责把厂商字节译到这里，
 * Face 只负责从这里编码到自己的 wire format。
 *
 * @param id 上游给出的响应标识。上游没给时为空串，由 Face 决定要不要降级显示
 * @param model 实际处理请求的模型名，可能与请求里指定的不同
 * @param choices 候选列表
 * @param usage token 用量。**上游没给就是 null**，不由任何一层补零或估算
 * @param passthrough 未被建模的上游字段，值是 JSON 原文。
 *                    厂商特有的能力信息走这条路回到客户端，网关不需要认识它们
 */
public record CanonicalResponse(
    String id,
    String model,
    List<CanonicalChoice> choices,
    CanonicalUsage usage,
    Map<String, String> passthrough) {

  /** 缺字段在上游一侧是常态，这里统一成空列表与空映射。 */
  public CanonicalResponse {
    choices = choices == null ? List.of() : List.copyOf(choices);
    passthrough = passthrough == null ? Map.of() : Map.copyOf(passthrough);
  }
}
