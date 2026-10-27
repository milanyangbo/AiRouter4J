# 03: 流式打通（正常路径）

**What to build:** 一条流式请求以 SSE 逐块拿到内容，并收到 `[DONE]` 哨兵；
终态是这条流的最后一个事件。

本票只走**正常路径** —— 上游好好说话、好好结束。异常终态（不讲完、静默、取消）
是 04 的事，分开是为了让「正常路径正确」与「异常路径被判对」各自能单独定位。

**Blocked by:** 02

**Status:** done（commit 见 `git log`，tag `baseline-2`）

## Acceptance criteria

- [x] `CanonicalStreamEvent` 是 sealed，实现层六型（`Opening` / `TextDelta` / `Usage` /
      `Ping` / `Terminal` / `Failed`）。`ToolCallDelta` **不引入** ——
      工具调用的增量形态取决于上游真实怎么发，现阶段没有能稳定复现它的端点
- [x] `StreamingCompletionPort` 返回 `Flow.Publisher`（不是 `Mono` / `Flux`，
      Reactor 进 `core` 的依赖树会被 enforcer 判红）
- [x] SSE 分帧器就位（`transport/SseFramer` + `SseFrame`）
- [x] SLO 三角（`idleTimeout` × 最大累积内容 × 入站/出站缓冲区）三者**全部显式配置**
- [x] 流式请求逐块收到内容，并收到 `[DONE]`
- [x] 终态是这条流的**最后一个**事件；终态之后的帧不出现在客户端
- [x] `contract-stream/` 与其 README 就位，比的是**帧序列**且包含 `[DONE]` 哨兵本身
      （哨兵的存废是一个被断言的事实）
- [ ] 流式与非流式的**延迟可比** —— 两者都算到收到终态为止
      → **推迟到 04**：可比性比的是 `Outcome.latency`，而结算（含 `StreamState.claim()`）
      是 04 的内容。本票把「终态是最后一个事件」钉住了，结算时刻因此有了着落
- [x] 背压向两端传导（见下，断言的是「上游还没讲完时客户端已经收到了前面的块」）
- [x] 非流式与流式走**同一条路径**（流式不绕过治理）
- [x] 对 `text/event-stream` 的下游响应**不设** `ResponseTimeout`
      （否则长流会被基础设施掐断）
- [x] `idleTimeout` 是**块间**时长，不是总时长 —— 一条想很久的流不会被误杀

## 落地 shape

**core** —— `CanonicalStreamEvent`（sealed，六型；`Opening` 带 passthrough，
`created` 与厂商私有字段靠它回到客户端每一帧）、`StreamingCompletionPort`
（返回 `Flow.Publisher`）。**依赖树没变**：core 仍然只有 JUnit + AssertJ。

**gateway** ——
- `transport`：`SseFrame`（`Data` / `Comment` 两型）+ `SseFramer`。
  分帧器**在字节层缓存**：一行没攒够就留着字节，攒够一行才解码。
  UTF-8 的多字节序列里不可能出现 `0x0A`，所以「一个中文字符被劈在两个 TCP 段之间」
  天然被解决 —— 不需要任何特殊处理
- `OutboundWire.postStream(...)`：`Flux<DataBuffer>` → 分帧 → `Flux<SseFrame>`。
  时间约束只有 `idleTimeout` 一个，**不套**非流式那个 `responseTimeout`
- `providers/openai`：`OpenAiUpstreamCodec.StreamDecoder`（一帧 → 哪些事件，按流创建，
  因为「哪一块是开场」只有这条流知道）；`OpenAiCompatibleProvider` 同时实现两个端口，
  按 译码 → 截断 → 终态处截断 的顺序组合，`takeUntil(终态)`
- `faces/openai`：`OpenAiInboundCodec.StreamEncoder`（事件 → SSE 载荷，按流创建，
  因为 id / model / `created` 只在开场那一帧出现一次）；`OpenAiFace` 在同一个入口按
  `stream` 字段分派，**不改 `Accept`**

**config** —— `StreamProperties`（`airouter.stream.*`，五个值，非正数启动期判红）。
入站缓冲区经一个 `WebFluxConfigurer` 落到 `ServerCodecConfigurer`，
出站缓冲区进 `WebClient` 的 `ExchangeStrategies`。

## 验证

`./mvnw clean verify` 全绿：core 10 / gateway 14 / app 51（流式端到端 11）。

**故意违反实测过两处**，都确认会红：

1. 在 `postStream` 上加 `.take(responseTimeout)`（一个**总时长**窗口）
   → `idleTimeoutIsBetweenChunksNotTotal` 在 2.01s 处红。
   ⚠️ 第一次探针写的是把 `.timeout(idleTimeout)` 换成 `.timeout(responseTimeout)`，
   **没红** —— Reactor 的 `timeout(Duration)` 本身就是块间语义（每来一块重新计时），
   换一个值仍然是块间。「用 Reactor 的 timeout 做探针」得不出「总时长上限」的结论
2. 去掉 `takeUntil(终态)` → 5 条红（端到端那条 + 4 组 `contract-stream/` 基线）

**可执行 jar 的冒烟**（指向本机 Ollama `127.0.0.1:11434/v1`）：真实流式请求返回
`text/event-stream`，逐块到达，`created` 与 `system_fingerprint` 原样回到客户端，
末帧 `finish_reason:stop` 之后是 `data:[DONE]`。
顺带确认了一件事：**真实 Ollama 的 SSE 是以空行收尾的**，所以「未以空行结束的那一段
不被分派」（SSE 的规定）不会误伤正常流。

## 两处值得记住的判断

- **两个端口、一个对象。** 想让 `CompletionPort` 与 `StreamingCompletionPort`
  各由一个 `@Bean` 交出去（同一个对象按两个接口），结果按类型注入时**两个 bean 同时匹配
  两个接口**（同一个类实现了两个端口）→ 二选一报错。最终只留一个 `@Bean`（具体类），
  注入点看到的仍然是接口。写成一个 bean 而不是建两次，是为了让「流式那条路
  不可能单独少挂一样东西」在装配代码里也成立
- **哨兵是 codec 的事，不是传输层的事。** `SseFrame` 只有 `Data` / `Comment` 两型，
  `[DONE]` 的辨认在 `providers/openai` 的 codec 里 —— 传输层认下哨兵，
  就等于替某一家的协议做决定（ADR-0006 §5）
