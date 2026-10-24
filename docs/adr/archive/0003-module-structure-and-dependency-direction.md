# ADR-0003: 模块结构与依赖方向

- **状态**：已接受
- **日期**：2026-10-23
- **相关**：[0002 技术栈](./0002-tech-stack-and-version-pinning.md)、[0004 Spring AI 边界](./0004-spring-ai-boundary.md)、[0008 验证策略](./0008-verification-and-quality-gates.md)

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

几乎每一对都成环。**这不是"包分得不够细"的问题，而是没有定义依赖方向**：
任何一个类都可以 import 任何其他类，久了必然全部互连。

同时存在的规模问题：`QuotaLedgerService` 1268 行、`ServiceRequestHandler` 1027 行、
`StreamingRequestProcessor` 940 行、`AnthropicStreamingTranslator` 923 行。

## 决策

### 1. 模块数量保持克制

只分 4 个 Maven 模块：

```text
airouter-core       领域模型与端口       无框架依赖
airouter-gateway    face / provider / routing / governance
airouter-agent      Spring AI 层（见 ADR-0004）
airouter-app        启动、配置装配、打包
```

**刻意不做的事情**：不按 face / transport / routing / governance 各自开一个模块。
模块数量增加会让单次构建变慢、改动跨模块的成本变高，而换来的隔离度用**包 + 包间依赖规则**同样拿得到。
等 `airouter-gateway` 内部的包边界经过一段时间稳定下来，再决定是否拆模块 —— 那时拆是纯搬运。

依赖方向严格单向：

```text
app ──→ gateway ──→ core
  └───→ agent ────→ core        （agent 不 Java 依赖 gateway，见 ADR-0004）
```

`core` 不允许反向依赖任何人。它是最近没有框架的：一个框架出现在 `core` 的依赖树里就是设计倒退。

### 2. 网关内部按端口与适配器组织，不按技术角色

来源项目按技术角色分包（`controller` / `service` / `config` / `monitor`），
这种分法会把同一件事的代码摊到四个包里，而成环就是这么来的。

改用**端口与适配器**：网关领域逻辑稀薄、外部系统很多（各种模型厂商、Redis、数据库、
Agent 层的 MCP 服务），六边形比 DDD 四层更贴合 —— 真正需要表达的是"内外边界"，而不是"领域有多厚"。

```text
airouter-gateway/src/main/java/io/github/airouter/gateway/
├── faces/         入站协议：把外部请求翻译成 Canonical
│   ├── openai/    OpenAI 兼容 face（含 vLLM / Ollama / GPUStack 客户端）
│   └── anthropic/ Anthropic Messages face
├── providers/     出站协议：把 Canonical 翻译成厂商 wire format
│   ├── openai/
│   ├── anthropic/
│   └── gemini/
├── transport/     HTTP 出站：连接管理、超时、SSE 读取
├── routing/       实例选择、健康检查、重试、降级
├── governance/    限流、熔断、配额、脱敏
└── config/        配置绑定与装配（不含业务逻辑）
```

判断归属的唯一依据：**代码知道多少外部世界**。`faces` 和 `providers` 知道协议，
`transport` 知道 HTTP，`routing` 和 `governance` 只操作 Canonical 与抽象状态，
`config` 只做绑定。

### 3. 用机器而非自觉来维持依赖方向

- 引入依赖方向测试（ArchUnit 或等效手段），只写**少量核心规则**：
  `core` 不得依赖任何框架；`gateway` 内部包之间的允许引用矩阵；无人依赖 `app`
- 规则数量保持少。规则越多，越容易产生"加个例外算了"的冲动，最终退化成来源项目那 186 行抑制文件

### 4. 明确禁止三种压环手法

来源项目里出现过会把装配顺序变成隐式依赖的做法，这里一律禁止：

| 禁止 | 原因 |
|---|---|
| `@Lazy` | 把循环依赖从启动期推迟到首次调用，问题不是消失而是变成偶发 |
| setter 注入 / 字段注入 | 允许对象在半成品状态下被发布 |
| `getBean()` 按类型查找 | 依赖关系从类型签名里消失，只能运行时发现 |

构造函数注入是唯一允许的方式。出现构造循环说明类的职责边界需要调整，不是加个 `@Lazy` 绕过去。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 保持单模块，只调整包结构 | 依赖方向无法在构建层面被隔离，`core` 是否保持纯净只能靠自觉 |
| 每个关注点一个 Maven 模块（十几到二十个） | 与项目规模不匹配；跨模块改动的成本高，收益却可以用包规则拿到 |
| DDD 四层（interface / application / domain / infrastructure） | 网关几乎无领域行为，domain 层会变成只有 getter 的包；真正要表达的是内外边界 |
| 彻底消灭所有环后再开工 | 有些环的解除需要先做行为锁定（否则改完不知道有没有改坏），应按 ADR-0008 分批推进 |
| 依赖 AOP / 事件总线把环"技术手段消掉" | 只是把编译期的环藏到了运行期，调试成本更高 |

## 后果

**正面**

- 加一个 provider 不需要动 `faces` 里的任何代码（这是能被测试直接验证的，见 ADR-0008）
- `core` 纯净意味着领域模型可以在没有任何容器参与的情况下做单元测试

**负面**

- 从 6 个技术包迁到端口/适配器是一次大规模的文件移动，与 Boot 4 API 变更叠加，单次审阅量大
- `airouter-gateway` 在拆模块之前会比较大，需要靠包间依赖规则维持秩序
- 依赖方向测试的失败信息有时不够直观，需要在规则里写清楚为什么这么约束

## 如何验证

- [ ] `airouter-core` 的依赖树中不出现 Spring / Jackson / Reactor
- [ ] 依赖方向测试失败时给出具体类名与违反的规则，而不是笼统报错
- [ ] 代码中不出现 `@Lazy`、字段注入、`getBean()` 按类型查找；这一条由 CI 静态扫描保证
- [ ] 新增一个 provider 的完整变更集中在一个包内（可作为回归验证）
