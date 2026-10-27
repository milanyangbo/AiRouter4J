package io.github.airouter.core;

import java.util.Map;

/**
 * 一条 Canonical 消息。
 *
 * <p>此刻被建模的只有角色与正文。工具调用、多模态内容块这些分支留给真正需要它们的那一票 ——
 * 提前把字段占好，等于让每一次使用者都要先判断某个字段自己该不该填。
 *
 * <p>其余字段走 {@code passthrough}：厂商往消息上挂私有字段是很常见的做法，
 * 这些字段网关不解释，但必须在两个方向上都能跟着走。
 *
 * @param role 角色，不为 null
 * @param content 正文。工具结果与现阶段的其他形态都落在这里；没有正文时是空串而不是 null
 * @param passthrough 未被建模的消息字段，值是 JSON 原文
 */
public record CanonicalMessage(CanonicalRole role, String content, Map<String, String> passthrough) {

  /** 没有额外字段时的常用写法。 */
  public CanonicalMessage(CanonicalRole role, String content) {
    this(role, content, Map.of());
  }

  /** 空正文统一表示成空串、缺字段统一成空映射：下游不必到处判 null。 */
  public CanonicalMessage {
    content = content == null ? "" : content;
    passthrough = passthrough == null ? Map.of() : Map.copyOf(passthrough);
  }
}
