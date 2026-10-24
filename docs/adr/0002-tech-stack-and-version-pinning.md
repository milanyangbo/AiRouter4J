# ADR-0002: 技术栈与版本锁定

- **状态**：已接受（第 2 版，2026-10-25 重写）
- **日期**：2026-10-25
- **相关**：[0001 定位与目标态](./0001-project-scope-and-lineage.md)、[0003 模块结构](./0003-module-structure-and-dependency-direction.md)、[0004 Spring AI 边界](./0004-spring-ai-boundary.md)、[0012 存储与多副本](./0012-storage-migrations-and-cluster.md)

## 背景

来源项目是 Java 17 + Spring Boot 3.5.5，在 Jackson 2 上，不依赖 Spring AI。

本项目要引入 Spring AI 2.x 做 Agent 层，这一步会把整个技术栈的版本一起拽上去。下面这条约束是实测出来的，不是推测：

从 Maven Central 拉下 `spring-ai-starter-model-openai-2.0.1.pom`，其中写死的依赖为：

```xml
<artifactId>spring-boot-starter-webclient</artifactId>  <version>4.1.1</version>
<artifactId>spring-boot-starter-restclient</artifactId>  <version>4.1.1</version>
```

**结论：Spring AI 2.0.1 与 Spring Boot 4.1.x 是绑定关系，不是推荐搭配。** 把 Boot 3.5 的 classpath
与一组 Boot 4.1 的 starter 混在一起，等于同时加载两套不兼容的自动配置 —— 这条路不存在。

于是选择是二选一，没有中间状态：**用 Spring AI 2.x → 必须 Boot 4.1 → 必须 Jackson 3**；
留住 Boot 3.5 → 不能用 Spring AI 2.x。本项目选择前者。

第二个约束来自目标态（[0001](./0001-project-scope-and-lineage.md)）：持久化、共享状态与可观测性
都要落地，而它们恰恰是来源项目复杂度失控的地方（4 套 store + 4 层 tier、160 个自研 tracing 类）。
选型必须让这些能力**长不出**那两种形态。

## 决策

### 1. 版本锁定（全部实测确认可解析）

| 组件 | 版本 | 备注 |
|---|---|---|
| Spring Boot（`spring-boot-starter-parent`） | **4.1.2** | 只在根 POM 出现一次 |
| Spring AI BOM | **2.0.1** | `<dependencyManagement>` import |
| Java | **21** | `maven.compiler.release` |
| ArchUnit | 1.5.1 | 仅 test scope，不进运行时 classpath |
| `openai-java`（官方客户端） | 4.91.0 | **仅 test scope**：让「响应能不能被标准客户端解析」由第三方判，而不是自己写断言证明自己合规 |
| `maven-checkstyle-plugin` / checkstyle | 3.6.0 / 10.21.2 | 配对版本 |
| `spotbugs-maven-plugin` | 4.8.6.6 | 4.9.3 实测 HTTP 404，不存在 |
| `maven-enforcer-plugin` | 3.6.1 | 绑定在 `validate`，JDK 不对时不必等到编译 |

版本统一写在根 POM 的 `<properties>`，子模块不重复声明 —— 任何版本调整都是单点修改。
唯一的已知边界：子模块在自己的 `<properties>` 里覆盖版本，Maven 内置规则判不红
（`requireManagedVersions` 来自 extra-enforcer-rules，其规则类缺无参构造器，与 enforcer 3.x 无法实例化），
该情形由 [0003](./0003-module-structure-and-dependency-direction.md) 的结构约束承担。

### 2. 为什么 pin 在 Spring AI 2.0.1 而不是更新版本

承载 Agentic 能力的社区包以其编译目标为准发版：`spring-ai-agent-utils:0.13.0`、
`spring-ai-session-bom:0.10.0` 都编译在 **2.0.1** 上，没有针对更晚版本重新发过。

`spring-ai.version` 是一个属性，改它只需动一处；但它是**协同变更而不是日常升级** ——
升级前必须确认社区包已跟进。

### 3. 为什么是 Java 21

**不是因为框架强制**（Spring Framework 7 与 Boot 4 的基线仍是 Java 17）。选 21 是为了两件有实际收益的东西：

- **虚拟线程**：网关的瓶颈是大量并发等待上游响应，虚拟线程对这种负载是直接收益
- **JSpecify 空安全**：网关处理的是外部不可信输入，编译期的可空性标注能挡掉一类空指针

这条要写清楚，因为「某某版本强制某某 JDK」是很容易口口相传但经不起核对的说法。

### 4. Jackson 3 全量接受，不做双 classpath

Boot 4.1 内置 Jackson 3（`tools.jackson`）。**不保留 Jackson 2**：双 classpath 会让序列化行为的
归属变得难以判断 —— 同一个对象走不同序列化器可能产出不同 JSON，而这类差异恰恰不会编译报错。
一次性跟随，用契约测试拦截行为漂移（[0008](./0008-verification-and-quality-gates.md)）。

### 5. 持久化用 Flyway + JDBC，**不引入 JPA / Hibernate**

