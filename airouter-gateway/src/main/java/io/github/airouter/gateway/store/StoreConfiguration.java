package io.github.airouter.gateway.store;

import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储侧的装配。它落在这个包里而不是 {@code config} 里，是因为它要碰到 mapper ——
 * 而 mapper 不许出这个包（R10）。装配随被装配的东西走，规则才不用为它开一个例外。
 *
 * <p>三条 MyBatis 用法纪律在这里落地（ADR-0017 第 2 节）：
 * 二级缓存关掉（纪律 2）、本地缓存范围压到语句级（纪律 2）、
 * mapper 只在这个包里被扫描（纪律 5）。
 * 剩下那条「调用必须过响应式边界」在 {@link ConfigSnapshotStore} 里，
 * 并由结构规则 R11 判红。
 *
 * <p>二级缓存为什么必须关：WebFlux 下 {@code SqlSession} 的生命周期由 Spring 托管，
 * 不是由一次请求托管 —— 于是「缓存里这一份是哪个版本的数据」取决于线程调度，
 * 两个副本读到不同版本只是时间问题（纪律 2 的原话）。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan("io.github.airouter.gateway.store")
public final class StoreConfiguration {

  private StoreConfiguration() {
  }

  /**
   * MyBatis 的两条缓存纪律。
   *
   * <p>用 starter 给的 {@link ConfigurationCustomizer} 而不是自己造
   * {@code org.apache.ibatis.session.Configuration}：后者要顺手接管事务、类型别名与插件那一整套，
   * 而这些都不是存储这一层该重新决定的事。这里只改那两处，其余仍由 starter 决定。
   *
   * @return 只改缓存那两处的定制器
   */
  @Bean
  ConfigurationCustomizer mybatisCacheCustomizer() {
    return configuration -> {
      configuration.setCacheEnabled(false);
      configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    };
  }

  /**
   * 启动期探测一次「有没有库」。
   *
   * <p>它是其它存储 bean 的**前置判断**，不是一个可选项：
   * 迁移（{@link #schemaMigration}）与读写（{@link ConfigSnapshotStore}）都先看它，
   * 于是「库不在」这一档行为只有一处定义（ADR-0017 第 7 节）。
   *
   * @param dataSource 数据源
   * @return 探测结果
   */
  @Bean
  StoreAvailability storeAvailability(DataSource dataSource) {
    return new StoreAvailability(dataSource);
  }

  /**
   * schema 的唯一变更路径。
   *
   * <p>顺带说明为什么 {@code spring.flyway.enabled} 是关着的（见 application.yml）：
   * Boot 的自动迁移没有「库不在」这一档，库不可达时它会让上下文起不来，
   * 而这里要的恰恰是起得来。迁移动作本身仍然百分之百由 Flyway 执行。
   *
   * @param dataSource 目标库
   * @param availability 探测结果：不可用时**不迁移**，也不失败
   * @param jdbcUrl 数据库地址，决定跑哪一套迁移
   * @return 迁移的执行结果
   */
  @Bean
  SchemaMigration schemaMigration(DataSource dataSource, StoreAvailability availability,
      @Value("${spring.datasource.url}") String jdbcUrl) {
    return new SchemaMigration(dataSource, availability, jdbcUrl);
  }

  /**
   * 配置快照的读写门面。
   *
   * @param mapper 由 {@code @MapperScan} 在本包里扫到的 mapper
   * @param availability 探测结果：库不在时读写都走「没有那一版」这一档
   * @return 存储门面
   */
  @Bean
  ConfigSnapshotStore configSnapshotStore(ConfigSnapshotMapper mapper,
      StoreAvailability availability) {
    return new ConfigSnapshotStore(mapper, availability);
  }
}
