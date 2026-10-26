# ADR-0003：模块结构与依赖方向

- 状态：已接受
- 日期：2026-10-26
- 相关：[0002](./0002-tech-stack-and-version-pinning.md) 第 4 节（pom 需重建）

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
**空包不要提前开**：空包会让结构规则看起来已覆盖了一块实际不存在的区域。

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

规则定义在 `airouter-app/src/test/java/io/github/airouter/arch/StructuralRules.java`（九条）。
**新增规则前先读那里的表格**：规则越多，越容易走到「加个例外算了」那条路上。
每条规则旁边必须写清它保护什么；「规则真的会红」由 `GuardsActuallyFireTest` 断言。

## 4. 为什么结构规则放在 `app` 而不是各模块内

跨模块的约束放在任何一个**被约束**的模块里，都会变成「自己审自己」：`gateway` 里的规则无法完整表达
`gateway → core` 的方向，因为它在 `core` 里没有立足点。放在 `app` 才能同时看到全部四个模块，
且 `app` 是唯一允许依赖所有模块的模块。

## 5. 后果

- 新建一个包就要补 `package-info.java`（checkstyle 与 spotbugs 的作用域包含测试源码）
- 想让 `faces` 直接读库、想让 `governance` 看状态码 —— 都会红；红的时候改的是设计，不是规则
