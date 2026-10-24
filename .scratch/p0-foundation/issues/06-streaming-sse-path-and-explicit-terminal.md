# 06: 流式 SSE 路径与显式终态

> 来源：[spec.md](../spec.md) — 用户故事 2、3、15、24

**What to build:** `stream=true` 的请求以 SSE 逐帧返回内容，流以显式发出的 TerminalEvent 后跟 `[DONE]` 收尾；任何一条流有且只有一个终态；一次处理过程中同一份上游字节只被 JSON 解析一次。

终态必须显式发出是 ADR-0006 的核心。禁止依赖响应式流的完成信号去推断结果 —— 上游完全可能在发出结束哨兵之前就关闭连接，那时的完整信号与失败长得一模一样。

**Blocked by:** 05 非流式端到端打通

**Status:** done

- [x] `stream=true` 时调用方在读取过程中观察到多次帧到达，而不是等到最后收到一个拼好的整体
- [x] 流转义过程中不丢内容：逐帧拼接后的文本与上游产出的完整文本一致（含多字节字符被切在帧边界的情形）
- [x] 每一条流都以恰好一个 TerminalEvent 结束，其后跟上 `[DONE]`
- [x] 终态缺失是不可能的：上游在未发出结束哨兵的情况下关闭连接时，由框架补发 Failed，而不是让流静默结束
- [x] 终态是显式事件，不由响应式流的完成信号推断 —— 用一个「正常发完内容但刻意不发结束哨兵」的上游来销毁这种推断
- [x] 延迟反映的是到收到终态为止的真实耗时，流式样本不再恒为零
- [x] CHUNK_PARSE_COUNT=1：同一份上游字节在一次处理流程中只被 JSON 解析一次，由可观测计数断言
- [x] IdleTimeout 有配置项且有合理默认值
- [x] 流式 wire format 被纳入 golden-file 基线，包含 `[DONE]` 哨兵本身

## 完成记录

### 落在哪里

| 位置 | 内容 |
|---|---|
| `core` | `CanonicalStreamEvent`（sealed：Opening / TextDelta / Usage / Ping / Terminal / Failed）、`StreamingCompletionPort`（返回 JDK `Flow.Publisher`）、`Outcome` / `OutcomeKind` / `OutcomeObserver`、`UpstreamFailure` 增补 `kind()` |
| `gateway/transport` | `SseFrameReader`（字节层分帧）、`OutboundWire.postStream`、`UpstreamRejection`（只报告上游的非 2xx，不判断） |
| `gateway/providers` | `ChunkParseCounter`、`OpenAiCompatibleProvider.stream()`（显式终态 + 兜底 + 结算上报） |
| `gateway/faces` | `OpenAiInboundCodec.encodeStreamEvent`、`OpenAiFace` 按 `stream` 字段分派并输出 `text/event-stream` |
| `gateway/observe` | `RecordingOutcomeObserver`（结算点的第一个消费者） |
| `gateway/config` | `EndpointProperties.idleTimeout`、`UpstreamConfiguration` 装配 |
| 测试 | `contract-stream/`（3 组 golden 基线）、`GoldenStreamContractTest`、`StreamingEndToEndTest`、`LocalModelStreamingEndToEndTest`、`SseFrameReaderTest`、`EndpointPropertiesValidationTest` 增补 |

### 几处决定，以及它们为什么是这样

- **终态兜底放在 Provider 而不是 transport**：只有 Provider 知道上游的哨兵长什么样。
  transport 那边能看到的只是「连接没了」，它无从判断这是不是终态。
- **`UpstreamRejection` 只带状态码与原文，不带判断**：4xx 透传 / 5xx 收敛 502 是网关策略，
  由 Provider 一处决定，流式与非流式共用同一个 `clientError(status, body)`。
- **切分发生在字节层、解码发生在事件完成之后**：一个多字节字符完全可能被 TCP 分段切成两半，
  先解码再切会得到替换字符，而客户端看到的是「模型偶尔吐乱码」，日志里没有任何痕迹。
- **端口返回 JDK `Flow.Publisher`**：与 `CompletionPort` 用 `CompletionStage` 同理。
  两侧各用一次 `JdkFlowAdapter` 转换（`java.util.concurrent.FlowAdapters` 在 JDK 里并不存在）。
- **`[DONE]` 是无条件追加的**：它不是「转述上游的哨兵」，而是「我们确认有终态之后才发出」。
  依据来自端口契约 —— 一条流有且只有一个终态，且它是最后一个事件。

### 与 ADR / spec 的三处差异（都是实现时确定的，不是静默覆盖）

1. **ADR-0005 的事件子类型**：初稿列的 `ToolCallDelta` 暂缺（没有能稳定复现它的端点），
   另补了 `Opening`（id / model / created 属于整条流，不能挂在「第一个增量帧」上 —— 空内容流没有那一帧）。
   已回填 ADR-0005 与 GLOSSARY，GLOSSARY 的概念四态分层继续有效。
2. **ADR-0006 的 `Outcome.usage` 类型**：写的 `TokenUsage`，实现用 ADR-0005 的 `CanonicalUsage`。
   它已经能表达「上游没给就是没给」，为一个字段再定义一个同构类型只会多一次转换。已在 ADR 里标注。
3. **spec 里记录的验证缺口被补上了一部分**：spec 说「流成功开篇后中途静默断开」在真实端点这个 seam 下不可构造。
   `RecordedUpstream` 现在能控制到**字节块一级**（`replayStream(status, chunks, between)`），
   所以这类样本造得出来，本票就用它验证了「讲完但不发哨兵」。
   真实端点继续负责兼容性发现的职责，两者是补充关系不是替换。

### 反向验证（证明这些断言真的会红）

| 怎么改 | 结果 |
|---|---|
| 把「上游未发哨兵即关闭」当成成功（补发 `Terminal` 而不是 `Failed`） | `GoldenStreamContractTest` 与 `StreamingEndToEndTest` 各红一条 |
| 去掉 Face 末尾追加的 `[DONE]` | `StreamingEndToEndTest` 红 2 条，真实端点那条也红 |
| （票 05 的做法沿用）改 golden 基线里的任一字段 | 立刻红，并打印期望/实际的整齐缩进 |

### 留给后面的票

- **超时路径**（IdleTimeout 触发的 `Failed(UPSTREAM_TIMEOUT)`）：实现已经有了，断言在票 07
- **取消**：`CLIENT_CANCELLED` 只在 `OutcomeKind` 里留了位置，产生者还没接 —— 票 07
- **熔断器等结算消费者**：票 08；本票只建立了结算的数据出口（`RecordingOutcomeObserver`）
