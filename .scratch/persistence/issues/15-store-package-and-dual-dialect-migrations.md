# 15: store 包起步 —— 双方言迁移、响应式边界与两条结构规则

**What to build:** 阶段 5（ADR-0020）的第一块：把「库」引进来，但**只引进到它该待的边界为止**。
一块可验证的垂直切片：Flyway 在 H2 与 PostgreSQL 上跑同一套迁移 → 一个 `store` 包用 XML mapper
读写其中一张表 → 对外只暴露 `Mono` / `Flux` → 两条结构规则把「MyBatis 不许出包」与
「store 不许暴露阻塞返回值」钉住 → DB 不在时的行为被断言过。

这一票不铺全部七张表（ADR-0017 第 4 节）。只铺**一张有真实消费方**的表：配置版本快照
（消费方是 [0010](../../../docs/adr/0010-configuration-model-and-runtime-registry.md) 第 6 节
「配置版本与回滚」—— 它回答「现在生效的是哪一版、能回滚到哪一版」）。
表由消费方倒推，不是由 legacy 的 20 张表倒推：其余六张等各自的消费方到位再落。

来源：ADR-0020 阶段 5；ADR-0017 第 1–4、7–9 节。

1. **MyBatis 是这套栈里唯一的阻塞 API，八条纪律是引入它的对价（0017 第 2 节）。**
   本票落地其中四条：SQL 只在 XML mapper 里（纪律 1）、禁用二级缓存 +
   `localCacheScope=STATEMENT`（纪律 2）、mapper 只出现在 `store` 包内（纪律 5）、
   所有调用过 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`（纪律 6）。
   纪律 3/4/7/8 随后续票（禁懒加载与嵌套 select 要有 join 才能验；不引 MyBatis-Plus 由依赖清单保证；
   流式热路径不落库要等调用历史；方言分支要有两条分支才谈得上）
2. **双方言不是「两份配置」，是同一套迁移在两个方言上各跑一次（0017 第 1 节）。**
   迁移文件按 `db/migration/h2/` 与 `db/migration/postgres/` 同名成对（legacy 的 V1–V4 就是这个结构，
   0017 第 8 节裁决「搬」）。方言由 `DATABASE_URL` 决定，默认 H2 —— 因此
   **只在 PG 上验过的 SQL 不算数**：mapper 的每一条语句都要在两个方言上各跑一次
3. **`store` 被 `faces` / `providers` 直接依赖是红（0003 第 2 节）。** 本票把这条与
   「store 对外只暴露 `Mono` / `Flux`」变成 **R10 / R11** 两条结构规则，编号续在 R9 之后
   （0003 第 3 节：新增规则前先读那张表，规则旁必须写清它保护什么）
4. **依赖缺席是契约，不是兜底（0017 第 7 节）。** 「DB 不在」的默认行为是
   *内存档运行、启动时不失败*，而不是 NPE 之后被某个 `catch` 吞掉。
   所以「有没有库」是**启动期探测一次**的结果（`StoreAvailability`），不是每次调用 catch ——
   后者会把「SQL 写错了」和「库不在」混成同一件事，于是坏 SQL 也能静默通过
5. **ADR-0002 第 1 节那一行空着的关系存储版本，本票写回。**
   它原本就写着「具体版本在阶段 5 锁定后写回本表」

**Blocked by:** 14

**Status:** done（2026-10-30）

## Acceptance criteria

- [x] `airouter-gateway` 加四个依赖：`mybatis-spring-boot-starter`、`flyway-core`、
      `flyway-database-postgresql`、`h2`、`postgresql`。版本只有一处：
      MyBatis 不在 Boot 的依赖管理里 → 版本号写进根 pom 的 `<properties>`（实测本机仓库已有
      `mybatis-spring-boot-starter:4.0.1` 与 `mybatis-spring-boot-autoconfigure:4.0.1`）；
      Flyway / H2 / PG 由 Boot 4.1.2 管理（实测 `spring-boot-dependencies-4.1.2.pom`：
      flyway 12.4.0、h2 2.4.240、postgresql 42.7.14），**不自己再写一个号**
- [x] 迁移 `V1__config_snapshot.sql` 在 `db/migration/h2/` 与 `db/migration/postgres/` 同名成对：
      `id`、`version`（唯一、单调递增）、`content`（快照正文）、`created_at`、`actor`。
      正文类型按方言各写各的（H2 `CLOB` / PG `TEXT`）—— 迁移分目录正是为了容纳这种差异，
      不需要 `databaseIdProvider`
- [x] 方言选择只由 `DATABASE_URL` 决定：不设 → H2 内存库（默认档位不需要任何外部服务）；
      设了 → 用它给的 JDBC URL。**不做 `postgres://user:pass@host/db` 到 JDBC 的转换**：
      现阶段没有消费方需要它，按「不引未用的复杂度」留白，需要它的那一票再补
- [x] `store` 包：`ConfigSnapshotStore`（public，方法只返回 `Mono` / `Flux`）、
      `ConfigSnapshotMapper`（**包私有**接口 + `@Mapper`，SQL 全在 XML 里）、
      `ConfigSnapshot`（record）、`StoreAvailability`、`package-info.java`
