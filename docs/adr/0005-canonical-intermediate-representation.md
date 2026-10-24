# ADR-0005: Canonical 中间表示

- **状态**：已接受（第 2 版，2026-10-25 重写；已落地）
- **日期**：2026-10-25
- **相关**：[0003 模块结构](./0003-module-structure-and-dependency-direction.md)、[0006 流式终态](./0006-streaming-terminal-outcome-contract.md)、[0009 形态自述](./0009-second-wire-shape-and-shape-self-description.md)

## 背景

网关两侧都不止一种协议：入站至少有 OpenAI 兼容与 Anthropic Messages，
出站至少有 OpenAI 兼容、Ollama 原生、Gemini 原生。

如果每一对入站/出站都写一份翻译，是 **N×M** 份实现，且新增一侧要让另一侧全部跟着改。
来源项目就是这么做的（它在两侧各写一套 transformer，于是「加一家 OpenAI 兼容厂商」变成写一对新类）。

## 决策

### 1. 一切翻译都经过 Canonical：N+M 而不是 N×M

```text
Face（入站协议）──→ CanonicalRequest ──→ Provider（出站协议）──→ 厂商 wire format
Face ←── CanonicalResponse / CanonicalStreamEvent ←── Provider
```

新增一个 Face 或 Provider 都只是加一个类，另一侧零改动。这条不是口号，
它由 `SecondProviderTouchesNoFaceTest` 判红（统计 `git diff` 的差异文件，红的时候会说清是哪个文件）。

### 2. Canonical 的类型是 sealed 的

`CanonicalStreamEvent` 是 `sealed interface`，子类型只有六个：
`Opening` / `TextDelta` / `Usage` / `Ping` / `Terminal` / `Failed`。

sealed 的理由具体到终态上：**漏处理一个分支是编译错误，而不是线上悬置**。
终态漏处理的后果是「一条流永远没有结果」，而没有任何编译器会在那种情况下提醒你。

### 3. 三个「不像内容」的事件被单独建模

| 事件 | 为什么单独建模 |
|---|---|
| `Opening` | id / model / created 属于**整条流**而不是某一帧；后续每一帧都要带着它们出境。而「第一个增量帧」这个时机在遇到空内容流时并不存在 |
| `Usage` | 厂商常在流末尾单独发一帧纯用量（开了 `stream_options` 时更常见），那一帧里 `choices` 是空数组。把它当内容帧处理会得到一个 content 为空的假增量 |
| `Ping` | 保活注释行在语义上不是内容，**也不是终态判断的依据**：只发注释的上游是活的但没说话，与「连接已断」是两回事。后者由空闲超时判，前者不该触发它 |

**刻意不建 `ToolCallDelta`**：工具调用的增量形态取决于上游真实怎么发，而现在没有能稳定复现它的端点。
与其先画一个未经验证的类型，不如等有了可回放的上游再补 —— 那时它是被样本钉住的，不是被想象出来的。

### 4. Passthrough 装「网关不解释但必须转发」的字段

`Map<String, String>`，**值是 JSON 原文**而不是解析后的对象 —— 因为 `core` 不许依赖 Jackson
（[0003](./0003-module-structure-and-dependency-direction.md) R1），只能是文本，解码时再解析一次。

它挂在四个位置：响应顶层、每个候选、每条消息、usage 内部。厂商在这些位置挂私有字段都很常见，
而丢掉它们的代价是「某个客户端突然少了一个字段」，且这种丢失不会报错。

### 5. 端口返回 JDK 类型，不返回 Reactor 类型

| 端口 | 返回 |
|---|---|
| `CompletionPort` | `CompletionStage<CanonicalResponse>` |
| `StreamingCompletionPort` | `java.util.concurrent.Flow.Publisher<CanonicalStreamEvent>` |

不返回 `Mono` / `Flux`：Reactor 进入 `core` 的依赖树会被 enforcer 判红。
两侧各用一次 `JdkFlowAdapter` 转换即可接上 —— 一次转换的成本，换 `core` 在零容器下可单测。

### 6. 错误只有一处形态：GatewayError

从传输层故障到上游 4xx/5xx，最终收敛成一个 `GatewayError`（`type` / `message` / `httpStatus`）到达 Face。
`UpstreamFailure` 是携带它的 **unchecked** 异常：调用链上的中间层都不打算处理它，只有 Face 在边界上收口一次。

### 7. 一份字节只允许解析一次（CHUNK_PARSE_COUNT = 1）

一次流式 pipeline 中同一个 chunk 被解析的次数**必须恒为 1**。允许同一份封装有多个 reader，
不允许重复 parse。来源项目的 Anthropic 流式路径对同一个 chunk 做了 4 次 `readTree` + 2 次 `writeValueAsString`。

这条由 `ChunkParseCounter` 断言，是三条核心契约之一（[0008](./0008-verification-and-quality-gates.md) 第 3 节）。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 入站与出站直接互转（N×M 翻译） | 新增一侧要让另一侧全部跟着改；来源项目正是这么做的 |
| 用 `Map<String, Object>` 当中间表示 | 字段的存在性只能靠运行时发现；而「某个客户端少了一个字段」这类缺陷正是最难查的 |
| 事件用普通接口而非 sealed | 漏处理一个分支不报错；对终态尤其危险 |
| 把 `Usage` 塞进 `Terminal` | 厂商会单独发一帧纯用量，那一帧之后还有别的帧；塞进终态会丢掉顺序信息 |
| 把保活帧当内容或直接忽略掉 | 当内容会产生假增量；直接忽略会让「上游活着但没说话」与「上游断了吗」在类型上不可区分 |
| 现在就建 `ToolCallDelta` | 没有可复现的端点，形状是猜的；猜出来的类型一旦落地就很难改 |
| `core` 里用 `Mono` / `Flux` | `core` 依赖树会出现 Reactor，[0003](./0003-module-structure-and-dependency-direction.md) R1 判红 |
| Passthrough 存解析后的对象 | 需要 Jackson；而 `core` 不许依赖它 |

## 后果

**正面**

- 加协议只加一侧；这条可被测试直接验证
- `core` 可以在没有容器的情况下单测：领域模型不依赖任何框架
- 终态是类型系统的一部分，「一条流没有结果」这类缺陷在编译期就少了一条路

**负面**

- 多一次翻译：入站一次、出站一次。这是 N+M 的固有成本
- Passthrough 是文本，用它之前要再解析一次（高频路径上的额外开销，可控但要意识到）
- Canonical 覆盖不到的厂商特性只能走 Passthrough，而 Passthrough 里的东西**网关不解释** ——
  任何需要理解某个字段才能生效的功能（比如按内容脱敏）都不能只靠它

## 如何验证

- [x] 新增一个 Provider 时 `faces` 包零改动（`SecondProviderTouchesNoFaceTest`）
- [x] `CHUNK_PARSE_COUNT` 恒为 1（`ChunkParseCounter` 断言；换错分帧器时会变成 0 并被捕获）
- [x] `core` 的依赖树中不出现 Spring / Jackson / Reactor（enforcer + 结构规则 R1）
- [x] 对外 wire format 由 golden-file 契约测试锁定（`contract/` 与 `contract-ollama/`，一个用例一个目录，放两个文件即被发现）
- [x] 流式契约比的是**帧序列**且包含 `[DONE]` 哨兵本身（`contract-stream/`）
- [ ] 出现 `ToolCallDelta` 时，它必须由一份可回放的真实上游样本引入，不能凭协议文档推测
