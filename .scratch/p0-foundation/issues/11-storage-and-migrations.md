# 11: 存储与迁移

> 来源：[ADR-0011](../../docs/adr/0011-feature-roadmap-and-migration-boundary.md) 决策 2、3

**What to build:** 持久化一次到位——默认 H2；`DATABASE_URL` 指向 PostgreSQL 时用 PostgreSQL；
schema 由 Flyway 版本化管理，两种方言各一份迁移。同时定义 store 抽象，
并让「存储缺席」成为一条被测试钉住的契约而不是兜底。

为什么放在 Phase A 第一张：后面每一张票（配置版本、调用历史、配额账本、多副本共享计数）
都要往这里写东西。先做存储不是因为它简单，而是因为晚做会导致**所有写入方回头改一遍**。

**Blocked by:** —

**Status:** todo（基线 tag `baseline-pre-11`）

- [ ] 默认 H2（本地 / 开发档位零配置起）；`DATABASE_URL` 指向 `postgres://` 或 `jdbc:postgresql://`
      时切到 PostgreSQL，两种方言由 `DATABASE_URL` 的 scheme 判定并在启动日志里说明用了哪一种
- [ ] Flyway 迁移：`src/main/resources/db/migration/{h2,postgres}` 各一份，版本编号对齐；
      迁移**幂等**，重复执行不报错也不改数据
- [ ] store 抽象只有一个主实现（JPA），不照搬 legacy 的 4 套 store + 4 层 tier 组合；
      接口里写明「**空集合是合法输入**」
- [ ] 存储不可用（库起不来 / 未配置）时回落到内存 store，且**启动日志明确记录降级生效**，
      不允许静默——静默降级会让「配置改了没生效」变成一个查不出来的问题
- [ ] 启动期校验：方言、迁移版本与当前 schema 一致；不一致即启动失败并指出该跑哪条迁移
- [ ] 集成测试打**真实存储**（本机已有 PG 16 `:5432` 与 Redis 7 `:6379`），不用桩替代；
      H2 与 PG 两套都跑一遍同一组用例

## 实现要点（来自 legacy 的教训）

- **空表必须能存。** legacy 的 `JpaStoreManager.saveConfig` 有 `config.isEmpty()` 早退，
  而上层普遍是「读全量 → 改 → 写全量」，于是**删掉最后一项永远删不掉却返回成功**
  （legacy #446）。契约里写明空集合合法，并在测试里真的删到最后一个条目。
- **集成测试不得 mock store。** 当年漏掉上面那个 bug 的原因正是把 `StoreManager` mock 了：
  mock 上没有那段早退，「saveConfig 被调用过」恒真。
- 迁移目录分方言，但**版本号必须同步**。两边不同步时，H2 上过的改动在 PG 上是没过的。
- 配置项只从环境变量取敏感值（与 `EndpointProperties` 同一纪律）：`DATABASE_URL` 可以带口令，
  但它不出现在任何受跟踪文件里。
