# AGENTS.md

本文件是**硬约束清单**，不是项目介绍。项目背景、目标态与阶段顺序见 `docs/adr/0001`。
每条都当成「违反就改代码」的硬要求，不是建议。

## 权威来源

- **`docs/adr/` 是唯一权威**。动手前先读相关 ADR。产出与某条 ADR 冲突时**显式指出**（编号 + 为什么值得重开），**不要静默覆盖**
- 本文件是 ADR 的**执行侧清单**：每条都能在 `docs/adr/` 找到它的理由。**两处不一致时以 ADR 为准，并立刻改本文件**。
  反过来（只在 AGENTS.md 里改掉一条）会造出两处都能改、改一处就漂移的局面
- 术语以根 **`GLOSSARY.md`** 为准。需要的词不在表里 → 要么你在发明项目里不存在的语言（先重新考虑），要么表有真实缺口（记给 `domain-modeling`）
- **四个模块是分层，不是四个业务上下文**：所以只有**一份**根 `GLOSSARY.md`，不按模块各写一份。
  判断标准：同一个词在两个模块里含义不同 → 那是我们在制造歧义（去修那个词），不是该分裂术语表
- 写、改、删测试，或判断一条测试该不该留 → **`docs/agents/testing.md`**：四条判据、正反例、组织界限（每模块 ≤10 个文件、每文件 ≤20 个方法、行覆盖率 ≥60%）
- `legacy/` 是**设计素材库**，不是禁区：可搬，但每一项都要裁决（搬 / 改 / 新增 / 不搬），且搬进来的代码必须过三道关——换坐标、适配新契约、补它原来没有的失败与缺席路径测试。**「一律不搬」与「一律搬」同样不可接受**。裁决标准见 ADR-0001

## 模块与依赖方向

由 `airouter-app` 的结构测试判红，**跑在默认 `verify` 路径上**。底层是 ArchUnit 的库，但**不用它的 `@AnalyzeClasses` 引擎**（理由见测试一节）。

| 模块 | 职责 | 约束 |
|---|---|---|
| `airouter-core` | 领域模型 + 端口接口 | 依赖树里出现 Spring / Jackson / Reactor 即构建失败（Maven enforcer） |
| `airouter-gateway` | faces / providers / transport / routing / governance / registry / select / store / cluster / admin / observe / config | 按端口与适配器分包，包职责写进各自的 `package-info.java` |
| `airouter-agent` | Spring AI：ChatClient + Advisor 链 + Subagent | 通过 core 端口拿网关数据，**不走回环 HTTP** |
| `airouter-app` | 启动装配 + **跨模块结构规则的唯一住所** | 没有模块可以依赖它 |

- `faces` 与 `providers` **互不感知**；`routing` / `governance` 不做 IO、**看不到 HTTP 状态码**；`transport` 不认识业务包
- `select` 是**纯函数**（只看候选与权重，不看健康、不做 IO）；`registry` 是「配置 → 运行时对象」的**唯一**持有者；`store` 不被 `faces` / `providers` 直接依赖
- 禁止 `@Lazy`、字段注入 / setter 注入、按类型 `getBean`
- 成败的结论只有一个出口：只有 `providers`（产生终态的那一处）可以构造 `Outcome`
- **空包不要提前开**：空包会让结构规则看起来已覆盖一块实际不存在的区域

结构规则定义在 `StructuralRules`（九条）。**新增规则前先读那里的表格** —— 规则越多，越容易走到「加个例外算了」那条路上。规则旁边必须写清它保护什么；「规则真的会红」由 `GuardsActuallyFireTest` 断言，不靠人记得试一次。

## 成败与结算

最容易写错的一块，每条都对应一个真实踩过的坑：

- **流式只在终态结算**。`CanonicalStreamEvent.Terminal / Failed` 是终态的**唯一**形态，由 Provider 显式发出
- 流式的出站**不套**非流式那个总时长上限：`idleTimeout` 是**块间**时长，给一条流设总时长等于替调用方决定「模型最多想多久」。SSE 的下游响应同样不设 `ResponseTimeout`
- 分帧在**字节层**缓存（一个多字节字符被切成两半仍能合并）；「一帧字节 → 哪些事件」是形态的事，写在 `providers/<形态>/` 的 codec 里，传输层不认哨兵
- `OutcomeObserver` 是结算的**唯一**收口点。任何组件不得自己看状态码或异常来判定成败（R4 / R9 判红）
- 本阶段它的实现是 `SettlementLog`（诊断用的窗口账本，256 条，**不是存储**）。熔断 / 配额 /
  调用历史随后接的是**同一个接口**，不是这个类 —— 声明成接口的话诊断也取不到它
- 上游没发结束哨兵就把连接收了 → 补发 `Failed(upstream_closed)`；帧间静默超上限 → `Failed(UPSTREAM_TIMEOUT)`。**都不是成功**
- 调用方中途断开没有事件可发，但**必须结算成 `CLIENT_CANCELLED`**；一条流恰好结算一次，且取消**不计入熔断**
- 本地拒绝（熔断 OPEN / 未登记 / 限流拒绝）**不出网、也不结算** —— 把它算作失败会让它继续喂熔断器，打开的熔断器就再也等不到冷却结束的那次成功探测
- 未被纳入健康管理的实例一律不可用（fail-closed），配置漏项表现为不可用而不是静默放行
- **任何流式路径上的逻辑必须有同步路径的对应实现**。只做一半 = 静默失效
- 治理默认 **fail-closed**。确需 fail-open 必须写进 ADR 并写明补偿措施
- 禁止在 Reactor 事件循环线程上做阻塞 IO

