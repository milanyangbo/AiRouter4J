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
  /**
   * 光标在加权区间上一步步往前走。它可预测，所以装配期默认它而不是随机
   * （理由写在 {@link SelectionStrategyFactory} 上）：轮转序列能被**严格**断言，
   * 于是「权重真的生效」这条性质有了一条不靠概率的证明。
   */
  ROUND_ROBIN("round-robin"),

  /**
   * 挑**当前压着的调用最少**的那一个（{@link LeastConnectionsSelector}）。
   *
   * <p>它需要 {@code transport} 的在途计数，而这个数是**装配递进来的一个查表动作**，
   * 不是让它自己去问：整个 {@code select} 包是纯函数（ADR-0011 第 2 节 (e)）。
   * 查不到某个候选的数时它报错，而不是把「不知道」当作 0 ——
   * 那会让本策略静默退化成随机。
   */
  LEAST_CONNECTIONS("least-connections"),

  /**
   * 按调用方标识哈希：**同一个调用方落在同一个实例上**（{@link IpHashSelector}）。
   *
   * <p>它共用第 ④ 步的区间，不自建一套加权 ——
   * 于是「权重在这个策略里也是真的」是一条能被断言的性质：
   * 3:1 时足够多的调用方里约有 3/4 落在重的那一个上。
   */
  IP_HASH("ip-hash"),

  /**
   * 键环 + 虚拟节点：**同一个调用方落在同一个实例上，且实例的增减只搬动它必须搬动的那部分键**
   * （{@link ConsistentHashSelector}）。
   *
   * <p>它是六个策略里唯一**把加权内建在自己里面**的那个：虚拟节点数与权重成正比，
   * 那些虚拟节点本身就已经是加过权的样子，所以它**不再叠加**第 ④ 步的区间。
   * 「换任意策略权重行为一致」这条验收因此只对另外五个成立 ——
   * 它在本策略里的等价性质改由「键比例 ≈ 权重占比」承担。
   */
  CONSISTENT_HASH("consistent-hash"),

  /**
   * 按延迟反比加权：每实例 EWMA，{@code ewma = alpha * 样本 + (1 - alpha) * ewma}，
   * 有效权重 = 声明权重 / {@code (1 + ewma)}（{@link LatencySelector}）。
   *
   * <p>样本由 {@code observe} 从 {@code Outcome} 养出来：成功用实际延迟、**失败用惩罚值**，
   * 于是高失败率的实例被自然降权；进到这里的时候它是**一个查表动作**。
   * 「降权」说的是**份额下降**，不是不再选中它 —— 一次也拿不到流量的实例
   * 没有机会证明自己恢复了，而 ADR-0011 第 4 节要的正是「概率下降」。
   *
   * <p>还没有样本的实例与**已知最快的那一个同档**：不优于任何被量过的实例
   * （那等于宣布「没量过 = 最快」），也不被当成最差的一档（那样它永远得不到第一次抽样）。
   * 两处不一致过一次，裁决写回了 ADR-0011 第 2 节 (d)。
   */
  LATENCY("latency");

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
