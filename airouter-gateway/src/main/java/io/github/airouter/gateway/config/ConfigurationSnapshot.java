package io.github.airouter.gateway.config;

import java.time.Instant;

/**
 * 一次配置变更的**不可变**快照（ADR-0010 第 6 节）。
 *
 * <p>它回答两个问题：现在生效的是哪一版、能回滚到哪一版。
 * 前者是 {@link #version()}，后者是账本里还留着的那些版本。
 *
 * <p>版本号**只增不减**，包括回滚：回滚产生的是新的一版，其内容取自要回到的那一版。
 * 另一条路（让版本号回退）会让两个副本之间无法用「版本号大的那一版新」来判断谁落后 ——
 * 而多副本之间广播的只是「变更事件 + 版本号」，不是全量配置。
 * 单调的版本号还有一个副作用是它本身就是一条变更史：v7 的内容与 v3 相同，
 * 说明第 7 次变更是一次回滚。
 *
 * <p>快照里存的是**配置**而不是运行时对象：运行时对象由 {@link InstanceAssembler} 按需装配，
 * 而且它带的是连接与超时这类活的东西，把它们塞进历史里只会让「回滚到哪一版」变成
 * 「回滚到哪些连接」。
 *
 * <p>已知代价：配置里带着实例自己的凭证，于是历史里也就留着凭证的旧值 ——
 * 凭证轮换之后，旧值在内存里还会存在一段时间（最多 {@link ConfigurationLedger#RETAINED_VERSIONS} 版）。
 * 持久化（ADR-0017）落地时这一条要一并处理：库里存的版本不该带着凭证。
 *
 * @param version 版本号，从 1 开始
 * @param appliedAt 这一版开始生效的时刻
 * @param services 这一版的配置。不可变，进来之后谁都改不动它
 */
public record ConfigurationSnapshot(long version, Instant appliedAt, ServicesProperties services) {

  /**
   * @throws IllegalArgumentException 版本号不是正数、时刻或配置为空时抛出
   */
  public ConfigurationSnapshot {
    if (version < 1) {
      throw new IllegalArgumentException("版本号从 1 开始，收到的是 " + version);
    }
    if (appliedAt == null) {
      throw new IllegalArgumentException("快照必须带上它开始生效的时刻");
    }
    if (services == null) {
      throw new IllegalArgumentException("快照必须带上它那一版的配置");
    }
  }
}
