# ADR-0003: 模块结构与依赖方向

- **状态**：已接受（第 2 版，2026-10-25 重写）
- **日期**：2026-10-25
- **相关**：[0002 技术栈](./0002-tech-stack-and-version-pinning.md)、[0004 Spring AI 边界](./0004-spring-ai-boundary.md)、[0007 治理模型](./0007-traffic-governance-model.md)、[0008 验证策略](./0008-verification-and-quality-gates.md)

## 背景

来源项目是**单模块**，6 个顶层包：`config` / `auth` / `common` / `monitor` / `persistence` / `router`。

实测的互相引用计数（A→B / B→A）：

```text
router      ↔ monitor       58 / 20      common      ↔ monitor        6 / 40
router      ↔ persistence   15 / 22      common      ↔ auth           1 / 151
router      ↔ config        25 / 32      auth        ↔ persistence   47 / 4
persistence ↔ monitor        4 / 14      persistence ↔ config         3 / 50
config      ↔ monitor        3 / 18      config      ↔ auth           3 / 3
```

几乎每一对都成环。**这不是「包分得不够细」的问题，而是没有定义依赖方向**：
任何一个类都可以 import 任何其他类，久了必然全部互连。

同时存在的规模问题：`QuotaLedgerService` 1268 行、`ServiceRequestHandler` 1027 行、
`StreamingRequestProcessor` 940 行、`AnthropicStreamingTranslator` 923 行。

## 决策

### 1. 模块数量保持克制：四个

```text
airouter-core       领域模型与端口       依赖树里无任何框架
airouter-gateway    face / provider / transport / routing / governance / 基础设施
airouter-agent      Spring AI 层（见 ADR-0004）
airouter-app        启动、配置装配、打包
```

**刻意不做的事情**：不按 face / transport / routing / governance 各自开模块，也不为 `store` / `cluster` 开新模块。
模块数量增加会让单次构建变慢、跨模块改动成本变高，而换来的隔离度用**包 + 包间依赖规则**同样拿得到。

依赖方向严格单向：

```text
app ──→ gateway ──→ core
  └───→ agent ────→ core        （agent 不 Java 依赖 gateway，见 ADR-0004）
```

`core` 不允许反向依赖任何人。它是唯一没有框架的模块：一个框架出现在 `core` 的依赖树里就是设计倒退
（由 Maven enforcer 与结构规则双重判红）。

### 2. 网关内部按端口与适配器组织，不按技术角色

来源项目按技术角色分包（`controller` / `service` / `config` / `monitor`），
这种分法把同一件事的代码摊到四个包里，成环就是这么来的。

改用**端口与适配器**：网关领域逻辑稀薄、外部系统很多（各种模型厂商、Redis、数据库、Agent 层），
六边形比 DDD 四层更贴合 —— 真正需要表达的是「内外边界」，而不是「领域有多厚」。

目标态包清单（✔ 已存在，○ Phase A/B 新增）：

```text
airouter-gateway/src/main/java/io/github/airouter/gateway/
├── faces/          ✔ 入站协议：把外部请求翻译成 Canonical
│   └── openai/     ✔ OpenAI 兼容 face
├── providers/      ✔ 出站协议：把 Canonical 翻译成厂商 wire format
│   ├── openai/     ✔
│   └── ollama/     ✔
├── transport/      ✔ HTTP 出站：连接管理、超时、SSE / 逐行 JSON 分帧
├── config/         ✔ 配置绑定与校验（不含业务逻辑）
├── registry/       ○ 配置 → 运行时对象：服务 / 实例 / 形态注册表，热重载与版本
├── select/         ○ 实例选择策略：纯函数，不做 IO
├── routing/        ✔ 可用性判定与选择编排：把 select 的结果收敛成一个可出网的实例
├── governance/     ✔ 熔断、限流、配额：只消费 Outcome，不做 IO
├── store/          ○ 持久化：JDBC + Flyway，配置版本与调用历史
├── cluster/        ○ 多副本：事件广播、共享计数、分布式锁
├── observe/        ✔ 指标、结算收口、运维端点
└── admin/          ○ 管理面 API
```

判断归属的唯一依据：**代码知道多少外部世界**。`faces` 和 `providers` 知道协议，
`transport` 知道 HTTP，`select` 只做纯函数决策，`routing` 与 `governance` 只操作 Canonical 与抽象状态，
`store` 与 `cluster` 知道存储但不该认识 wire 形态，`config` 只做绑定。

**`select` 与 `routing` 的分工要特别说明**，因为两者都在「选一个实例」这件事上：

| 包 | 职责 | 禁止 |
|---|---|---|
| `select` | 从**已确定可用**的候选里挑一个（轮询 / 加权 / 随机 / 最少活跃 / 延迟感知） | 不做 IO、不看健康状态、不认识 HTTP |
| `routing` | 判「哪些实例此刻能被选中」（`InstanceAvailability`），再调 `select` | 不做 IO（IO 是健康检查结果的写入方，不在请求路径上） |

