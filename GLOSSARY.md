# GLOSSARY.md

本项目的领域词汇表。**coder agent 在输出中命名领域概念时必须用这里的词**（见 `docs/agents/domain.md`），避免同义词漂移。

> 状态说明：`[已定]` 定义已稳定；`[待定]` 尚未实现，定义可能随 ADR 演进。

---

## 一、网关拓扑：Face / Provider / Service

**Face**（入站协议面）
客户端与网关之间说的协议。一个 Face 负责一种 wire format 的 encode/decode。
例：`OpenAiFace`、`AnthropicFace`。
> 禁用：不要叫 controller / handler / endpoint —— 那些是 Spring 的实现细节。Face 是协议边界，不是 HTTP 路由。

**Provider**（出站模型提供方适配器）
网关与上游模型服务之间说的协议。一个 Provider 负责把 CanonicalRequest 翻译成某个厂商的 wire format，并把它的响应译回来。
例：`OpenAiCompatibleProvider`（覆盖 DeepSeek / vLLM / Ollama / GPUStack 等 OpenAI 兼容端点）、`AnthropicProvider`。

**Face 与 Provider 的关系**
两者通过 **Canonical** 中间表示解耦，形成 **N+M 扩展**而非 N×N 翻译。新增一个 Face 或 Provider 只需加一个类。

**Service**（逻辑服务名）
客户端可见的逻辑模型名，如 `chat`、`embedding`。一个 Service 下挂若干实例。

**ModelInstance**（实例）
一个 Service 下的具体后端：`id` + `Endpoint`（baseUrl / 凭证 / 超时）+ `ProviderKind` + 权重 + 启用标志。
> 禁用：不要叫 backend / node / replica。

**Endpoint 的取值来源**
`Endpoint` 的 baseUrl 与凭证**只从环境变量读取**（`AIROUTER_ENDPOINT_BASE_URL` / `AIROUTER_ENDPOINT_API_KEY` / `AIROUTER_ENDPOINT_MODEL`），
配置文件里出现的只能是占位符，任何受跟踪文件中都不出现真实凭证。

**base-url 的 `/v1` 约定**
地址必须以 `/v1` 结尾且不带尾斜杠。Spring AI 2.x 不再自动补这个后缀：
少了它不在启动期报错，只会变成一次运行期的 404，因此它是一条**启动期校验**，在 `EndpointProperties` 里判红。

**档位**（local / cloud）
同一份代码运行在哪一类环境上：`local` 指向本机 OpenAI 兼容端点（Ollama），无网络也能起来；
`cloud` 指向真实厂商端点，三个变量全部必填。切换只改 profile 或环境变量，**不改代码**。
> 禁用：不要按 env / prod / dev 这类与环境生命周期绑定的词称呼它 —— 那是部署语义，不是端点语义。

**ProviderKind**
实例的协议种类枚举：`OPENAI_COMPATIBLE`、`ANTHROPIC`。决定用哪个 Provider 适配器。

**Adapter** [旧词]
`legacy/` 里用来同时指代"入站面"和"出站适配"的词，语义含糊，新版**不再使用**。见到 legacy 代码里的 `Adapter`，先判断它实际是 Face 还是 Provider。

---

## 二、中间表示：Canonical

**CanonicalRequest**
网关内部的请求中间表示。与任何厂商的 wire format 无关，字段是我们自己的领域模型。
所有 Face 都必须先译到 CanonicalRequest，所有 Provider 都只接受 CanonicalRequest。

**CanonicalResponse**
一次完整（非流式）调用的中间表示。

**CanonicalStreamEvent**
流式调用的事件中间表示，`sealed interface`。概念层是四态（**Opening / Delta / Terminal / Failed**），
实现层的子类型：

| 子类型 | 概念层 | 含义 |
|---|---|---|
| `Opening` | Opening | 首个事件，携带 id / model / created |
| `TextDelta` | Delta | 文本增量，携带候选序号与这一帧的文本 |
| `Usage` | Delta | 纯用量帧。厂商常单独发一帧用量，那一帧里没有内容增量 |
| `Ping` | Delta | 上游的保活注释。**不是终态判断的依据**：只发注释的上游是活的但没说话，与「连接已断」是两回事 |
| `Terminal` | Terminal | **正常终态**，携带 FinishReason 与 Usage |
| `Failed` | Failed | **异常终态**，携带 GatewayError |

> `ToolCallDelta` 尚未引入：工具调用的增量形态取决于上游真实怎么发，而现阶段没有能稳定复现它的端点。
> 与其先画一个未经验证的类型，不如等有了可回放的上游再补 —— 那时它是被样本钉住的。
> 同理，`Ping` 保留为显式类型是为了让「保活帧不是内容、也不是终态」这件事在类型上可见。

