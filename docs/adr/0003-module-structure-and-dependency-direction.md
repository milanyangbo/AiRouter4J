# ADR-0003：模块结构与依赖方向

- 状态：已接受
- 日期：2026-10-26
- 相关：[0002](./0002-tech-stack-and-version-pinning.md) 第 4 节（pom 需重建）、
  `AGENTS.md`「模块与依赖方向」（同一组约束的执行侧清单，改动两处同步）

## 1. 四个模块

按**端口与适配器（六边形）**组织，不按技术角色分包。

| 模块 | 职责 | 依赖 |
|---|---|---|
| `airouter-core` | 领域模型 + 端口接口 | **不依赖任何模块**，且不依赖任何框架 |
| `airouter-gateway` | faces / providers / transport / routing / governance / registry / select / store / cluster / admin / observe / config | 依赖 `core` |
| `airouter-agent` | Spring AI：ChatClient 装配 + Advisor 链 + Subagent | 依赖 `core`（通过端口拿网关数据，**不走回环 HTTP**） |
| `airouter-app` | 启动装配 + **跨模块结构规则的唯一住所** | 依赖全部 |

早先出现过一份十几个模块的清单，已被本文件否决：**模块数量与项目规模不匹配**，
且隔离度用包 + 包间规则同样拿得到。即使补齐九类能力面，代码量仍在四模块可承载的范围内。

## 2. `gateway` 的包划分与职责

| 包 | 知道什么 | 不该知道什么 |
|---|---|---|
| `faces` | 入站协议 ↔ Canonical | 背后有几个实例、走哪家厂商 |
| `providers` | Canonical ↔ 上游 wire format，产生终态 | 请求从哪个 Face 来 |
| `transport` | 字节 ↔ 帧（SSE / 逐行 JSON）、连接与超时 | 任何业务包 |
| `registry` | 配置 → 运行时对象（服务 / 实例 / 形态） | 选择策略 |
| `select` | 候选 + 权重 → 一个实例（**纯函数**） | 健康、IO、HTTP |
| `routing` | 选择链六步的编排、可用性判定 | HTTP 状态码、IO |
| `governance` | 熔断、限流、配额判定 | HTTP 状态码、IO；只读 `Outcome` |
| `store` | 库与 Redis 的读写 | 被 `faces` / `providers` 直接依赖 |
| `cluster` | 跨副本事件广播、分布式锁、共享计数 | — |
| `admin` | 管理面接口与鉴权 | — |
| `observe` | 结算点、指标、追踪 | 自己判定成败（只能收 `Outcome`） |
| `config` | 配置绑定与校验 | — |

每个包都要有 `package-info.java`，写清它知道什么、不该知道什么。

### 包的分期开放（阶段 0 的取舍）

**九条规则在阶段 0 一次落地，但生产包按需开放。** 阶段 0 开放
`config` / `faces` / `providers` / `transport` / `observe` 五个业务包，
每个只有 `package-info.java`；`registry` / `select` / `store` / `cluster` / `admin`
随各自阶段开放；`routing` 属**阶段 4**（ADR-0020 的「选择链与负载均衡」），
`governance` 属**阶段 7**。阶段号一律以 [0020](./0020-stage-plan-and-task-board.md) 为准。

**「空包不要提前开」的准确含义是**：不要让规则看起来已覆盖了一块实际不存在的区域。
所以它约束的不是「包能不能先建出来」，而是**规则守的必须是真有的东西**：

- 只有 `package-info.java` 的包是**占位包**，不算「规则已覆盖」
  （`RulesCoverNonEmptyPackagesTest` 按这个口径判定）
- R4 面向的 `routing` / `governance` 生产包阶段 0 不存在 ——
  它在阶段 0 通过**测试侧样例包**守门（见下一节），生产侧检查随包开放接管

这样取舍的代价与收益：代价是阶段 0 存在五个占位包；
收益是九条规则与它们的守门**从第一天就在位**，不需要等到阶段 7 才有 R4。

## 3. 依赖方向的核心约束

由 `airouter-app` 的结构测试判红，**跑在默认 `verify` 路径上**：

- `core` 的依赖树里出现 Spring / Jackson / Reactor → enforcer 判失败
- 没有模块可以依赖 `app`
- `faces` 与 `providers` **互不感知**
- `routing` / `governance` 不做 IO、**看不到 HTTP 状态码**
- `select` 是**纯函数**：只看候选与权重，不看健康、不做 IO
- `registry` 是「配置 → 运行时对象」的**唯一**持有者
- `store` 不被 `faces` / `providers` 直接依赖
- **Mapper 类型不越出 `store` 包**（MyBatis 是阻塞 API，出包就等于裸奔在事件循环上），
  且 `store` 对外只暴露 `Mono` / `Flux` —— 由结构规则判红，理由见 [0017](./0017-persistence-migrations-and-multi-replica.md) 第 2 节
