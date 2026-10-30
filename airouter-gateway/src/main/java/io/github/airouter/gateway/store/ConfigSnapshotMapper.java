package io.github.airouter.gateway.store;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 配置快照的 SQL。**包私有**：mapper 越出 {@code store} 包就等于把阻塞 API 放上事件循环
 * （ADR-0003 第 3 节、结构规则 R10）。
 *
 * <p>SQL 一条都不写在这里（ADR-0017 第 2 节纪律 1）：它们全在
 * {@code ConfigSnapshotMapper.xml} 里，与 Flyway 的迁移脚本放在一起评审 ——
 * 注解里的 SQL 既逃出评审范围，也逃出「与迁移同评审」这层保护。
 * 这个接口因此只有**签名**：它存在的意义是让 SQL 有一个可被注入的名字。
 *
 * <p>没有懒加载、没有嵌套 {@code select}（纪律 3）：每张表对应扁平的一条语句，
 * 「一次查询跑了几条 SQL」等于数这里的方法被调用了几次。
 */
@Mapper
interface ConfigSnapshotMapper {

  /**
   * 写入一版快照。
   *
   * @return 影响的行数
   */
  int insert(@Param("version") long version,
      @Param("content") String content,
      @Param("createdAt") Instant createdAt,
      @Param("actor") String actor);

  /**
   * 当前生效的那一版 —— 版本号最大的那一版。
   *
   * @return 快照；**一张表都没有时返回 null**。「没有历史」与「有一个空的快照」不是一回事
   */
  ConfigSnapshot selectLatest();
}
