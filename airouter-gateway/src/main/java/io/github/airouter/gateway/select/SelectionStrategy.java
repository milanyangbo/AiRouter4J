package io.github.airouter.gateway.select;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 第 ⑥ 步有哪几种策略，以及它们在配置里叫什么名字（ADR-0011 第 2 节 (a)）。
 *
 * <p>名字只在**这一处**定义。它有两个消费者：写错名字时的报错要列出全部取值，
 * 策略工厂要按它枚举一遍自检。两份用处共用一份清单，而不是各写一处 ——
 * 后者必然漂成「报错说有六种、实际只登记了两种」，而那正是最难归因的一种不一致。
 *
 * <p>**取值跟着实现走**：只有已经在 {@link SelectionStrategyFactory} 里登记了构造器的策略
 * 才出现在这个枚举里。六个名字因此是分两批出现的（本票两个、票 10 四个）——
 * 反过来先把六个名字都写出来，会让「配了某个策略却拿不到它」变成一种只能运行期偶遇的失败。
 *
 * <p>它与 {@code UpstreamShape} 是同一个形状：形态那边回答「一共有几种形态、名字怎么取、
 * 写错了由谁负责」，这里回答同一组问题在策略这一侧的答案。
 */
public enum SelectionStrategy {

  /** 均匀抽签。权重决定它在区间上占多长，抽签只在那个长度里取一个值。 */
  RANDOM("random"),

  /**
   * 光标在加权区间上一步步往前走。它可预测，所以装配期默认它而不是随机
   * （理由写在 {@link SelectionStrategyFactory} 上）：轮转序列能被**严格**断言，
   * 于是「权重真的生效」这条性质有了一条不靠概率的证明。
   */
  ROUND_ROBIN("round-robin");

  private final String id;

  SelectionStrategy(String id) {
    this.id = id;
  }

  /** 配置里写这个名字。小写，短横分词，与 {@code application.yml} 里的取值一致。 */
  public String id() {
    return id;
  }

  /**
   * 按配置里给出的名字取值。**不认的名字一律报错并列出已登记的取值**，不猜、也不给默认值。
   *
   * <p>写错一个字之后起不来，比写错之后静默跑在另一个策略上便宜得多：
   * 后者正是票 07 那三个「有配置、无消费者」字段的形状 —— 系统照跑、日志干净、只是流量走向不同。
   *
   * @param raw 配置里的原文
   * @return 对应的策略
   * @throws IllegalArgumentException 名字缺失或不认识时抛出，并列出全部已登记的名字
   */
  public static SelectionStrategy of(String raw) {
    String trimmed = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    for (SelectionStrategy strategy : values()) {
      if (strategy.id.equals(trimmed)) {
        return strategy;
      }
    }
    throw new IllegalArgumentException("不认识的策略名：收到的是 "
        + (trimmed.isEmpty() ? "(空)" : trimmed) + "；本阶段登记的是 " + describeAll());
  }

  /**
   * 全部已登记的名字，用「 / 」连起来。
   *
   * <p>它的消费者是出错信息与诊断，不是调用路径 —— 与 {@code UpstreamProviderFactory}
   * 报「没有对应的构造器」时列出已登记形态同一个理由：
   * 不知道自己配错了哪一个字是最贵的那一种失败。
   */
  public static String describeAll() {
    return Arrays.stream(values())
        .map(SelectionStrategy::id)
        .collect(Collectors.joining(" / "));
  }
}