- 禁止 `@Lazy`、字段注入 / setter 注入、按类型 `getBean`
- 只有 `providers` 可以构造 `Outcome`

规则在 `airouter-app/src/test/java/io/github/airouter/arch/StructuralRules.java`
（十一条，编号 R1–R11；前九条一次落地，R10 / R11 随阶段 5 补进来）。
该文件已随票 01 按下面的编号表复原，**编号未重排**（其余 ADR 与 AGENTS.md 里引用的是 R4 / R9 这些编号，
不是它们的内容）。

复原时改了两处形状，都是为了让规则在 core 还没有内容（阶段 0）的时候就能守门：

- **R6 / R7 是类级条件**，在一个条件里同时查类型、字段与方法三个位置，而不是拆成三条
  「noFields / noMethods」规则 —— 拆开之后，还没有字段与方法的阶段会有子规则空转，
  而 ArchUnit 对空转是报错的
- **R9 按类型名 `Outcome` 认目标**，不按 `Class` 认。core 的 `Outcome` 属阶段 1，
  在它落地之前，规则靠测试样例里的同名类型证明自己会红；两者可以互换而不必改规则
  （样例类型届时换成真实类型即可）

**R1–R9 编号表**（阶段 0 按此复原，编号不得重排）：

| 编号 | 规则 | 它保护什么 |
|---|---|---|
| R1 | `core` 的依赖树里没有框架 | 由 Maven enforcer 判红，不是 ArchUnit |
| R2 | 没有模块依赖 `app` | 装配顺序由类型签名决定 |
| R3 | `faces` 与 `providers` 互不感知（R3a 出站 / R3b 入站两个方向） | 新增一个 provider 不需要动 faces 里任何代码 |
| R4 | `routing` 与 `governance` 不碰协议与 IO、**看不到 HTTP 状态码** | 它们是纯函数 |
| R5 | `transport` 不知道业务包 | transport 只搬字节 |
| R6 | 禁止 `@Lazy` | 装配问题是启动期问题，不是偶发问题 |
| R7 | 禁止字段注入与 setter 注入 | 对象不会以半成品状态被发布 |
| R8 | 禁止按类型 `getBean` | 依赖关系写在类型签名里 |
| R9 | 成败的结论只有一个出口 | 结算点唯一 |
| R10 | MyBatis 的类型不越出 `store` 包 | 阻塞 API 不得裸奔在事件循环上（[0017](./0017-persistence-migrations-and-multi-replica.md) 第 2 节纪律 5） |
| R11 | `store` 对外只暴露 `Mono` / `Flux` | 同步返回值把阻塞留在调用方线程上（0017 第 2 节纪律 6） |
**新增规则前先读那里的表格**：规则越多，越容易走到「加个例外算了」那条路上。

R10 / R11 是阶段 5（引入 MyBatis）补进来的：在那之前没有 `store` 包，
而「规则守的必须是真有的东西」（第 2 节）—— 为一块还不存在的区域写规则，
只会得到两条永远不红的规则。编号续在 R9 之后，前九条不重排。
每条规则旁边必须写清它保护什么；「规则真的会红」由 `GuardsActuallyFireTest` 断言。

### 守门的机制：规则以包根为参数

**R3–R5 与 R9 以「包根」为参数**（`facesDoNotDependOnProviders(root)` 这种形状），
同一组规则因此既能作用在生产包上，也能作用在测试侧的样例包上。

它解决的正是上一节那个问题：规则要守的包阶段 0 可能还没有内容，
但「这条规则真的会咬人」不能等到包有内容那天才被验证。样例包有两套：

| 样例包 | 用途 | 被谁断言 |
|---|---|---|
| 合规样例（ordinary） | 正常的 Spring 用法与正常的包布局 | **不**被规则误报 |
| 违规样例（violations） | 故意违反各条规则的摆放 | 各自被对应的规则判红 |

外加装配样例（R6 / R7 / R8）与结算样例（R9）。

> 形状取自上一轮实现（tag `baseline-pre-11`）。那时 `routing` / `governance`
> 也只有测试侧的样例包 —— 这正是「生产包可以后到、守门必须先到」能成立的证据。

## 4. 为什么结构规则放在 `app` 而不是各模块内

跨模块的约束放在任何一个**被约束**的模块里，都会变成「自己审自己」：`gateway` 里的规则无法完整表达
`gateway → core` 的方向，因为它在 `core` 里没有立足点。放在 `app` 才能同时看到全部四个模块，
且 `app` 是唯一允许依赖所有模块的模块。

## 5. 后果

- 新建一个包就要补 `package-info.java`（checkstyle 与 spotbugs 的作用域包含测试源码）
- 想让 `faces` 直接读库、想让 `governance` 看状态码 —— 都会红；红的时候改的是设计，不是规则