**Canonical / Unified / IR**
同义词。**本项目只用 Canonical**，另外两个词不要出现在代码与文档里。

**wire format**
协议在传输层上的实际字节/JSON 形状（OpenAI 的 JSON Schema、Anthropic 的 SSE event 类型等）。翻译层的全部工作就是 Canonical ↔ wire format 的互转。

**Passthrough**（透传位）
Canonical 上用来装「网关不解释但必须转发」的字段的容器，`Map<String, String>`，值是 JSON 原文。
它挂在三个层级：响应顶层、每个候选、每条消息、以及 usage 内部 —— 厂商在这些位置挂私有字段都很常见。
> 值是原文而不是解析后的对象：core 不许依赖 Jackson，所以只能是文本；解码时再解析一次。

**CompletionPort**（完成端口）
Face 与 Provider 之间的**非流式**端口：Face 只认识它，Provider 实现它。返回 `CompletionStage<CanonicalResponse>`。
> 不返回 `Mono`：Reactor 进入 core 的依赖树会被 enforcer 判红，因此用 JDK 类型表达「异步结果」。

**StreamingCompletionPort**（流式完成端口）
同一个边界上的流式端口，返回 `java.util.concurrent.Flow.Publisher<CanonicalStreamEvent>`。
契约由实现方保证：**一条流有且只有一个终态，且它是最后一个事件**；
流不以 error 信号结束（失败先在事件层变成 `Failed`）。
> 不返回 `Flux`，理由同 `CompletionPort`。两侧各用一次 `JdkFlowAdapter` 转换即可接上。

**GatewayError**
网关自己定义的错误形态（`type` / `message` / `httpStatus`）。从传输层故障到上游 4xx/5xx，
最终都会收敛成一个 GatewayError 到达 Face，让错误只有一处收口。

**UpstreamFailure**
携带 {@code GatewayError} 的失败。它是 unchecked 的：调用链上的中间层都不打算处理它，
只有 Face 在边界上收口一次。

---

## 三、流式管道

**TerminalEvent**（终态事件）
一次流式调用的结束语信号，`Terminal` 或 `Failed` 二者之一。**必须显式发出**，不允许依赖 Reactor 的 `onComplete` / `onError` 隐式推断。
> 这是修复"流式绕过熔断器"的关键机制。见 §五 **OutcomeObserver**。

**Opening / Delta / Terminal / Failed**
概念层的四态分层，见 CanonicalStreamEvent 表。写代码时用表里的**实现层子类型**，
讨论协议语义时用这四态。

**IdleTimeout**（块间空闲超时）
两帧之间的最大静默时长，超过即判超时。**不是总时长**。与之相对：

**ResponseTimeout**（总响应超时）
整个响应从发起到结束的上限。⚠️ **对 `text/event-stream` 的下游响应禁止设置**，否则长流会被基础设施掐断（legacy 在 60s 处踩过）。

**SLO 三角**
流式资源的三个明确边界：`idleTimeout` × 最大累积内容 × 入站/出站缓冲区。三者必须同时显式配置。

**Backpressure**（背压）
消费者消费速度低于生产者时的压力传导。网关处于中间位置，**必须向两端传导**：对下游用 request(n)，对上游受控后才能继续。

**Cancellation**（取消）
客户端断连。必须传播到下游连接并释放资源；同时它是一个**终态**，需要结算（但不能算作失败）。

**BoundedAccumulator**（有界累积器）
流式过程中累积 content 的缓冲区，带上限，超限则截断。关键性质：**截断影响展示，不影响计费** —— token 估算走独立的字符计数轨道。

---

## 四、路由与流量治理

**SelectionStrategy**（实例选择策略）
`RANDOM` / `ROUND_ROBIN` / `WEIGHTED` / `LEAST_ACTIVE` / `FIRST_AVAILABLE`。

**InstanceSelector**
从候选实例中选一个。纯函数式决策，**不做 IO**。

**Availability**（可用性）
实例是否可以被选中 = 启用 ∧ 健康检查通过 ∧ 熔断器未 OPEN ∧ 限流未拒绝。
> 注意：legacy 里同一个 handler 对"健康"的默认值自相矛盾（一处默认健康、一处默认不健康），新版只有一条判定路径。

**CircuitBreaker**（熔断器）
按实例粒度的状态机：`CLOSED` → `OPEN` → `HALF_OPEN` → `CLOSED`。OPEN 期间请求不被选中。

**CircuitState**
`CLOSED` / `OPEN` / `HALF_OPEN`。

**HalfOpen**
OPEN 冷却结束后的试探态，放行有限数量的探测请求，决定是否回到 CLOSED。

**RateLimiter**（限流器）
按维度（API Key / Service / 实例 / 全局）限额。算法包括 `TokenBucket`、`SlidingWindow`、`LeakyBucket`、`WarmUp`。

