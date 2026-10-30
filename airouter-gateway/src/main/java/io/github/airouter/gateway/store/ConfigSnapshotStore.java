package io.github.airouter.gateway.store;

import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 配置快照的读写门面。**这个包对外唯一该被用到的东西。**
 *
 * <p>每个方法都过响应式边界：{@code Mono.fromCallable(...).subscribeOn(boundedElastic())}。
 * 这不是风格 —— MyBatis 是阻塞 API，直接调用就是把 JPA 的隐形阻塞换成 MyBatis 的显式阻塞，
 * 什么都没换来（ADR-0017 第 2 节纪律 6）。调度器取 {@code boundedElastic} 而不是
 * {@code parallel}：它的池按需增长且有上限，适合「偶尔一次、会等 IO」的形状，
 * 而库调用在网关里正是这个形状 —— 它不在流式热路径上（纪律 7）。
 *
 * <p>**库不在时返回空，不抛异常**：ADR-0017 第 7 节把「DB 不在」定义为一种档位
 * （内存档运行），不是一种故障。读不到快照于是等价于「没有历史版本」——
 * 这与「有一版但是空的」是同一件事的两种说法，回滚到「没有上一版」本来就是合法起点。
 * 判据来自 {@link StoreAvailability} 的**启动期探测**，不是每次调用 catch：
 * 后者会把 SQL 写错也一并吞成「没有那一版」。
 */
public final class ConfigSnapshotStore {

  private final ConfigSnapshotMapper mapper;

  private final StoreAvailability availability;

  private final Scheduler scheduler;

  public ConfigSnapshotStore(ConfigSnapshotMapper mapper, StoreAvailability availability) {
    this(mapper, availability, Schedulers.boundedElastic());
  }

  /**
   * @param mapper mapper 接口（包私有：它不许出这个包，R10）
   * @param availability 启动期的可用性探测结果
   * @param scheduler 阻塞调用跑在哪个调度器上。**注入而不是直接取**，
   *                  是因为「数据库调用必须离开事件循环」这件事本身值得被断言 ——
   *                  测试里换成同一个线程的调度器就能证明调用确实被丢出去了
   */
  public ConfigSnapshotStore(ConfigSnapshotMapper mapper, StoreAvailability availability,
      Scheduler scheduler) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.availability = Objects.requireNonNull(availability, "availability");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /**
   * 当前生效的那一版 —— 版本号最大的那一版。
   *
   * @return 快照；**没有历史版本时是空**。库不在时也走这一档（理由见类注释）
   */
  public Mono<ConfigSnapshot> latest() {
    if (!availability.available()) {
      return Mono.empty();
    }
    return Mono.fromCallable(mapper::selectLatest).subscribeOn(scheduler);
  }

  /**
   * 记下一版快照。
   *
   * @param snapshot 要记的那一版
   * @return 写完之后完成；库不在时**直接完成而不写**（内存档运行，不落库）
   */
  public Mono<Void> save(ConfigSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (!availability.available()) {
      return Mono.empty();
    }
    return Mono.fromCallable(() -> mapper.insert(snapshot.version(), snapshot.content(),
        snapshot.createdAt(), snapshot.actor()))
        .subscribeOn(scheduler)
        .then();
  }
}
