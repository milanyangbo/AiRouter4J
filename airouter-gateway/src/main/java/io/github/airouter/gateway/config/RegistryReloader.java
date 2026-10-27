package io.github.airouter.gateway.config;

import io.github.airouter.gateway.registry.ServiceView;
import java.util.Map;
import java.util.function.Consumer;

/**
 * **热重载**：换掉注册表里的对象，不重建 Spring 上下文（ADR-0010 第 5 节）。
 *
 * <p>重建上下文的代价是丢掉在途的流、熔断计数与延迟样本 —— 一次配置变更等于一次全站状态清零，
 * 那是重启，不是热重载。所以这里做的只有两步：按新配置装配出一组对象，再把它们整体换进注册表。
 * 上下文、Face、以及其它持有注册表的组件一个都不动。
 *
 * <p>**在途的请求不受影响**，而且这件事不需要额外代码：请求在开始时就从注册表里取走了它要用的
 * 那一个实例对象（{@code UpstreamProvider}），之后用的始终是它自己那一手拿到的东西。
 * 换掉注册表影响的是**下一次**取用 —— 这正是「在途请求继续用它开始时拿到的那一份，
 * 直到结束」的另一种说法。
 *
 * <p>三步的顺序是有意的：**先装配，再记账，最后换注册表**。
 * 装配（也是校验真正发生的地方）失败了，版本号不前进、注册表不动 ——
 * 一次失败的变更不留半截。反过来的顺序（先记账再装配）会在装配抛异常时留下
 * 「账本说 v5 生效过，而注册表里还是 v4 的东西」这样一处对不上的账。
 *
 * <p>换注册表之前，新配置已经通过了自己的构造校验（缺凭证、地址不合形态、
 * 默认服务不在清单里都在那里判红），所以能走到这里的都是**能起来**的那一版配置。
 */
public final class RegistryReloader {

  /** 把新的一版服务清单交出去的动作。它是注册表唯一的写入口（{@code registry::replace}）。 */
  private final Consumer<Map<String, ServiceView>> replacement;

  private final InstanceAssembler assembler;

  private final ConfigurationLedger ledger;

  /**
   * @param assembler 实例装配器。它记得上一回给出去的出网对象，于是只有该换的才换
   * @param ledger 版本账本
   * @param replacement 替换动作。它来自注册表（{@code registry::replace}）：
   *     这里要的是「把这一版交出去」这一个动作，不是注册表本身 ——
   *     热重载不读注册表，它需要的能力只有写那一个
   */
  public RegistryReloader(InstanceAssembler assembler, ConfigurationLedger ledger,
      Consumer<Map<String, ServiceView>> replacement) {
    this.assembler = assembler;
    this.ledger = ledger;
    this.replacement = replacement;
  }

  /**
   * 换到新的一版配置。
   *
   * @param next 新配置，进来时已经过它自己的校验
   * @return 这一版变更的快照，带版本号
   * @throws IllegalArgumentException 新配置装配不出来时抛出。此时**生效中的那一版不受影响**
   */
  public synchronized ConfigurationSnapshot reload(ServicesProperties next) {
    return apply(next);
  }

  /**
   * 回上一版。
   *
   * <p>它产生的是**新的一版**（版本号前进），内容取自上一版 —— 理由见
   * {@link ConfigurationSnapshot}：版本号必须单调，否则副本之间无法比较谁新。
   * 于是「回滚到 v3」在账本里表现为 v7，而 v7 的内容与 v3 相同。
   *
   * @return 回滚后生效的那一版快照
   * @throws IllegalStateException 没有更早的版本可回时抛出，并**指名当前版本号**
   */
  public synchronized ConfigurationSnapshot rollback() {
    ConfigurationSnapshot target = ledger.previous().orElseThrow(() -> new IllegalStateException(
        "现在生效的是 v" + ledger.current().version() + "，它是第一版：没有更早的版本可以回滚"));
    return apply(target.services());
  }

  /** 现在生效的那一版。 */
  public ConfigurationSnapshot current() {
    return ledger.current();
  }

  private ConfigurationSnapshot apply(ServicesProperties next) {
    Map<String, ServiceView> views = assembler.assemble(next);
    ConfigurationSnapshot snapshot = ledger.apply(next);
    replacement.accept(views);
    return snapshot;
  }
}
