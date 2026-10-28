package io.github.airouter.gateway.select;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * **策略工厂**：策略 → 构造器的登记处。
 *
 * <p>它把「这次用哪个策略」从一段条件判断变成一次查表。查表本身没什么了不起，
 * 了不起的是这条路上只有一处需要改动：登记一行。
 * 新增策略既不动选择链，也不动注册表与 {@code config} 的绑定 ——
 * 那正是 ADR-0011 第 6 节说的「新增策略 = 加一个纯函数类 + 在策略工厂登记」。
 *
 * <p>登记是一次性的，在构造时完成并**立刻自检**：漏登记会让进程起不来，
 * 而不是等到某个配置写中那一种策略时才炸。后者的代价极高 ——
 * 它在测试里几乎撞不到，因为测试用的总是已经登记的那几个。
 *
 * <p>这里不像 {@code UpstreamProviderFactory} 那样用 {@code Map.copyOf}：
 * 那份复本不保证遍历顺序，于是出错信息里「已登记的是哪些」每次顺序都可能不同。
 * 名字至少要按登记顺序稳定出现，运维才能用它对着配置改。
 */
public final class SelectionStrategyFactory {

  /** 策略 → 构造器。缺一个就起不来，见 {@link #SelectionStrategyFactory()}。 */
  private final Map<SelectionStrategy, Supplier<InstanceSelector>> constructors;

  /**
   * @throws IllegalStateException 有策略没有登记构造器时抛出。
   *     宁肯起不来，也不要让人配到一种策略之后才发现它根本不存在
   */
  public SelectionStrategyFactory() {
    Map<SelectionStrategy, Supplier<InstanceSelector>> registered =
        new LinkedHashMap<>();
    registered.put(SelectionStrategy.RANDOM, RandomSelector::usingSharedRandom);
    registered.put(SelectionStrategy.ROUND_ROBIN, RoundRobinSelector::new);
    for (SelectionStrategy strategy : SelectionStrategy.values()) {
      if (!registered.containsKey(strategy)) {
        throw new IllegalStateException("策略 " + strategy.id() + " 没有登记构造器：请在本类补一行");
      }
    }
    this.constructors = Collections.unmodifiableMap(registered);
  }

  /**
   * 按名字造一个策略实例。
   *
   * @param strategy 策略名，它已经过 {@link SelectionStrategy#of} 那一道校验
   * @return 第 ⑥ 步的那一个策略
   */
  public InstanceSelector create(SelectionStrategy strategy) {
    Objects.requireNonNull(strategy, "strategy");
    Supplier<InstanceSelector> constructor = constructors.get(strategy);
    if (constructor == null) {
      // 构造时的自检已经放过这一关，能走到这里说明有人绕过了工厂。
      // 出错信息仍然指名策略并列出已登记的：让它退化成一个不带名字的空指针，最难排查。
      throw new IllegalStateException("策略 " + strategy.id() + " 没有对应的构造器；"
          + "已登记的是 " + describeSupportedStrategies());
    }
    return constructor.get();
  }

  /**
   * 已登记的策略名，按登记顺序用「 / 」连起来。
   *
   * <p>它回答「**现在**有几种策略可用」：票 10 把四个策略登记进来之后，
   * 这一句话自己会说出来，不需要有人记得回来改出错信息。
   */
  public String describeSupportedStrategies() {
    return constructors.keySet().stream()
        .map(SelectionStrategy::id)
        .collect(Collectors.joining(" / "));
  }
}
