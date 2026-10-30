package io.github.airouter.gateway.store;

import java.util.Locale;

/**
 * 关系存储的**方言**：两个取值，各自对应一套迁移脚本。
 *
 * <p>为什么方言是一个枚举而不是一个字符串常量：它同时决定「用哪一套迁移」，
 * 而两套迁移必须**同名成对**（{@code db/migration/h2/} 与 {@code db/migration/postgres/}）。
 * 让它成为一个类型之后，「新增一个方言要补哪些东西」有唯一一处答案 ——
 * 漏掉一半迁移会在这里表现为编译错误，而不是表现为「PG 上迁移执行了、H2 上没有」。
 *
 * <p>判定的输入只有 JDBC URL，不读 {@code DataSource} 的元数据：
 * 后者要连一次库才知道，而「库不在」是本包必须能在启动期就答出来的一种状态
 * （ADR-0017 第 7 节）—— 先连库再决定用哪套迁移，等于把降级路径堵死。
 */
enum StoreDialect {

  /** 默认档位。{@code local} 不需要任何外部服务就能起来（ADR-0017 第 1 节）。 */
  H2("jdbc:h2:", "classpath:db/migration/h2"),

  /** 生产档位。pgvector 预留，为将来的向量能力。 */
  POSTGRESQL("jdbc:postgresql:", "classpath:db/migration/postgres");

  private final String urlPrefix;

  private final String migrationLocation;

  StoreDialect(String urlPrefix, String migrationLocation) {
    this.urlPrefix = urlPrefix;
    this.migrationLocation = migrationLocation;
  }

  /**
   * 按 JDBC URL 判定方言。
   *
   * @param jdbcUrl 形如 {@code jdbc:h2:mem:...} / {@code jdbc:postgresql://...}
   * @return 对应的方言
   * @throws IllegalArgumentException URL 不属于任何受支持的方言。
   *         这里**显式拒绝**而不是退回默认方言：默认档位是 H2，
   *         一个写错的 URL 退回 H2 会静悄悄地在内存库上建一套表，
   *         而「为什么库里没有数据」要查很久才发现启动时连的是另一个库 ——
   *         这与 ADR-0010 第 4 节「启动期判红，不留到运行期」是同一条纪律
   */
  static StoreDialect of(String jdbcUrl) {
    String normalized = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
    for (StoreDialect dialect : values()) {
      if (normalized.startsWith(dialect.urlPrefix)) {
        return dialect;
      }
    }
    throw new IllegalArgumentException("不受支持的数据库地址：" + jdbcUrl
        + "。目前只有 H2 与 PostgreSQL 两套迁移，加一套方言要同时补 db/migration/<方言> 目录");
  }

  /** 这套方言的迁移脚本在哪。返回值直接交给 Flyway 的 {@code locations}。 */
  String migrationLocation() {
    return migrationLocation;
  }
}
