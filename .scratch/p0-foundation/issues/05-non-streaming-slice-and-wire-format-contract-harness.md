# 05: 非流式端到端打通 + wire format 契约测试基础设施

> 来源：[spec.md](../spec.md) — 用户故事 1、5、7、8、29

**What to build:** 一条真实的 chat 请求从 OpenAI 兼容的 Face 进入，翻译成 `CanonicalRequest`，由 Provider 转发到真实模型端点，响应被翻译回来并以 OpenAI 兼容的 JSON 返回 —— 一个现成的 OpenAI 客户端库无需改动就能解析它。同时把 golden-file 对照基础设施就位。

这是本次 P0 的第一条真正的垂直切片：从 HTTP 进、从 HTTP 出，中间穿过全部四层。它同时也是后续从 `legacy/` 搬运代码的前提 —— Jackson 2 → 3 最危险的产物是「编译通过但序列化行为变了」，没有这组基线文件，那种变更无从发现。

**Blocked by:** 03 质量门禁、04 app 启动

**Status:** ready-for-human

## 验收项与落地情况

- [x] 向 `/v1/chat/completions` 发送 `stream=false` 请求得到 200，响应体可以被标准 OpenAI 客户端库直接解析
      —— 用**官方 `com.openai:openai-java` 客户端**（test 作用域）打我们的端点并解析成 `ChatCompletion`，
      不是自己写断言证明自己合规
- [x] 请求中携带厂商私有字段时被原样转发，上游响应的厂商独有字段也被完整透传
      —— 用 `vendor_hint` / `vendor_ext` 与上游的 `vendor_trace` / `vendor_reasoning` 双向断言
- [x] 每次响应都携带可用于排查的请求标识，成功路径与失败路径都出现
      —— `X-Airouter-Request-Id` 响应头；成功与 404 两条路径各有一条断言
- [x] 上游提供 usage 时如实返回；上游未给出 usage 时该字段缺失而不是被编造
- [x] golden-file 对照基础设施就位：给定录制的上游字节，产出的响应体与存储的期望值比对，
      不一致时构建失败并给出差异（打印期望与实际的整齐缩进，逐字段对）
- [x] 第一组基线覆盖：正常非流式响应、上游 4xx、上游 5xx
- [x] 新增一个用例不需要改任何代码，只需要放入输入与期望两份文件
      —— 目录发现；并有一条测试钉住「用例数 ≥ 3」，防的是目录被挪走后零用例还全绿
- [x] 上述全部断言都从入站 HTTP 边界做出，不 mock 任何内部组件
- [x] 是否要走 Canonical 中间表示这件事是可见的：Face 侧与 Provider 侧都不持有对方的 wire 结构知识
      —— `faces.openai.OpenAiInboundCodec` 与 `providers.openai.OpenAiUpstreamCodec` 各自维护自己那一侧的
      「认识哪些字段」清单，互不引用；结构规则 R3a/R3b 在每次构建上判红

## 建了什么

### core（纯 Java，无框架）

`CanonicalRequest` / `CanonicalMessage` / `CanonicalTool` / `GenerationParams` 与
`CanonicalResponse` / `CanonicalChoice` / `CanonicalUsage` / `CanonicalFinishReason`，
外加 `GatewayError`、`UpstreamFailure` 与端口 `CompletionPort`。

- **`CompletionPort` 返回 `CompletionStage` 而不是 `Mono`**：网关内部确实是异步的，但把 Reactor 写进 core
  等于让框架进入它的依赖树（那条边界由 core 自己的 enforcer 判红）。JDK 类型表达「异步结果」，实现侧自选。
- **`CanonicalFinishReason` 是值类型不是枚举**：网关必须容忍上游的取值。枚举遇到未知字符串只能抛异常或丢信息，
  两者都不合适（ADR-0004 冲突二）。未知取值原样保留并出境。
- **passthrough 挂在三层上**：响应顶层、每个候选、每条消息、以及 usage 内部。
  厂商往这些位置挂私有字段都很常见，只做顶层等于把「完整透传」打了折扣。
  值的类型是 `Map<String, String>`，值是 JSON 原文 —— core 不许碰 Jackson，所以只能存原文。

### gateway