| 选择 | 理由 |
|---|---|
| **Flyway** 管 schema（H2 与 PostgreSQL 各一份迁移） | 迁移是显式的、可审阅的、有版本号的；来源项目的表结构本身就是资产，值得搬（[0001](./0001-project-scope-and-lineage.md) 第 2 节） |
| **JDBC**（`JdbcClient` / Spring Data JDBC）做访问层 | 网关的持久化只有两类：少量配置表 + **append-only 的调用历史**。都不需要 ORM 的变更跟踪 |
| **不引入 JPA / Hibernate** | 脏检查、一级缓存、延迟加载、实体关系图在请求热路径上是负担，而且是隐式查询的来源 —— 你不知道一次 `save()` 会发几条 SQL。来源项目「4 套 store + 4 层 tier」的复杂度，ORM 是共谋者之一 |

代价是映射要自己写。这个代价是刻意承担的：网关的写入路径很短，手写映射的可读性优于
「实体关系正确但 SQL 不可预测」。只读的查询（配置加载、调用历史检索）用 `JdbcClient` 表达是直接的。

### 6. 共享状态用 Redis，客户端用 Lettuce（Boot 默认的 `spring-data-redis`）

限流与配额的共享计数、跨副本事件广播、分布式锁都落在 Redis。客户端不另选：
Boot 4.1 的 `spring-boot-starter-data-redis` 默认 Lettuce，其异步模型与 WebFlux 一致。

**Redis 必须可以缺席**，缺席时降级为本地语义（[0012](./0012-storage-migrations-and-cluster.md) 第 2 节）。
这不是兜底而是契约：降级路径本身是必测行为。

### 7. 可观测性用官方 starter，不引入自研 tracing

| 能力 | 选型 |
|---|---|
| 指标 | Micrometer + `micrometer-registry-prometheus` |
| 链路 | Micrometer Tracing + OpenTelemetry bridge（Boot 4 官方支持） |
| 日志 | 结构化日志（JSON encoder） |
| 告警 | **仓内不实现任何推送通道**，只出指标与日志，接入交给 Alertmanager / 日志平台（[0001](./0001-project-scope-and-lineage.md) 能力清单） |

来源项目的 160 个自研 tracing 类不搬。理由不是「自己写一定更差」，而是：官方 starter 已覆盖的部分，
自研的维护成本与覆盖面都不划算，且它会变成第二个「4 套 store」。

### 8. 不使用独立 A2A 包

`spring-ai-a2a-server:0.3.0` 的 POM 写死依赖 `spring-ai-client-chat:2.0.0-RC1`，未针对 GA 重新发布。
不引入；需要跨系统 Agent 互操作时走 `spring-ai-agent-utils` 自带的 A2A 支持或官方后续版本。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 网关留 Boot 3.5、只把 Spring AI 加进来 | Spring AI 2.0.1 直接依赖 Boot 4.1.1 的 starter，classpath 层面不成立 |
| Agent 层独立进程跑 Boot 4.1、网关留 3.5 | 会让网关长期停留在旧栈，而 Jackson 3 迁移迟早要做；两个 Boot 大版本长期并存让构建与依赖管理复杂化 |
| 同时保留 Jackson 2 与 Jackson 3 | 序列化行为归属不明，是最难排查的一类问题 |
| 引入 JPA / Hibernate 做持久化 | 热路径上的隐式查询与脏检查；且它是来源项目 store 复杂度的共谋者。网关的持久化不需要 ORM |
| 自研 tracing（搬来源项目的 160 个类） | 官方 starter 已覆盖；自研部分会变成第二个「4 套 store」 |
| 仓内实现告警推送（邮件 / Webhook / IM） | 与网关职责无关；一旦开了口子，通道会增长 |
| 用 Testcontainers 起存储依赖 | 本机已有真实的 Redis 7 与 PG 16 在跑；集成测试打真实实例而不是每轮拉镜像（[0008](./0008-verification-and-quality-gates.md) 第 3 节） |

## 后果

**正面**

- 单一版本栈，classpath 里没有版本夹带
- 虚拟线程对网关这种高并发等待型负载是直接收益
- 存储与可观测的选型把「4 套 store」「160 个 tracing 类」这两种形态从源头堵掉了

**负面（必须承担的代价）**

- **Jackson 2 → 3 是本次最大的迁移风险**：编译通过但 JSON 形态变了的那部分最危险。靠契约测试拦（[0008](./0008-verification-and-quality-gates.md)）
- 手写 JDBC 映射：每张表一个映射函数，比 JPA 实体啰嗦
- Spring AI 2.x 自身在演进（`spring-ai-session` 计划在 2.1 被吸收进本体），后续仍有协调成本
- 一次变更里同时包含「包名迁移 + Boot 4 API 变更 + Jackson 3 变更」，单次审阅负担较大 ——
  这也是 [0008](./0008-verification-and-quality-gates.md) 要求先补测试再动结构的直接原因

## 如何验证

- [ ] 根 POM 里不出现 `checkstyle.skip` / `spotbugs.skip` / `jacoco.skip` 中的任何一个
- [ ] enforcer 规则锁定 Maven ≥ 3.9、JDK ≥ 21，且禁止浮动版本与外部快照
- [ ] `mvn dependency:tree` 中不出现两个版本的 Jackson / Spring Boot / Spring AI
- [ ] 依赖树中不出现 Hibernate / `jakarta.persistence`
- [ ] 依赖树中不出现来源项目自带的 tracing 包；tracing 只来自 Micrometer + OTel bridge
- [ ] 有一份清单记录 Jackson 2 → 3 的行为变更，且每一条都有对应的契约测试
