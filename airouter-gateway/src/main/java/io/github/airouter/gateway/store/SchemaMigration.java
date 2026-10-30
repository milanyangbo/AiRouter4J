package io.github.airouter.gateway.store;

import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * schema 的**唯一变更路径**（ADR-0017 第 4 节）。
 *
 * <p>没有 {@code ddl-auto}，没有手写建表脚本，也没有「先手动加一列、回头再补迁移」。
 * 迁移文件按 {@code V<n>__<语义>.sql}、两个方言同名成对，已落地的版本不可再改
 * （Flyway 按校验和判定，改了就是红）。
 *
 * <p>为什么由这里触发而不是让 Spring Boot 的 Flyway 自动配置去跑：
 * 自动配置的迁移跑在数据源就绪之后、且**没有「库不在」这一档** ——
 * 库不可达时它会让整个上下文起不来，而 ADR-0017 第 7 节要求的恰恰是
 * 「DB 不在 → 内存档运行，启动时不失败」。
 * 触发点搬到 {@link StoreAvailability} 之后，这一档才有可能存在。
 * 迁移动作本身仍然百分之百是 Flyway 的，这里只决定「跑不跑、跑哪一套」。
 *
 * <p>构造即执行：它是启动期的一次性动作，不是一个给别人调用的服务。
 * 留一个 {@link #applied()} 是为了让「迁移到底跑了没有」可以被断言 ——
 * 「没跑」和「跑了但没建表」是两种不同的故障，混在一起只能靠人去猜。
 */
final class SchemaMigration {

  private final boolean applied;

  /**
   * @param dataSource 目标库
   * @param availability 启动期的可用性探测结果。**不可用时不迁移**，也不抛异常
   * @param jdbcUrl 数据库地址，用来决定跑哪一套迁移
   */
  SchemaMigration(DataSource dataSource, StoreAvailability availability, String jdbcUrl) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(availability, "availability");
    if (availability.available()) {
      Flyway.configure()
          .dataSource(dataSource)
          .locations(StoreDialect.of(jdbcUrl).migrationLocation())
          .load()
          .migrate();
      this.applied = true;
    } else {
      this.applied = false;
    }
  }

  /** 迁移是否真的执行过。库不在时是 false。 */
  boolean applied() {
    return applied;
  }
}