## 实例维度与治理顺序

- 选择链六步（认证 → 规则路由 → 可用性闸门 → 实例选择 → 出网 → 失败转移）落在 `routing` 的**一处**编排里，不散在若干 handler 里
- **实例维度先于一切作用域类功能**：限流的作用域、规则路由的目标、失败转移换谁重试、配额的维度，全部以「一个服务下有多个实例」为前提
- 失败转移只对**显式白名单**内的可重试失败生效（写「除了取消都重试」等于把 4xx 也重试一遍）；**只结算最终那一次**
- 新增一种 wire 形态 → 在形态工厂登记一个构造器，**不在调用路径上按形态分支**

## 持久化、配置与凭据

- **关系存储用 MyBatis，不引入 JPA，也不用裸 `JdbcClient`**（JPA 的懒加载与 flush 是流式路径上的隐形阻塞点；裸 JDBC 把 SQL 打散在 Java 里，无法与迁移一起评审）
- **SQL 只写在 XML mapper 里**：不写在注解里、不在 Java 里拼字符串。**不引 MyBatis-Plus**（它的 Wrapper 把 SQL 搬回 Java）
- **Mapper 只出现在 `store` 包内，且对外只暴露 `Mono` / `Flux`**：MyBatis 是阻塞 API，每次调用都要经 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`。直接调用就是把 JPA 的隐形阻塞换成 MyBatis 的显式阻塞
- **禁用懒加载、嵌套 `select` 与二级缓存**：要关联就 join + 扁平 `resultMap`，一条查询路径的 SQL 条数必须等于 mapper 语句数
- **流式热路径上不落库**：调用历史经缓冲后异步批量写入。八个字：**mapper 跟着迁移走** —— 引用了哪一列就要有哪次迁移
- **Flyway 是 schema 的唯一变更路径**。没有 `ddl-auto`，没有手写建表脚本，没有「先手动加一列、回头再补迁移」
- H2 默认 / PostgreSQL 按 `DATABASE_URL`，**同一套迁移双方言**。只在 PG 上验过的 SQL 不算数
- **依赖缺席是契约，不是兜底**。DB 或 Redis 不在时的行为必须是**被断言过**的那一种，而不是 NPE 之后被某个 `catch` 吞掉。每一处新依赖都要有一条「它不在」的测试
- 配置变更**不重建 Spring 上下文**（会丢在途的流、熔断计数、延迟样本）；热重载的粒度是替换注册表里的对象
- 配置文件里不得出现 API 密钥，一律走环境变量；**凭据不透传给下游**
- 新增一个 OpenAI 兼容厂商是**配置**（在适配器声明里登记），不是新类

## 构建与测试

- JDK 21；Maven Wrapper 已入库，**统一用 `./mvnw`**，不要用系统里的 `mvn`
- 默认构建路径即全部门禁：`./mvnw clean verify`，**不带任何 skip 参数**
- 档位 `local` / `cloud` 只改 profile 或环境变量，**不改代码**；变量名清单见 `.env.example`
- 本机端点的安装、起停与实测能力边界见 `docs/runbook/local-openai-endpoint.md`。本机地址写 `127.0.0.1` 不写 `localhost`
- 每个测试类必须用 `@Tag` 标出所属 gate（`unit` / `integration` / `architecture`），标准是**启不启容器、出不出网**；遗漏由 `EveryTestBelongsToAGateTest` 判红
- **不要用 ArchUnit 自带的 `@AnalyzeClasses` 引擎**：它看不见类上的 `@Tag`，按 gate 筛选时整个类会被静默漏掉
- 契约测试在 `airouter-app/src/test/resources/` 下三个目录（`contract/` 非流式、`contract-stream/` SSE、`contract-ollama/` 逐行 JSON）。已建 runner 的目录是一个用例一个目录，放 `upstream.json` + `expected.json` 即被发现，**不需要改测试代码**；`contract-ollama/` 目前**只有样本没有 runner**（它的样本是从真实端点抓下来的数据资产，runner 随 `ollama` 形态落地时才建 —— 不为尚不存在的形态建 runner）。它们是未来任何框架升级的唯一安全网
- **时间相关的断言要等条件成立，不要睡固定时长再比数字**
- 故意制造上游失败的用例必须交代它与熔断阈值的关系：同一测试类里三条失败就会把熔断器打开，后面用例收到的是 `instance_unavailable` 而不是它想看的上游错误 —— 表现为一条**与执行顺序有关**的偶发红
- 两个静态分析的作用域都包含测试源码 → 每个新包（含测试里的样例包）都要补 `package-info.java`

## 违约处置

处置路径是**改代码**。确实无法避免的情形，处置路径是**写一份 ADR** —— 不是加一行抑制、一个排除块或一处 include 例外。