| 包 | 内容 |
|---|---|
| `transport` | `OutboundWire`：唯一出站口，WebClient 在这里，入参是地址与超时等普通值（R5 不允许它认识业务包） |
| `providers.openai` | `OpenAiUpstreamCodec`（纯函数互转）+ `OpenAiCompatibleProvider`，覆盖所有 OpenAI 兼容端点 |
| `faces.openai` | `OpenAiFace`（`@RestController`，路径 `/v1/chat/completions`）+ `OpenAiInboundCodec` |
| `config` | `UpstreamConfiguration`：唯一把 Face 与 Provider 连起来的地方 |

`EndpointProperties` 加了 `requestTimeout`（GLOSSARY 的 Endpoint = baseUrl / 凭证 / 超时）。
校验用 `@NotNull` + 构造器里的正数检查：手上的 validator（Hibernate Validator 9）已经没有 Duration 范围约束了，
而「等到第一次调用才炸」正是要避开的失败形态。

### 测试

- `GoldenWireContractTest` —— 目录发现的 golden 对照（回放上游 + 真实 Provider + 真实 Face 编解码）
- `NonStreamingEndToEndTest` —— 入站 HTTP seam，六条：形状、双向透传、usage 缺省、4xx 透传、5xx 收敛、坏请求
- `LocalModelEndToEndTest` —— 打本机真实模型端点，断言形状不断言内容
- `StandardClientParsesResponseTest` —— 官方 SDK 直接解析

## 刻意的选择与它们的代价

1. **状态码：上游 4xx 原样透传，5xx 收敛到 502。** 前者是这一次的请求有问题（模型名不存在、参数不合法），
   客户端需要看见自己的错误；后者是上游自身故障，让客户端去区分是哪一种上游故障只会增加耦合。
2. **响应侧角色未知时按 ASSISTANT 保留正文。** 认错角色的代价远小于丢消息 ——
   后者表现为「模型突然不说话了」而无从追查。
3. **不补 `created`。** 上游给了就经 passthrough 原样回来，上游没给就不出现。
   合成一个时间戳等于编造上游没说过的话。
4. **`usage` 子字段缺失按 0 处理，不做 `total = prompt + completion` 的推导。** 推导出来的合计会被拿去核销，
   而事后没人能区分它是上游算的还是网关补的。
5. **测试作用域引入官方 OpenAI SDK。** 它不进运行时 classpath，网关侧不允许出现任何厂商 SDK。
   换来的是「标准客户端能否解析」由第三方判定。

## 反向验证（都真跑过）

| 注入的违约 | 结果 |
|---|---|
| 基线文件里改一个 `total_tokens` | golden 测试红，打印期望与实际的整齐缩进 |
| 新建一个用例目录、故意写错的期望 | 无需改代码即被发现并判红（证明「加用例不改代码」） |
| 从 `encodeResponse` 里去掉 `id` | **golden 与官方 SDK 两条同时红** —— 两个独立 gate 抓到同一个 wire 变更 |
| 给 core 加 `tools.jackson.core:jackson-databind` | validate 失败，报 `tools.jackson.core:jackson-databind:jar:3.1.7 <--- banned` |

最后一条顺带修掉一个旧缺口：R1 原来只写了 `com.fasterxml.jackson*`，而 Boot 4 已经把 databind 的坐标换到
`tools.jackson*` 之下 —— 那条规则对 Jackson 3 是失效的，且失效得毫无声音。

## 已知限制（记在这里，不藏）

- **每条消息里的未知字段能被转发，但 `messages` 以外的嵌套结构没有全部覆盖**：目前建模到「消息 / 候选 / usage」三层，
  更深层的结构若厂商往里塞私有字段，仍会被丢弃。需要时按同样的 passthrough 办法往下加。
- **`upstream-5xx` 这组的输入是手写的**：本机端点产生不了 5xx。它验的是状态码映射，不是厂商保真度。
- **`verify` 现在依赖本机模型端点在跑**（`LocalModelEndToEndTest` 与官方 SDK 那条）。
  端点没起来时测试会红并给出指路信息，不做静默跳过。安装与起停见 `docs/runbook/local-openai-endpoint.md`。
- **CHUNK_PARSE_COUNT=1 的计数断言还没做**：它属于流式 pipeline，留给接进流式的那一票（spec 故事 24）。

## 下一步

票 06 / 07：流式路径、`CanonicalStreamEvent` 与终态契约（ADR-0006）—— 那时 `CanonicalResponse` 会多出一条
「由事件序列折叠而来」的来路，Face 侧则要开始产出 SSE 帧。