- [x] MyBatis 纪律 2/6 落地：`cache-enabled=false`、`localCacheScope=STATEMENT`，
      每次调用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`
- [x] **R10**：`store` 包之外的生产类不依赖 `org.apache.ibatis..`（连 mapper 接口与 `SqlSession`
      一起挡在包内）。**R11**：`store` 包里的 public 类，public 方法只返回 `Mono` / `Flux`。
      两条都进 `StructuralRules`，`ModuleStructureTest` 各有一条断言，
      `archtest/layout/{ordinary,violations}/store/` 各有样例，`GuardsActuallyFireTest` 证明会红
- [x] 集成测试一条：H2 上迁移起来 → 写入 → 读回 → **迁移幂等**（重复执行不报错、不改数据）
- [x] 集成测试一条：PostgreSQL 上跑同一套断言（Testcontainers，`postgres:16-alpine`
      本机已有该镜像；`org.testcontainers:testcontainers-postgresql:2.0.5` 与 Boot 管理版本一致，本机仓库已有）
- [x] 断言一条「DB 不在」：数据源指向不可达地址时，应用**起得来**，
      `store` 的读返回「没有快照」（降级），而不是抛连接异常 / NPE
- [x] 结构测试登记：`store` 进 `RulesCoverNonEmptyPackagesTest` 的 `PACKAGES_WITH_CLASSES`
- [x] ADR-0002 第 1 节关系存储那一行的版本写回（含实测依据：本机仓库与中央仓库的探测结果）
- [x] gateway 测试文件数维持在 **10**：`OutboundWireTest`（4 方法）与 `SseFramerTest`（12 方法）
      合成一份 `OutboundBytesTest`（16 方法）—— 两者同在 `transport` 包、同是「出站这一侧的字节」
      （分帧看上游把字节切成了什么样，出站口看根本没字节过来）；空出的名额给 store 的集成测试文件
- [x] `./mvnw clean verify` 绿，覆盖率不降（当前 89.0%）

## 实测与踩坑

覆盖率 **89.0% → 89.4%**，gateway 测试文件数维持 10（`OutboundWireTest` 已并入
`OutboundBytesTest`，与 `SseFramerTest` 合成一份），`./mvnw clean verify` 全绿。

1. **Boot 4 换了 JDBC 自动配置的包名。** `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`
   在 4.1.2 里不存在了，它在 `org.springframework.boot.jdbc.autoconfigure` ——
   Boot 4 把自动配置按模块拆成了多个 jar（`spring-boot-jdbc` 等），包名跟着走。
   同一类迁移后面还会遇到（flyway / redis 那几个同理）
2. **Testcontainers 2.x 的坐标叫 `testcontainers-postgresql` / `testcontainers-junit-jupiter`**，
   不是 1.x 的 `postgresql` / `junit-jupiter`。写旧坐标的报错是「缺版本号」而不是「找不到」——
   因为 bom 里根本没有那个 artifactId，于是看起来像忘了写号
3. **MyBatis 的构造器映射里 `javaType="long"` 是包装类型。** 别名表里 `long` → `java.lang.Long`，
   原始类型是 `_long`。record 的规范构造器收的是原始类型，写 `long` 的报错是
   `NoSuchMethodException: <init>(Long, String, Instant, String)` ——
   一条**只在读的时候**才出现的错，写的时候与迁移的时候都不报
4. **mapper XML 与接口同包同名会被自动加载**（`MapperAnnotationBuilder#loadXmlResource`），
   于是不需要 `mybatis.mapper-locations`。这省掉的不是一个配置项，而是「SQL 在哪」的第二个答案
5. **`@Mapper` 接口的自动扫描范围跟着启动类走**：MyBatis starter 在没写 `@MapperScan` 时
   扫的是 `@SpringBootApplication` 那个包（`io.github.airouter.app`），
   gateway 里的 mapper 因此扫不到 —— 装配配置跟着被装配的东西走，才不用为它开例外
6. **R11 的返回类型名字带泛型**：`Mono<ConfigSnapshot>` 的全名不等于 `reactor.core.publisher.Mono`。
   按前缀认，但要收到 `Mono` / `Flux` 这两个名字本身（否则 `MonoProcessor` 之类同前缀的邻居也被放过）
7. **探红改资源文件要连 `target/classes` 里那份一起动。** 第一遍把
   `db/migration/postgres/V1__config_snapshot.sql` 挪走，PG 测试照样绿 ——
   Maven 不会删掉已复制过去的旧文件，于是测的是上一份。删掉两边各一份之后才红在
   `BadSqlGrammar`（表不存在），且**只有 PG 那条红**，H2 那条仍绿
8. **探红 A**：去掉 `latest()` 里的可用性判断 → 恰好「DB 缺席」那条红（MyBatisSystem），另两条仍绿。
   「库不在」这一档因此不是「没崩就算过」：它断言的是上下文起得来、迁移没跑、读给出空三件事
9. **两个 `Configuration` 撞名**：`org.apache.ibatis.session` 与
   `org.springframework.context.annotation` 各有一个，装配类里只能 import 一个 ——
   javadoc 里改写成全限定名。这是「SQL 与装配同处一个包」顺带付的一点代价
