# ADR-0017：持久化、迁移与多副本

- 状态：已接受
- 日期：2026-10-26
- 相关：[0002](./0002-tech-stack-and-version-pinning.md)（JDBC 不引 JPA）、[0010](./0010-configuration-model-and-runtime-registry.md)（配置版本）

能力面：**第 8 类（持久化与多副本）**

## 1. 数据库：H2 默认，PostgreSQL 按 `DATABASE_URL`

- 默认 **H2**：`local` 档位不需要任何外部服务就能起来
- `DATABASE_URL` 指向 PostgreSQL 时用 **PG**（pgvector 预留，为将来的向量能力）
- **同一套迁移双方言**（legacy 已有 `db/migration/h2/` 与 `db/migration/postgres/` 各 4 个版本，结构可搬）
- 只在 PG 上验过的 SQL **不算数** —— 默认档位跑的是 H2

## 2. 只有一套 store

legacy 有 **4 套并存的 store / 版本服务**：同一件事四种实现，不是抽象，是失控。**不搬。**

新版：一个 `store` 包，一个接口一组实现（H2/PG 由方言差异消化，不是由不同实现消化）。

## 3. Flyway 是 schema 的唯一变更路径

- 没有 `ddl-auto`，没有手写建表脚本，没有「先手动加一列、回头再补迁移」
- 迁移文件按 `V<n>__<语义>.sql`，H2 与 PG **同名成对**（legacy 的 V1–V4 就是这个结构）
- 每次 schema 变更都走一次新的版本文件，不修改已落地的版本

| 需要的表（由消费方倒推，不由 legacy 的 20 张表倒推） | 消费方 |
|---|---|
| 实例与服务配置快照 | [0010](./0010-configuration-model-and-runtime-registry.md) |
| 配置版本与回滚 | [0010](./0010-configuration-model-and-runtime-registry.md) 第 6 节 |
| 规则定义 + 命中统计 | [0012](./0012-rule-engine-and-conditional-routing.md) |
| 厂商声明 | [0009](./0009-adapters-shape-factory-and-vendor-declaration.md) |
| 调用历史（含密文与脱敏配置） | [0015](./0015-call-history-and-record-governance.md) |
| 脱敏规则 | [0015](./0015-call-history-and-record-governance.md) |
| API Key（只存哈希）与角色 | [0018](./0018-authentication-api-key-and-rbac.md) |

## 4. Redis 承载三件事

1. **配置变更事件广播**：广播「变更事件 + 版本号」，**不是全量配置**
2. **跨副本共享计数**：限流（[0013](./0013-resilience-circuit-failover-ratelimit-cache.md)）与配额（[0014](./0014-quota-multi-window-ledger.md)）
3. **分布式锁**：定时任务（历史清理）、告警冷却

> legacy 的 `HttpConfigPushService` 把全量配置推给一个并不存在的端点，**不搬**。

## 5. 分布式锁

- 可重入，带 **TTL 与续期**
- **锁丢失时的行为要被断言**（是跳过这次执行，还是允许并发执行）—— 不能让「锁没了」变成未定义行为
- 定时任务（记录清理、告警冷却）是唯一允许用锁的场景

## 6. 依赖缺席是契约，不是兜底

DB 或 Redis 不在时，行为必须是**被断言过**的那一种（降级到哪一档、拒绝还是放行），
而不是 NPE 之后被某个 `catch` 吞掉。

**每一处新依赖都要有一条「它不在」的测试** —— 这是 [0001](./0001-scope-target-state-and-sequence.md) 第 2 节第三道关的具体形式。

| 依赖不在 | 默认行为 |
|---|---|
| DB 不在 | 内存档运行（配置与规则只读），历史不落库；启动时不失败 |
| Redis 不在 | 限流走本地窗口；配额按配置拒绝（默认）；多副本退化为独立副本 |

## 7. 裁决

| legacy 的东西 | 裁决 |
|---|---|
| `db/migration/{h2,postgres}/V1..V4` 双方言结构 | **搬**（表内容重写） |
| 4 套 store / 版本服务 | **不搬** |
| `HttpConfigPushService` | **不搬** |
| 分布式锁用于定时任务与告警冷却 | **搬**（改：补上锁丢失的行为定义） |

## 8. 验收

- H2 与 PG 上跑同一套迁移都能起来（两个档位各一条集成测试）
- 迁移是幂等的：重复执行不报错、不改数据
- DB 不在 / Redis 不在各有一条断言行为（不是「没崩就算过」）
- 两个副本同时跑定时任务，只有一个执行（锁生效）
