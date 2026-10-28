package io.github.airouter.gateway.routing;

/**
 * 选择需要知道「这一次调用算谁的」，而这一次调用**认不出来**（没有调用方标识）。
 *
 * <p>它是被 Faces 在边界上收口成 400 的那一种失败：让客户端知道这一次请求少了什么，
 * 而不是让它在「每次打到不同实例」这件事上自己去猜原因。
 * 为什么要显式拒绝而不是退化成「这次就不粘 / 这次随便挑」：
 * 后者与来源项目那种静默失效是同一个形状 —— 系统照跑、日志干净，
 * 只是「按调用方分配」这个承诺在一部分调用上没有兑现。
 *
 * <p>**它只有一个实现，但可以有好几个消费者**：粘滞要拿它说「这次不知道该粘在哪」，
 * 两个 hash 策略要拿它说「这次算不出落点」。合成一个异常类型的理由很实际 ——
 * 若各有其名，Faces 那一处迟早会对其中一个给 400、对另一个给 503，
 * 而它们对客户端说的是同一句话：**带上你的标识再来**。
 *
 * <p>这一层只负责「认不出就把话说清楚」；标识怎么来是第 ① 步的事
 * （调用方标识的来源属阶段 6 / ADR-0018）。
 */
public final class CallerIdentityMissingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message 缺的是什么、**由谁**要它、以及怎么补。三个要素缺一个，
   *                运维拿到的还是一条「某项检查未通过」
   */
  public CallerIdentityMissingException(String message) {
    super(message);
  }

  /**
   * 粘滞的那个说法：它会顺带说出「不想带标识就把粘滞关掉」这一条出路，
   * 因为粘滞是一个**开关**，而 `ip-hash` 是一个策略 —— 关掉开关是合法的，换掉策略不是。
   *
   * @return 给粘滞那一条路径用的实例
   */
  public static CallerIdentityMissingException forStickySelection() {
    return new CallerIdentityMissingException("粘滞已开启，但这一次调用拿不到调用方标识："
        + "粘滞不知道该把它粘在哪一个实例上。请带上调用方标识，"
        + "或者把 airouter.routing.sticky-enabled 关掉");
  }

  /**
   * 需要一个标识才能算的策略（两个 hash）的说法。
   *
   * @param strategyId 策略在配置里的名字
   * @return 给那几个策略用的实例
   */
  public static CallerIdentityMissingException requiredBy(String strategyId) {
    return new CallerIdentityMissingException("策略 " + strategyId + " 要按调用方标识算落点，"
        + "但这一次调用认不出它算谁的：请带上调用方标识，或者换一个不依赖标识的策略");
  }
}
