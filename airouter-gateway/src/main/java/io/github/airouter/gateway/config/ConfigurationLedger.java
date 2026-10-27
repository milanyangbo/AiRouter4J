package io.github.airouter.gateway.config;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * 配置的版本账本：**现在生效的是哪一版**，以及**能回滚到哪一版**（ADR-0010 第 6 节）。
 *
 * <p>它只记账，不换注册表 —— 换注册表是 {@link RegistryReloader} 的事。
 * 分开的理由是「版本号前进」与「运行时对象换掉」这两步之间隔着一次装配，
 * 而装配有可能失败：装配失败时版本号**不该**前进，否则账本会说某一版生效过，
 * 而实际上那一版从来没进过注册表。
 *
 * <p>内存里**只留最近若干版**：无限增长的变更史在长跑的进程上是一个缓慢的泄漏，
 * 而它的价值（回滚）不需要三个月前的那一版。落库之后（ADR-0017）这个上限由表来承担，
 * 那时这里保留的只是「最近几次」这一层。
 *
 * <p>多副本之间广播的是「变更事件 + 版本号」，不是全量配置；广播本身随阶段 13 落地，
 * 版本号在这里就已经是单调的，所以到那一天不需要回来改它。
 */
public final class ConfigurationLedger {

  /** 保留的最近版本数。更早的会被丢掉，也就不能再回滚到它们。 */
  public static final int RETAINED_VERSIONS = 16;

  private final int retained;

  /** 变更史，最早的在前、当前生效的在最后。 */
  private final Deque<ConfigurationSnapshot> history = new ArrayDeque<>();

  /**
   * @param initial 启动时那一版配置。它成为 v1
   */
  public ConfigurationLedger(ServicesProperties initial) {
    this(initial, RETAINED_VERSIONS);
  }

  /**
   * @param initial 启动时那一版配置
   * @param retained 保留的版本数。至少 2：只有 1 版时「回滚」没有任何可回的地方
   * @throws IllegalArgumentException 保留数小于 2，或初始配置为空时抛出
   */
  public ConfigurationLedger(ServicesProperties initial, int retained) {
    if (retained < 2) {
      throw new IllegalArgumentException(
          "至少要保留 2 版，否则回滚没有任何可回的地方；收到的是 " + retained);
    }
    if (initial == null) {
      throw new IllegalArgumentException("账本必须从一版配置开始：没有当前生效的那一版，回滚无从谈起");
    }
    this.retained = retained;
    history.add(new ConfigurationSnapshot(1L, Instant.now(), initial));
  }

  /** 现在生效的那一版。 */
  public synchronized ConfigurationSnapshot current() {
    return history.getLast();
  }

  /**
   * 现在生效的**上一版**。
   *
   * @return 有的那一版；当前就是第一版时为 {@link Optional#empty()} ——
   *     「没有可回滚的版本」由调用方去决定是拒绝还是忽略，因为只有它知道这一次变更是谁发起的
   */
  public synchronized Optional<ConfigurationSnapshot> previous() {
    if (history.size() < 2) {
      return Optional.empty();
    }
    List<ConfigurationSnapshot> versions = List.copyOf(history);
    return Optional.of(versions.get(versions.size() - 2));
  }

  /**
   * 记下新的一版。
   *
   * <p>它**不检查内容是不是真的变了**：一次内容相同的变更仍然产生新版本号。
   * 「变更了几次」与「内容变了几回」是两个问题，而前者才是副本之间对账时需要的那个。
   *
   * @param next 新的一版配置，进来时已经过校验
   * @return 新产生的快照，版本号是上一版加一
   */
  public synchronized ConfigurationSnapshot apply(ServicesProperties next) {
    ConfigurationSnapshot snapshot =
        new ConfigurationSnapshot(current().version() + 1, Instant.now(), next);
    history.add(snapshot);
    while (history.size() > retained) {
      history.removeFirst();
    }
    return snapshot;
  }

  /** 还留着的版本号，从早到晚。它回答「能回滚到哪一版」。 */
  public synchronized List<Long> versions() {
    List<Long> versions = new ArrayList<>();
    for (ConfigurationSnapshot snapshot : history) {
      versions.add(snapshot.version());
    }
    return List.copyOf(versions);
  }

  /**
   * 取回某一版。
   *
   * @param version 版本号
   * @return 还留着的那一版；已经被丢掉或从来没有过就是 {@link Optional#empty()}
   */
  public synchronized Optional<ConfigurationSnapshot> version(long version) {
    return history.stream().filter(snapshot -> snapshot.version() == version).findFirst();
  }
}