混淆这两者会让「熔断打开的实例被权重选中」这类缺陷有藏身之处。

### 3. 用机器而非自觉来维持依赖方向

- 依赖方向测试（ArchUnit）跑在**默认 `verify` 路径**上，规则定义在 `airouter-app` 的
  `src/test/java/io/github/airouter/arch/StructuralRules.java`
  —— 位置不是随意挑的：`app` 是 classpath 上唯一能看到全部四个模块的地方
- 当前九条规则：R1 `core` 零框架 / R2 无人依赖 `app` / R3 `faces` 与 `providers` 互不感知 /
  R4 治理与路由看不到 HTTP 状态码 / R5 `transport` 不认识业务包 / R6 禁止 `@Lazy`、字段注入、`getBean` /
  R7 包间允许引用矩阵 / R8 每个包有 `package-info.java` / R9 只有产生终态的那一处能构造 `Outcome`

**规则数量保持少。** 规则越多，越容易走到「加个例外算了」的路上，最终退化成来源项目那 186 行抑制文件。
新增规则前先读 `StructuralRules` 里的表格；每条规则必须写清它保护什么；
「这条规则真的会红」由 `GuardsActuallyFireTest` 用故意违规的样例断言，不靠人记得试一次。

目标态会从 9 条扩到十几条（`store` / `cluster` / `select` / `admin` 各自要知道什么、不该知道什么）。
**扩规则是这个阶段的必要成本，也是风险**：漏写等于规则静默失效。因此每加一条规则，
必须同时加一个「违规样例会红」的测试。

### 4. 明确禁止三种压环手法

来源项目里出现过会把装配顺序变成隐式依赖的做法，这里一律禁止：

| 禁止 | 原因 |
|---|---|
| `@Lazy` | 把循环依赖从启动期推迟到首次调用，问题不是消失而是变成偶发 |
| setter 注入 / 字段注入 | 允许对象在半成品状态下被发布 |
| `getBean()` 按类型查找 | 依赖关系从类型签名里消失，只能运行时发现 |

构造函数注入是唯一允许的方式。出现构造循环说明类的职责边界需要调整，不是加 `@Lazy` 绕过去。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 保持单模块，只调整包结构 | 依赖方向无法在构建层面被隔离，`core` 是否纯净只能靠自觉 |
| 每个关注点一个 Maven 模块（十几到二十个） | 与项目规模不匹配；跨模块改动成本高，收益可以用包规则拿到 |
| 为 store / cluster 单独开模块 | 第四套依赖闸门与结构规则的成本大于收益；`gateway` 已是基础设施层 |
| DDD 四层（interface / application / domain / infrastructure） | 网关几乎无领域行为，domain 层会变成只有 getter 的包；真正要表达的是内外边界 |
| 彻底消灭所有环后再开工 | 有些环的解除需要先做行为锁定（否则改完不知道有没有改坏），应按 ADR-0008 分批推进 |
| 依赖 AOP / 事件总线把环「技术手段消掉」 | 只是把编译期的环藏到了运行期，调试成本更高 |
| 用包数量换隔离（一层一个包） | 包不是目录，包是「谁知道什么」的声明；包越多，允许引用矩阵越难维护 |

## 后果

**正面**

- 加一个 provider 不需要动 `faces` 里的任何代码（这是能被测试直接验证的：
  `SecondProviderTouchesNoFaceTest` 用 `git diff` 统计差异文件，红的时候会说清是哪个文件）
- `core` 纯净意味着领域模型可以在没有任何容器参与的情况下做单元测试
- 目标态的新包各自有明确的「知道什么 / 不该知道什么」，规则可以直接写出来

**负面**

- `airouter-gateway` 在拆模块之前会比较大，需要靠包间依赖规则维持秩序
- 规则从 9 条扩到十几条是可预期的成本，且每条都要配违规样例
- 依赖方向测试的失败信息有时不够直观，需要在规则里写清楚为什么这么约束

## 如何验证

- [ ] `airouter-core` 的依赖树中不出现 Spring / Jackson / Reactor
- [ ] 依赖方向测试失败时给出具体类名与违反的规则，而不是笼统报错
- [ ] 代码中不出现 `@Lazy`、字段注入、`getBean()` 按类型查找
- [ ] 新增一个 provider 的完整变更集中在 `providers/` 内（`SecondProviderTouchesNoFaceTest`）
- [ ] 每条结构规则都有一个「故意违规」的样例断言它会红（`GuardsActuallyFireTest`）
- [ ] 新建任何包（含测试里的样例包）都补了 `package-info.java`