**SlidingWindow** / **TokenBucket**
限流算法实现，可插拔到同一个 `RateLimiter` 端口下。

**Fail-closed / Fail-open**
治理组件自身故障时的默认行为。**本项目默认 fail-closed**：限流器自己挂了应当拒绝而非放行。确需 fail-open 必须写进 ADR 并说明补偿措施。
> legacy 的配额在 Redis 故障时默认 fail-open → 限额整体失效，这是新版必须修掉的。

**Single-flight**（单飞）
同一 key 的同一操作在同一时刻只允许一个执行者，其余跳过或等待。用于高并发下的压缩、结算、刷新场景，避免 CAS 无限重试。

**OutcomeObserver**（结果结算点）
**唯一的**成功/失败/取消观测点。所有协作组件（熔断器、延迟感知负载均衡、指标、配额结算）都只从这里取结果，**不得各自观测**。
> 这是修复 legacy P0-1 的架构手段：流式只要在 Terminal/Failed/Cancel 时才到达这里。

**Outcome**
一次调用的结算结果：`requestId` / `serviceId` / `instanceId` / `OutcomeKind` / `latency` / `usage` / `error`。
`latency` **一律算到收到终态为止**，这让流式与非流式的延迟具有可比性。

**OutcomeKind**
`SUCCESS` / `UPSTREAM_FAILURE` / `UPSTREAM_TIMEOUT` / `CLIENT_CANCELLED`，互斥且穷尽。
熔断口径：前两者计入熔断，`CLIENT_CANCELLED` **不计入** —— 移动端切后台、用户关页面都会产生取消，
把它算作上游失败会让一次共享网络的抖动熔断掉一个健康的实例。

**SSRF 守卫**
出站请求的双层地址校验：创建期校验 + 连接期再判 DNS 解析结果（堵 DNS rebinding）。**所有出站必须经过唯一出口**，不允许散落的 WebClient。

---

## 五、Agent 层

**ChatClient**
Spring AI 的请求入口，负责编排 Advisor 链。

**Advisor**
挂在 ChatClient 请求管线上的横切处理单元，`getOrder()` 决定顺序，不调用 `chain.next()` 即短路。

**CallAdvisor / StreamAdvisor**
两个**独立**接口。任何有安全语义的 Advisor 必须**同时实现两者**，否则流式路径被静默跳过且不报错。

**Tool**
模型可调用的能力单元，`@Tool` 注解声明。

**Subagent**（子 Agent）
在**独立上下文窗口**中运行的专职 Agent，由主 Agent 通过 Task 工具委派，只把结论返回主 Agent。定义即一个 Markdown 配置文件。

**Orchestrator**（编排 Agent）
负责任务分解与委派的主 Agent。本项目主线是 **Ops Agent**，见下。

**Ops Agent**
本项目的主 Agent。运维场景：查实例健康 / 熔断限流状态 / 调用历史 / 异常事件 / 配置变更审计。**工具的数据源就是本网关自己的可观测数据**。

**Session**
结构化、事件溯源的短期记忆单元，取代扁平的消息列表。参考实现：`spring-ai-session`。

**Turn**（轮次）
一个 UserMessage + 其后所有事件，直到下一个 UserMessage。**压缩的最小原子粒度** —— 保证保留窗口永远从用户消息开始，不会切断工具调用序列。

**Compaction**（压缩）
会话历史的收缩，由 Trigger（何时压）+ Strategy（怎么压）成对驱动。两者必须同时设置。

**Branch**（分支标签）
多 Agent 场景下隔离事件流的标签。必须用常量/枚举管理，禁止手写字符串。

**A2A**
Agent2Agent 协议，跨系统 Agent 互操作，通过 AgentCard 发现。

**MCP**
Model Context Protocol。注解在 Spring AI 2.x 中已从社区包移入核心。

---

## 六、质量保证

**Golden-file Contract Test**（契约测试）
用真实的上游请求/响应样本做快照比对，锁定 wire format 的完整行为（含边界：跨 chunk 的 UTF-8 截断、空[data]帧、错误帧不补 [DONE] 等）。
**这是未来任何框架升级的唯一安全网。**

**Property-based Test**
对翻译层的纯函数用生成式输入断言不变量（如"任意 chunk 序列切分方式，合并结果唯一"）。

**CHUNK_PARSE_COUNT**
一次流式 pipeline 中同一个 chunk 被解析的次数。**验收指标：必须恒为 1。** 允许同一封装有多 reader，不允许重复 parse。

> 背景见 `docs/planning/legacy-review.md`：旧版 Anthropic 流式路径对同一个 chunk 做了 4 次 `readTree` + 2 次 `writeValueAsString`。

---

## 术语缺口

本表未覆盖、但你认为是真实概念的，先记在这里，由 `domain-modeling` 统一裁决，不要自行发明词。
