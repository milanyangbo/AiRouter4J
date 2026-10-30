package io.github.airouter.gateway.store;

import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储这一层的**两个方言各跑一遍**。
 *
 * <p>为什么不是「在 H2 上跑一遍就算过」：默认档位跑的是 H2，
 * 于是只在 PG 上验过的 SQL 不算数（ADR-0017 第 1 节）——
 * 反过来，只验 H2 会让 PG 那一套迁移与 mapper 永远没人执行过。
 * 两条断言用的是**同一套动作**（迁移 → 写入 → 读回 → 再迁移一次），
 * 差别只有地址，因此「两个方言行为不一致」会表现为两条里恰好一条红。
 *
 * <p>接缝取的是**真实上下文**：数据源、MyBatis、mapper 与迁移都是真的，
 * 只有库本身是可替换的 —— 这里没有一个 mock。理由是这一层要证明的恰恰是
 * 「SQL、XML 与迁移对得上」，替身一上场，要证的这件事就没了。
 *
 * <p>不起 Web 服务器：存储不在请求路径上，
 * 端到端那一层（HTTP 入口）是别处的事（docs/agents/testing.md 的接缝纪律）。
 */
@Tag("integration")
@Testcontainers
class ConfigSnapshotStoreTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  /**
   * PG 用容器而不是本机常驻的那个实例：测试要能在一台没有起数据库的机器上照样跑出结论，
   * 「本机有 PG」是一个环境巧合，不是这条断言的前提。
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final Instant CREATED_AT = Instant.parse("2026-10-30T00:00:00Z");

  @Test
  @DisplayName("H2：迁移起来之后写入的那一版能被读回，再迁移一次不改数据")
  void snapshotsRoundTripOnH2() {
    String url = "jdbc:h2:mem:snapshot-h2-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    storeContext(url, "sa", "").run(context -> {
      ConfigSnapshotStore store = context.getBean(ConfigSnapshotStore.class);
      ConfigSnapshot written = new ConfigSnapshot(1L, "{\"services\":{}}", CREATED_AT, "startup");

      store.save(written).block(TIMEOUT);
      assertThat(store.latest().block(TIMEOUT)).isEqualTo(written);

      // 迁移是幂等的：重复执行不报错、不改数据（ADR-0017 第 9 节）。
      // 第二次由这里直接触发，而不是靠重启上下文 —— 后者验的是「新库能建」，
      // 验不出「已建过的库再跑一次会怎样」。
      new SchemaMigration(context.getBean(DataSource.class),
          context.getBean(StoreAvailability.class), url);
      assertThat(store.latest().block(TIMEOUT)).isEqualTo(written);
    });
  }

  @Test
  @DisplayName("PostgreSQL：同一套迁移与同一条 SQL 在 PG 上也成立")
  void snapshotsRoundTripOnPostgresql() {
    storeContext(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).run(context -> {
      ConfigSnapshotStore store = context.getBean(ConfigSnapshotStore.class);
      ConfigSnapshot written = new ConfigSnapshot(1L, "{\"services\":{}}", CREATED_AT, "startup");

      store.save(written).block(TIMEOUT);
      assertThat(store.latest().block(TIMEOUT)).isEqualTo(written);

      new SchemaMigration(context.getBean(DataSource.class),
          context.getBean(StoreAvailability.class), POSTGRES.getJdbcUrl());
      assertThat(store.latest().block(TIMEOUT)).isEqualTo(written);
    });
  }

  /**
   * 「DB 不在」是一种**被定义过的档位**，不是一种故障（ADR-0017 第 7 节）。
   *
   * <p>断言的是三件事，缺一件这条就不是在断言而是在「没崩就算过」：
   * 上下文**起得来**（内存档运行）、迁移**没有跑**（不是跑了然后失败）、
   * 读写**给出「没有那一版」**（而不是抛连接异常 / NPE）。
   */
  @Test
  @DisplayName("库不可达时：应用起得来，读给出「没有那一版」，而不是抛异常")
  void degradesToNoSnapshotWhenTheDatabaseIsAbsent() {
    String url = "jdbc:postgresql://127.0.0.1:1/absent";

    storeContext(url, "sa", "").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(StoreAvailability.class).available()).isFalse();
      assertThat(context.getBean(SchemaMigration.class).applied()).isFalse();
      assertThat(context.getBean(ConfigSnapshotStore.class).latest().block(TIMEOUT)).isNull();
      // 写也是同一档：完成而不落库。抛异常的话「内存档运行」就是一句空话。
      context.getBean(ConfigSnapshotStore.class)
          .save(new ConfigSnapshot(1L, "{}", CREATED_AT, "startup"))
          .block(TIMEOUT);
    });
  }

  /**
   * 只有数据源、MyBatis 与 store 自己的装配 —— 没有 web、没有网关那一整套。
   *
   * <p>刻意**不用** {@code @SpringBootTest}：gateway 没有启动类，
   * 而为了跑存储这一层去装一个启动类，等于把「谁装配谁」这件事改一遍。
   */
  private static ApplicationContextRunner storeContext(String url, String username,
      String password) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
            MybatisAutoConfiguration.class))
        .withUserConfiguration(StoreConfiguration.class)
        .withPropertyValues(
            "spring.datasource.url=" + url,
            "spring.datasource.username=" + username,
            "spring.datasource.password=" + password,
            // 连不上要**快点失败**，而不是让「库不在」这一档的测试等满默认那 30 秒。
            "spring.datasource.hikari.connection-timeout=1000",
            "spring.datasource.hikari.initialization-fail-timeout=-1");
  }
}
