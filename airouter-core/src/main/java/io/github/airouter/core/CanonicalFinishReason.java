package io.github.airouter.core;

import java.util.Objects;

/**
 * 一次生成为什么停下来。
 *
 * <p>它被做成一个带常量的值类型而不是枚举，是因为**网关必须容忍上游的取值**：
 * 一个声称 OpenAI 兼容的实例返回意料之外的 finish_reason 时，网关应该把它原样带出境，
 * 而不是在自己这一层炸掉（ADR-0004 冲突二）。枚举遇到未知值只能抛异常或丢信息，两者都不合适。
 */
public final class CanonicalFinishReason {

  /** 自然结束。 */
  public static final CanonicalFinishReason STOP = new CanonicalFinishReason("stop");

  /** 触到生成上限。 */
  public static final CanonicalFinishReason LENGTH = new CanonicalFinishReason("length");

  /** 请求工具调用。 */
  public static final CanonicalFinishReason TOOL_CALLS = new CanonicalFinishReason("tool_calls");

  /** 被内容过滤拦下。 */
  public static final CanonicalFinishReason CONTENT_FILTER = new CanonicalFinishReason("content_filter");

  private static final CanonicalFinishReason[] KNOWN = {STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER};

  private final String raw;

  private CanonicalFinishReason(String raw) {
    this.raw = raw;
  }

  /**
   * 从厂商给出的字符串还原。已知取值复用同一个实例，未知取值原样保留。
   *
   * @param raw 厂商原文，允许为 null
   * @return 对应的终态理由；未知取值原样保留
   */
  public static CanonicalFinishReason of(String raw) {
    String value = raw == null ? "" : raw;
    for (CanonicalFinishReason known : KNOWN) {
      if (known.raw.equals(value)) {
        return known;
      }
    }
    return new CanonicalFinishReason(value);
  }

  /** 厂商原文，用于原样出境。 */
  public String raw() {
    return raw;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CanonicalFinishReason cast)) {
      return false;
    }
    return raw.equals(cast.raw);
  }

  @Override
  public int hashCode() {
    return Objects.hash(raw);
  }

  @Override
  public String toString() {
    return raw;
  }
}
