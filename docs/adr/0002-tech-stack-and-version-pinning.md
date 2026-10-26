# ADR-0002：技术栈与版本锁定

- 状态：已接受
- 日期：2026-10-26
- 相关：[0001](./0001-scope-target-state-and-sequence.md) 第 4 节（非目标）

## 1. 决定

| 层 | 选择 | 版本 |
|---|---|---|
| 语言 | Java | **21** |
| 框架 | Spring Boot | **4.1.2** |
| Agent | Spring AI | **2.0.1** |
| JSON | Jackson | **3**（随 Boot 4，不单独指定） |
| 构建 | Maven（Wrapper） | **3.9.16** |
| 响应式 | Reactor（仅 gateway / app） | 随 Boot |

**版本锁定的理由不是「新就是好」，而是这个组合是唯一同时满足三条的**：Boot 4 带来 Jackson 3；
Spring AI 2.0.1 是与 Boot 4 对齐的那条线；Java 21 是 Boot 4 的底线。
中间任何一项降级都会把另外两项一起拖回旧世界。

## 2. 新阶段引入的组件选型

目标态（九类能力面）比骨架阶段多了存储、缓存、分布式协调与观测：

| 需要 | 选择 | 不选择 | 理由 |
|---|---|---|---|
| 关系存储 | **JDBC 直写**（`JdbcClient`） | JPA / Hibernate | 懒加载与 flush 时机是**流式路径上的隐形阻塞点**：一次不经意的关系遍历会在事件循环线程上触发查询。网关没有复杂对象图，ORM 换不来等价收益 |
| Schema 迁移 | **Flyway** | Liquibase / `ddl-auto` | 见 [0017](./0017-persistence-migrations-and-multi-replica.md) 第 3 节 |
| 默认数据库 | **H2** | — | 无依赖起步，`local` 档位不需要任何外部服务 |
| 生产数据库 | **PostgreSQL**（pgvector 预留） | MySQL | `DATABASE_URL` 指向即切换；JSONB 与窗口函数对调用历史查询有用 |
| Redis 客户端 | **Lettuce**（Boot 默认） | Jedis / Redisson | Redisson 的分布式锁很方便，但它把一整套对象语义一起带进来；我们只需要一段 Lua |
| 本地缓存 | **Caffeine** | 自研 LRU | legacy 自研了一个 `LRUCache`，不需要 |
| 指标 | **Micrometer + Prometheus registry** | 自研计数器 | — |
| 链路追踪 | **OpenTelemetry**（OTLP 导出） | 自研 | legacy 有 94 个自研 tracing 类，**不搬** |
| 加密 | **JDK 原生 AES-256-GCM** | BouncyCastle | JDK 自带，少一个依赖 |
| 校验 | Jakarta Validation | 手写 if | — |

## 3. 依赖纪律

- **`airouter-core` 零框架依赖**，由 Maven enforcer 判红（出现 `org.springframework*` / Jackson / Reactor 即失败）
- **新增依赖要写它的「缺席测试」**：依赖不在时的行为必须是被断言过的那一种，不是被 `catch` 吞掉（[0008](./0008-verification-and-quality-gates.md) 第 4 节）
- 不引入「为了将来可能用到」的依赖

## 4. 构建状态

根 `pom.xml` 与四个模块的 `pom.xml` 已在阶段 0 随源码一并删除（`10c42a1`）。
**实施阶段 0 的第一件事是按 [0003](./0003-module-structure-and-dependency-direction.md) 重建它们**，
包含 enforcer（版本、JDK、重复上界、插件版本、禁止抑制文件）、checkstyle、spotbugs 与 surefire 的分组配置。

## 5. 后果

- 升级 Boot 小版本前先跑契约测试（`contract/` 三目录）—— 序列化行为变了，最先红的是它们
- 任何一处想引 JPA、Redisson 或自研 tracing，都要先推翻本文件
