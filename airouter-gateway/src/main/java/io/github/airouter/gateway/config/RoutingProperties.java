package io.github.airouter.gateway.config;

import io.github.airouter.gateway.select.SelectionStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 路由这一级的配置：**第 ⑥ 步用哪个策略**、**第 ⑤ 步粘不粘**。
 *
 * <p>为什么由配置决定而不是写在装配代码里：「用哪个策略」是运行时该能回答的问题，
 * 写在代码里它就变成一件要读一行 Java 才能知道的事，改一个集群的流量走向要重新发一次版。
 *
 * <p>三个值都**有默认值**（轮询 / 关 / 4096），且默认值只写在 {@code application.yml}
 * 那一处占位符里。不给默认值的后果是每次起进程都得先把这三样凑齐，
 * 而「粘滞」这种开关显然不该是必填项。但**一旦给了值就要合法** ——
 * 写错策略名必须起不来，而不是静默退化成一个没点的策略
 * （票 07 那三个「有配置、无消费者」的字段就是那个形状）。
 *
 * @param strategy 第 ⑥ 步的策略名；可选取值见 {@link SelectionStrategy#describeAll()}
 * @param stickyEnabled 第 ⑤ 步的开关。**开了之后每一次调用都必须能被认出来**：
 *                      认不出的调用会被粘滞那一层拒掉，而不是退化成每次换一个实例
 * @param stickyMaxEntries 粘滞表的容量上限。它是「最多记住多少个调用方」，
 *                         不是「最多允许多少并发」 —— 超了挤掉最久没来的那一个
 */
@ConfigurationProperties(prefix = RoutingProperties.PREFIX)
public record RoutingProperties(String strategy, boolean stickyEnabled, int stickyMaxEntries) {

  /** 属性前缀。写在常量里，让 yml、测试与出错信息引用的是同一个字符串。 */
  public static final String PREFIX = "airouter.routing";

  /** strategy 的环境变量名。 */
  public static final String STRATEGY_ENV = "AIROUTER_ROUTING_STRATEGY";

  /** sticky-enabled 的环境变量名。 */
  public static final String STICKY_ENABLED_ENV = "AIROUTER_ROUTING_STICKY_ENABLED";

  /** sticky-max-entries 的环境变量名。 */
  public static final String STICKY_MAX_ENTRIES_ENV = "AIROUTER_ROUTING_STICKY_MAX_ENTRIES";

  /**
   * @throws IllegalArgumentException 策略名缺失或不在已登记清单里、或粘滞表容量不是正整数时抛出。
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
}
