# ADR-0009: 第二种 wire 形态与「形态自述」

- **状态**：已接受（第 2 版，2026-10-25 重写；已落地）
- **日期**：2026-10-25
- **相关**：[0001 目标态](./0001-project-scope-and-lineage.md)、[0005 Canonical](./0005-canonical-intermediate-representation.md)、[0006 流式终态](./0006-streaming-terminal-outcome-contract.md)

## 背景

只有一种上游形态时，有三条事实被**隐含在唯一那个 Provider 里**，写成了全局规则：

1. 地址必须以 `/v1` 结尾
2. 请求要带 `Authorization` 头
3. 流式是 SSE（`data:` + 空行 + `[DONE]`）

这三条都是 OpenAI 兼容端点的性质，不是网关的性质。只有一个 Provider 时看不出问题 ——
隐含与显式给出同一个答案。

第二个形态一出现，隐含的那部分立刻变成错的：Ollama 原生接口在 `/api` 上、不认凭证、
流式是**逐行 JSON** 而不是 SSE。而逐行 JSON 用 SSE 的读法一条事件都产不出来。

来源项目的对应处置是在 `adapter/` 下为每一家写一对 transformer（110 个相关文件），
其中 vLLM / GPUStack / Xinference / LocalAI 的 transformer 形状完全一致 ——
差异只是默认地址与少数字段名。这是「隐含规则」的另一条出路：用重复代替抽象。

## 决策

### 1. 形态自述：由形态自己回答问题

`UpstreamShape` 是一个枚举，但它不是配置的一个取值，而是**一组由形态自己回答的问题**：

| 问题 | 谁回答 |
|---|---|
| 地址该长什么样 | 形态（`addressPattern` + 人话版 `addressExpectation`） |
| 这个协议要不要凭证 | 形态（`needsApiKey()`） |
| 路径是什么 | 各自的 codec（`/chat/completions` vs `/api/chat`） |
| 帧怎么切 | 各自的**分帧器** |
| 一帧字节 → 哪几个事件 | 各自的 codec |

**刻意不让形态回答后两个问题**：把它们收进枚举会让枚举变成半个 Provider。
判据是「新增一种形态要改哪些地方」必须有一个可数的答案；反过来，出现第三个形态时
如果需要回到别处加 `if`，说明这里漏了问题。

未知取值**直接抛**而不是回落到默认形态：配错形态的后果是每一次调用都打在错误的协议上，
而「打错了还一直在打」比「起不来」难查得多。启动期是唯一合理的时机。

### 2. 分帧是并列的两种读法，不是一个读法的两种参数

| | SSE | 逐行 JSON（NDJSON） |
|---|---|---|
| 前缀 | `data:` | 无 |
| 分隔 | 空行 | 换行 |
| 结束 | `[DONE]` 哨兵 | 靠末帧内容（原生接口的 `done:true`） |
| 读法 | `SseFrameReader` | `LineFrameReader` + `OutboundWire.postLines` |

它们是 `transport` 里并列的两个类，**不是同一个类的参数**。
理由：差异在字节层就已经存在，用参数化的方式表达会让「这条流到底怎么结束」变成一个运行时分支，
而这个分支一旦判错，表现是「一条流永远不结束」。

这条被 `SecondProviderContractTest` 钉住：把逐行 JSON 的流交给 SSE 的读法，解析次数变成 **0** ——
不是报错，是一条事件都产不出来。

### 3. 形态差异唯一该存在的位置是 `providers/<形态>/`

「一帧字节 → 哪些事件」是形态差异的唯一归宿。`transport` 只在**分帧方式真的不同**时增加一种读法，
不为某个厂商加分支。

**措辞也属于形态。** 第二个 Provider 落地时踩过一次：改动错误文案动了第一个形态的 golden 基线。
结论是「上游在说完之前就把连接收了」这类措辞由 `ErrorExplainer` / 形态提供
（如 `prematureCloseMessage`），不在调用点硬写。

