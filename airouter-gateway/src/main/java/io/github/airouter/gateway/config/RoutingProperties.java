package io.github.airouter.gateway.config;

import io.github.airouter.gateway.select.SelectionStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 路由这一级的配置：**第 ⑥ 步用哪个策略**、**第 ⑤ 步粘不粘**、
 * 以及两个需要额外输入的策略**各自那几个数**。
 *
 * <p>为什么由配置决定而不是写在装配代码里：「用哪个策略」是运行时该能回答的问题，
 * 写在代码里它就变成一件要读一行 Java 才能知道的事，改一个集群的流量走向要重新发一次版。
 *
 * <p>这些值都**有默认值**，且默认值只写在 {@code application.yml} 那一处占位符里。
 * 不给默认值的后果是每次起进程都得先把它们凑齐，而「粘滞」这种开关显然不该是必填项。
 * 但**一旦给了值就要合法** —— 写错策略名必须起不来，而不是静默退化成一个没点的策略
 * （票 07 那三个「有配置、无消费者」的字段就是那个形状）。
 *
 * <p>后面这几个数（alpha / 惩罚值 / 保留窗口 / 虚拟节点数）及格线是 **显式** 且 **可配**：
 * 它们藏起来的话，「为什么这台机器过了这么久才被降权」就变成一件只能读实现才知道的事。
 * 单位写进名字（millis），因为它必须是能和环境变量上那个数字直接对上的单位。
 *
 * @param strategy 第 ⑥ 步的策略名；可选取值见 {@link SelectionStrategy#describeAll()}
 * @param stickyEnabled 第 ⑤ 步的开关。**开了之后每一次调用都必须能被认出来**：
 *                      认不出的调用会被粘滞那一层拒掉，而不是退化成每次换一个实例
 * @param stickyMaxEntries 粘滞表的容量上限。它是「最多记住多少个调用方」，
 *                         不是「最多允许多少并发」 —— 超了挤掉最久没来的那一个
 * @param latency {@code latency} 策略要用的三个数
 * @param virtualNodesPerWeight {@code consistent-hash} 每个单位权重要占多少个虚拟节点
 */
@ConfigurationProperties(prefix = RoutingProperties.PREFIX)
public record RoutingProperties(String strategy, boolean stickyEnabled, int stickyMaxEntries,
    Latency latency, int virtualNodesPerWeight) {

  /** 属性前缀。写在常量里，让 yml、测试与出错信息引用的是同一个字符串。 */
  public static final String PREFIX = "airouter.routing";

  /** strategy 的环境变量名。 */
  public static final String STRATEGY_ENV = "AIROUTER_ROUTING_STRATEGY";

  /** sticky-enabled 的环境变量名。 */
  public static final String STICKY_ENABLED_ENV = "AIROUTER_ROUTING_STICKY_ENABLED";

  /** sticky-max-entries 的环境变量名。 */
  public static final String STICKY_MAX_ENTRIES_ENV = "AIROUTER_ROUTING_STICKY_MAX_ENTRIES";

  /** alpha 的环境变量名。 */
  public static final String LATENCY_ALPHA_ENV = "AIROUTER_ROUTING_LATENCY_ALPHA";

  /** 失败惩罚值的环境变量名。 */
  public static final String LATENCY_PENALTY_ENV = "AIROUTER_ROUTING_LATENCY_FAILURE_PENALTY_MILLIS";

  /** 样本保留窗口的环境变量名。 */
  public static final String LATENCY_RETENTION_ENV =
      "AIROUTER_ROUTING_LATENCY_RETENTION_MILLIS";

  /** 虚拟节点数的环境变量名。 */
  public static final String VIRTUAL_NODES_ENV = "AIROUTER_ROUTING_VIRTUAL_NODES_PER_WEIGHT";

  /**
   * @throws IllegalArgumentException 策略名缺失或不在已登记清单里、粘滞表容量不是正整数、
   *     延迟样本那三个数不合法、或虚拟节点数不是正整数时抛出。
   *     后果是**启动失败**，并列出当时登记的策略名 —— 改对一个字不需要去读代码
   */
  public RoutingProperties {
    if (strategy == null || strategy.isBlank()) {
      throw new IllegalArgumentException("缺少策略名：请在环境变量 " + STRATEGY_ENV + " 里给出，"
          + "本阶段登记的策略是 " + SelectionStrategy.describeAll());
    }
    // 不认识的名字在这里就起不来。它同时也是「两处入口」的这一处：
    // 工厂创建时还会再问一次（防的是有人绕过绑定直接调工厂），两处用的是同一份清单 ——
    // 名字只写在 SelectionStrategy 里，没有第二个人在维护它。
    requireKnownStrategy(strategy);
    if (stickyMaxEntries <= 0) {
      throw new IllegalArgumentException("环境变量 " + STICKY_MAX_ENTRIES_ENV
          + " 必须是正整数，收到的是 " + stickyMaxEntries);
    }
    if (latency == null) {
      throw new IllegalArgumentException("缺少 " + LATENCY_ALPHA_ENV + " 那一段的配置："
          + "三个数必须一起出现在同一个来源里（列表元素不跨配置来源合并，何况是嵌套的一段）");
    }
    if (virtualNodesPerWeight <= 0) {
      throw new IllegalArgumentException("环境变量 " + VIRTUAL_NODES_ENV
          + " 必须是正整数，收到的是 " + virtualNodesPerWeight);
    }
  }

  /**
   * 解析后的策略，交给策略工厂取实例。
   *
   * <p>构造器已经判过一次不认识的名字，所以这里不会再问第二类问题。
   */
  public SelectionStrategy strategyKind() {
    return SelectionStrategy.of(strategy);
  }

  /**
   * 名字认不认识由 {@link SelectionStrategy} 判 —— 那边持有唯一的一份清单。
   * 这里补上的是它的出处：运维看的是环境变量，
   * 「该改哪个变量」这一句只有这一层说得出。
   */
  private static void requireKnownStrategy(String raw) {
    try {
      SelectionStrategy.of(raw);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException(
          "环境变量 " + STRATEGY_ENV + " 给的策略名不可用：" + unknown.getMessage(), unknown);
    }
  }

  /**
   * {@code latency} 策略那三个数：**EWMA 怎么平滑、一次失败算多慢、一个样本能留多久**。
   *
   * <p>它们单独作为一段，而不是摊平在 {@code airouter.routing} 的四个<｜hy_place▁holder▁no▁813｜>字段上：
   * 这三个数只有 {@code latency} 一个地方在用 ——
   * 摊平之后，改 alpha 的人和改策略的人在同一个列表里互相看不清对方改了什么。
   *
   * @param alpha EWMA 的平滑系数，取值 {@code (0, 1]}
   * @param failurePenaltyMillis 一次上游失败当作多少毫秒记进去
   * @param retentionMillis 一个实例没有新样本之后，它的 EWMA 还能活多久
   */
  public record Latency(double alpha, double failurePenaltyMillis, long retentionMillis) {

    /**
     * @throws IllegalArgumentException alpha 不在 {@code (0, 1]} 之内、
     *     惩罚值或保留窗口不是正数时抛出
     */
    public Latency {
      if (alpha <= 0d || alpha > 1d) {
        throw new IllegalArgumentException("环境变量 " + LATENCY_ALPHA_ENV
            + " 必须在 (0, 1] 之内，收到的是 " + alpha + "："
            + "为 0 的表永远不更新，大于 1 的表会在两个样本之间来回过冲");
      }
      if (failurePenaltyMillis <= 0d) {
        throw new IllegalArgumentException("环境变量 " + LATENCY_PENALTY_ENV
            + " 必须是正数，收到的是 " + failurePenaltyMillis
            + "：它是「一次失败记作多慢」，为 0 等于把一次失败记成一个完美的响应");
      }
      if (retentionMillis <= 0L) {
        throw new IllegalArgumentException("环境变量 " + LATENCY_RETENTION_ENV
            + " 必须是正数（毫秒），收到的是 " + retentionMillis
            + "：为 0 的表会在每一次记账时把刚学到的东西立刻忘掉");
      }
    }
  }
}
