package io.github.airouter.gateway.routing;

/**
 * 粘滞开着，但**这一次调用认不出它算谁的**（缺失调用方标识，也没有自定义键）。
 *
 * <p>它是被 Faces 在边界上收口成 400 的那一种失败：让客户端知道这一次请求少了什么，
 * 而不是让它在「每次打到不同实例」这件事上自己去猜原因。为什么要显式拒绝而不是退化成不选粘滞：
 * 后者与来源项目那种静默失效是同一个形状 —— 系统照跑、日志干净、
 * 只是「配了粘滞」这个承诺在一部分调用上没有兑现。
 *
 * <p>粘滞这一层只负责「拿不到键就把话说清楚」；补齐键的来源是第 ① 步的事
 * （调用方标识怎么来属阶段 6 / ADR-0018），换 instanceId 之外的复用方式则由
 * {@code select} 那边的键解析器承担。
 */
public final class StickyKeyMissingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 默认的措辞：说清「缺什么」与「怎么补」，而不是「某项检查未通过」。 */
  private static final String MESSAGE = "粘滞已开启，但这一次调用拿不到调用方标识："
      + "粘滞不知道该把它粘在哪一个实例上。请带上调用方标识，"
      + "或者把 airouter.routing.sticky-enabled 关掉";

  public StickyKeyMissingException() {
    super(MESSAGE);
  }
}
