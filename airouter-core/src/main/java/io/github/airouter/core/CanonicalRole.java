package io.github.airouter.core;

/**
 * 消息的角色。
 *
 * <p>取值是网关自己的枚举，不是厂商的字符串常量：厂商写法只在 Face 与 Provider 的边界上被认识一次，
 * 进入 Canonical 之后就与具体协议无关了。
 */
public enum CanonicalRole {

  /** 系统提示。 */
  SYSTEM,

  /** 使用者输入。 */
  USER,

  /** 模型产出。 */
  ASSISTANT,

  /** 工具执行结果。 */
  TOOL
}