### 4. 装配按配置选，不在调用路径上判分支

`UpstreamConfiguration` 用 `@ConditionalOnProperty` 给每种形态一个 `@Bean`，调用路径上没有 `if`。
新增一种形态要动的地方只有四处：

1. `providers/<形态>/` —— codec 与 provider
2. `transport` —— **仅当**分帧方式不同时加一种读法
3. `UpstreamShape` —— 加一个取值
4. `UpstreamConfiguration` —— 加一个 `@ConditionalOnProperty` 的 `@Bean`

`faces` 与 `core` **不在这个列表里**。它们一旦出现，说明抽象体表错了 —— 应该回来修抽象，
而不是接受那份改动。`SecondProviderTouchesNoFaceTest` 用差异统计判红，红的时候会说清是哪个文件。

### 5. 加一家 OpenAI 兼容厂商是一份配置，不是一个类

vLLM / GPUStack / Xinference / LocalAI / DeepSeek 在 wire 上是同一种形态，
差异只在默认地址与少数字段名 —— 由配置承担。需要写代码的只有 wire 形态真的不同的那几家
（Anthropic Messages、Gemini `generateContent`）。

这条直接决定目标态里「9 个内置适配器」的对齐方式（[0001](./0001-project-scope-and-lineage.md) 能力清单）：
对齐的是**形态覆盖**，不是适配器数量。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 沿用隐含的全局规则，在 Provider 里特判 | 第二个形态一出现就错；且错误表现为「每次都打错」，难查 |
| 给每一家写一对 transformer（来源项目的做法） | 约 3.5k 行的重复，差异只在默认地址与字段名 |
| 把分帧做成参数（`framing=SSE|NDJSON`） | 「这条流怎么结束」变成运行时分支；判错的表现是一条流永不结束 |
| 把路径与分帧也收进 `UpstreamShape` | 枚举变成半个 Provider；新增形态的改动点变得不可数 |
| 未知形态回落到默认 | 打错了还一直在打，比起不来难查 |
| 在第二形态里直接改错误文案 | 动了第一形态的 golden 基线 —— 已踩过一次，措辞交形态 |

## 后果

**正面**

- 新增形态的改动点是可数的四处，且有一处（`faces` / `core`）**应当为零**并被测试判红
- 兼容厂商的接入成本从「写两个类」降到「加一份配置」
- 分帧错误会以「解析次数为 0」的形式被测出来，而不是以「偶发卡住」的形式在生产里出现

**负面**

- 形态枚举只回答两个问题，剩下的问题分散在 codec 与分帧器里 —— 找齐它们需要读两三个文件
- 「要不要凭证」是形态的属性而不是实例的属性：同一个形态下所有实例在这件事上一致。
  将来若出现「同形态但某实例需要凭证」的需求，这里要重开
- 逐行 JSON 没有显式结束哨兵，「说完了没有」依赖末帧内容 —— 上游不发末帧就关连接时，
  只能靠 [0006](./0006-streaming-terminal-outcome-contract.md) 的 `upstream_closed` 补发终态

## 如何验证

- [x] 新增第二个 Provider 后 `faces` 包与 `core` 模块的改动量为零（`SecondProviderTouchesNoFaceTest`）
- [x] 形态由配置选择，不在调用路径上判分支（`WireShapeIsChosenByConfigurationTest`）
- [x] 逐行 JSON 的流用 SSE 读法解析次数为 0（`SecondProviderContractTest`）
- [x] Ollama 原生形态端到端打通（`OllamaNativeEndToEndTest`，打本机真实端点，不做静默跳过）
- [x] 未知形态取值在启动期判红，不回落默认
- [x] 地址形态由形态校验，报错写成人话（`EndpointProperties`：`/v1` vs `/api`）
- [ ] 加一家 OpenAI 兼容厂商只改配置、不改代码（第三个形态落地时反向验证一次）
