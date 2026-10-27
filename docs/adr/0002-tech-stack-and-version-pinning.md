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
| 关系存储 | MyBatis + mybatis-spring-boot-starter | 大版本与 Boot 4 对齐（starter 4.x 线），**具体版本在阶段 5 锁定后写回本表** |

**版本锁定的理由不是「新就是好」，而是这个组合是唯一同时满足三条的**：Boot 4 带来 Jackson 3；
Spring AI 2.0.1 是与 Boot 4 对齐的那条线；Java 21 是 Boot 4 的底线。
中间任何一项降级都会把另外两项一起拖回旧世界。

### 版本锁定的实测依据

2026-10-23 逐个坐标探测 `repo1.maven.org` 的结果（这是本表不是「凭印象填的」的证据）：

| 坐标 | 结果 |
|---|---|
| `spring-boot-starter-parent:4.1.2` | 200 |
| `spring-ai-bom:2.0.1` / `spring-ai-starter-model-openai:2.0.1` | 200 |
| `spring-ai-agent-utils:0.13.0` / `spring-ai-session-bom:0.10.0` | 200 |
| `maven-checkstyle-plugin:3.6.0` / `checkstyle:10.21.2` | 200 |
| `spotbugs-maven-plugin:4.8.6.6` | 200 |
| `spotbugs-maven-plugin:4.9.3` | **404（不存在）** |

关键的一条是直接从 `spring-ai-starter-model-openai-2.0.1.pom` 读到的：它依赖
`spring-boot-starter-webclient:4.1.1` 与 `spring-boot-starter-restclient:4.1.1`。
**Spring AI 2.0.1 与 Boot 4.1.x 是绑定的，不是「建议搭配」** ——
所以不存在「网关留在 Boot 3.5、只把 Spring AI 引进来」这条中间路线。

## 2. 新阶段引入的组件选型

目标态（九类能力面）比骨架阶段多了存储、缓存、分布式协调与观测：

| 需要 | 选择 | 不选择 | 理由 |
|---|---|---|---|
| 关系存储 | **MyBatis**（XML mapper，用法纪律见 [0017](./0017-persistence-migrations-and-multi-replica.md) 第 2 节） | JPA / Hibernate、裸 `JdbcClient` | JPA 的懒加载与 flush 时机是**流式路径上的隐形阻塞点**，且网关没有复杂对象图，ORM 换不来等价收益；裸 `JdbcClient` 则把 SQL 打散在 Java 里，无法与迁移一起评审。MyBatis 换来的正是「SQL 集中在 XML」，代价是它是**阻塞 API**，必须由响应式边界隔离 |
| Schema 迁移 | **Flyway** | Liquibase / `ddl-auto` | 见 [0017](./0017-persistence-migrations-and-multi-replica.md) 第 3 节 |
| 默认数据库 | **H2** | — | 无依赖起步，`local` 档位不需要任何外部服务 |
| 生产数据库 | **PostgreSQL**（pgvector 预留） | MySQL | `DATABASE_URL` 指向即切换；JSONB 与窗口函数对调用历史查询有用 |
| Redis 客户端 | **Lettuce**（Boot 默认） | Jedis / Redisson | Redisson 的分布式锁很方便，但它把一整套对象语义一起带进来；我们只需要一段 Lua |
| 本地缓存 | **Caffeine** | 自研 LRU | legacy 自研了一个 `LRUCache`，不需要 |
| 指标 | **Micrometer + Prometheus registry** | 自研计数器 | — |
| 链路追踪 | **OpenTelemetry**（OTLP 导出） | 自研 | legacy 有 91 个自研 tracing 类（`monitor/tracing`），**不搬** |
| 加密 | **JDK 原生 AES-256-GCM** | BouncyCastle | JDK 自带，少一个依赖 |
| 校验 | Jakarta Validation | 手写 if | — |

### 被否决的候选

| 候选 | 否决理由 |
|---|---|
| **R2DBC**（响应式关系驱动） | 与 WebFlux 最契合，但 H2 的 R2DBC 驱动能力弱于 JDBC 驱动，而 H2 是我们的默认档位 —— 双方言测试会退化成「只在 PG 上验过」。且 R2DBC 没有 MyBatis 的等价物，等于把 SQL 打回 Java。**落库本就不在热路径**（[0017](./0017-persistence-migrations-and-multi-replica.md) 纪律 7），用 `boundedElastic` 隔离同步驱动能拿到与 R2DBC 相同的隔离效果，代价小得多。**哪天 JDBC 侧确实进了热路径，这个议题重开** |
| **jOOQ** | 类型安全 SQL 是真的好，但它把 SQL 写在 Java 里，与「SQL 与迁移一起评审」冲突；部分特性需商业授权 |
| **MyBatis-Plus** | Wrapper 把 SQL 搬回 Java，与纪律 1 正面冲突；它的分页插件对百万行历史表是假需求 |

## 3. 依赖纪律

- **`airouter-core` 零框架依赖**，由 Maven enforcer 判红（出现 `org.springframework*` / Jackson / Reactor 即失败）
- **新增依赖要写它的「缺席测试」**：依赖不在时的行为必须是被断言过的那一种，不是被 `catch` 吞掉（[0008](./0008-verification-and-quality-gates.md) 第 4 节）
- 不引入「为了将来可能用到」的依赖

## 4. 构建状态

五个 `pom.xml`（根 + 四模块）已在票 01 重建：enforcer（版本、JDK 21、重复上界、插件版本、
禁止抑制文件）、checkstyle 与 spotbugs（作用域含测试源码）、surefire 的三道 gate 分组
（默认跑全部，`-Dgroups=unit|integration|architecture` 挑一道）。

阶段 0 的依赖清单刻意只放了当时真正用得到的东西：
`gateway` 只依赖 `core`，`agent` 只依赖 `core`，`app` 额外带测试作用域的
spring-boot-starter-test / archunit / spring-web（R4 样例需要一个状态码类型）。

票 02（非流式打通）是依赖清单第一次随阶段增长的时刻，增长的三项各有各的用途：
`gateway` 加 WebFlux（入站 Face 的注解与出站 WebClient 是同一个运行时）、
`app` 加 WebFlux 与可执行 jar（`@SpringBootApplication` 在这一票才出现）、
`app` 加测试作用域的 `com.openai:openai-java`（让「标准客户端能否解析我们的响应」由第三方判定）。
actuator 与 MyBatis 仍然随各自的阶段进来 —— 不按「将来可能用到」提前引入。

## 5. 后果

- 升级 Boot 小版本前先跑契约测试（`contract/` 三目录）—— 序列化行为变了，最先红的是它们
- 任何一处想引 JPA、Redisson、MyBatis-Plus 或自研 tracing，都要先推翻本文件
- MyBatis 是这套栈里**唯一的阻塞 API**。它带来的风险与 JPA 同源（事件循环上的隐形 IO）,
  所以 [0017](./0017-persistence-migrations-and-multi-replica.md) 第 2 节的八条纪律不是风格偏好，
  是引入它的**对价**。想放宽其中任何一条，先推翻本文件
