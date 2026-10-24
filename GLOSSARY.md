# GLOSSARY.md

本项目的领域词汇表。**coder agent 在输出中命名领域概念时必须用这里的词**（见 `docs/agents/domain.md`），避免同义词漂移。

> 状态说明：**默认即已定**；标 `[待定]` 的是尚未实现的词 —— 定义可能随实施调整，但**用词不许换**。

---

## 一、网关拓扑：Face / Provider / Service / 实例

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
作用域类功能（限流 / 配额 / 规则路由）的 `service` 维度指的就是它。

**ModelInstance**（实例）
一个 Service 下的具体后端：`id` + `Endpoint`（baseUrl / 凭证 / 超时）+ 形态 + 权重 + 启用标志。
**同一个 Service 下的实例可以有不同形态** —— 一台走 OpenAI 兼容、一台走原生，是合法配置（ADR-0013 第 1 节）。
> 禁用：不要叫 backend / node / replica / provider。后两个尤其危险：`provider` 在本项目里指适配器实现。

**Endpoint 的取值来源**
`Endpoint` 的 baseUrl 与凭证**只从环境变量读取**，配置文件里只能是占位符，
任何受跟踪文件中都不出现真实凭证；凭证只在形态需要它时才必填（[ADR-0013](./docs/adr/0013-configuration-model-and-runtime-registry.md) 第 6 节）。
单实例档位的变量名是 `AIROUTER_ENDPOINT_BASE_URL` / `_API_KEY` / `_MODEL` / `_PROVIDER`，清单见 `.env.example`。
> `[待定]` 多实例下「按实例 id 派生变量名」的规则尚未定（落在 `EndpointProperties`，Phase A）。
> 在它定下来之前**配不出两个不同地址的实例** —— 现在写多实例配置只能配出同位地址的多份拷贝。

**base-url 的形态约定**
地址形态由**形态**决定：OpenAI 兼容端点以 `/v1` 结尾，Ollama 原生端点以 `/api` 结尾，都不带尾斜杠。
Spring AI 2.x 不再自动补这个后缀：少了它不在启动期报错，只会变成一次运行期的 404，
因此它是一条**启动期校验**，在 `EndpointProperties` 里按形态判红。
> 同一台本机 Ollama 两种地址都在（`/v1` 与 `/api`）—— 这正是形态必须由配置显式给出、不能按地址猜的理由。

**档位**（local / cloud）
同一份代码运行在哪一类环境上：`local` 指向本机 OpenAI 兼容端点（Ollama），无网络也能起来；
`cloud` 指向真实厂商端点，三个变量全部必填。切换只改 profile 或环境变量，**不改代码**。
> 禁用：不要按 env / prod / dev 这类与环境生命周期绑定的词称呼它 —— 那是部署语义，不是端点语义。

**wire 形态**（`UpstreamShape`；旧词 `ProviderKind` 指同一件事，新版只用「形态」）
对面**说哪一种话**。当前两种取值：`openai`（OpenAI 兼容端点）/ `ollama`（Ollama 原生接口）。
它自己回答三个问题：地址该长什么样、这个协议要不要凭证、以及（经由各自的分帧器）流怎么结束。
**形态是实例的属性，不是全局设置**：每个 `ModelInstance` 声明自己说哪一种话。
单实例档位下 `airouter.endpoint.provider` 给出的是那个默认实例的形态；装配按实例进行，不在调用路径上分支。
> 禁用：不要叫 provider type / vendor —— 「形态」说的是协议，不是厂商身份：
> 同一个厂商可以同时提供两种形态（本机 Ollama 正是如此，见 [ADR-0009](./docs/adr/0009-second-wire-shape-and-shape-self-description.md)）。

**逐行 JSON**（NDJSON）
一种流式分帧约定：每行一个完整 JSON，**没有** `data:` 前缀、**没有**空行分隔、**没有**结束哨兵，
由最后一帧的内容（原生接口的 `done:true`）宣告说完。
它与 **SSE** 是并列的两种约定，不是同一种的变体：SSE 的读法读不出逐行 JSON 的流，一条事件都产不出来
（这一条被 `SecondProviderContractTest` 断言着 —— 换成 SSE 的读法，解析次数就变成 0）。
> 分帧方式属于形态而不属于传输层的参数：两种约定的差异在字节层就已经存在。

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
它同时是这条流的**最后一个**事件：上游在结束哨兵之后又发来的帧不出现在客户端。
> 这是修复"流式绕过熔断器"的关键机制。见 §五 **OutcomeObserver**。取消是唯一的例外，见 **Cancellation**。

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
客户端断连。必须传播到上游连接并释放资源；它**不产生事件** —— 接收方已经不在了，往一个已经断开的下游发事件既不成立也没有意义。
但它**仍然要结算**成 `CLIENT_CANCELLED`：少记一次与记成成功，对下游的熔断器是同一种误导。
> 它是唯一一种「有结算而无事件」的终态。终态唯一这条断言因此落在结算点上，不落在事件序列上。

**BoundedAccumulator**（有界累积器）
流式过程中累积 content 的缓冲区，带上限，超限则截断。关键性质：**截断影响展示，不影响计费** —— token 估算走独立的字符计数轨道。

---

## 四、路由与流量治理

**选择链**（selection chain）
一个请求从进来到出网之间**固定的一条**编排，六步，顺序被断言（[ADR-0007](./docs/adr/0007-traffic-governance-model.md) 第 3 节）：

```text
① 认证与租户识别（API Key / JWT → client 标识）
② 规则路由（条件匹配 → 目标服务，首命即止）
③ 可用性闸门（InstanceAvailability）
④ 实例选择（select，纯函数）
⑤ 出网调用（transport）
⑥ 失败转移（换实例重试，仅对可重试的失败）
```

它落在 `routing` 的**一处**编排里，不是散在若干个 handler 里 —— 「流式路径绕过了其中一步」
正是来源项目最贵的一个缺陷，而分散是它的成因。
> 禁用：不要叫 pipeline / chain of responsibility。前者与 Reactor 的流混淆，后者暗示每一步都能短路。

**SelectionStrategy**（实例选择策略）
目标态六种，取值与 legacy 对齐：`random` / `round-robin` / `least-connections` / `ip-hash` /
`consistent-hash` / `latency`（EWMA 延迟感知，权重 ∝ `1/(1+ewma)`）。
文档里出现过的 `WEIGHTED` / `FIRST_AVAILABLE` **不是独立策略**：权重是每个策略都会用到的参数；
「第一个可用」是 ③ 的语义（走到 ④ 时候选全都可用），单列出来只会多出一条永远选到同一个实例的路径。
> 数字以代码为准：legacy 的 `LoadBalancerManagementController` 对外报 **6 种**；
> `StickyLoadBalancer` 是包装器、`CachedWeightedSelector` 是辅助类，都不构成第 7 种。

**InstanceSelector**
从候选实例中选一个。纯函数式决策：**只看 ③ 筛过的候选与权重，不看健康、不做 IO**。
纯是为了可确定性断言 —— 给定候选与随机源，结果可复现，判「策略对不对」不需要起一个端点。

**Availability**（可用性）
实例是否可以被选中 = 被纳入健康管理 ∧ 熔断器未 OPEN ∧ 限流未拒绝。三个条件在**同一处**判定（`routing` 的 `InstanceAvailability`），
判不过的请求在本地结束，不产生出网调用，也不产生结算。
> 注意：legacy 里同一个 handler 对"健康"的默认值自相矛盾（一处默认健康、一处默认不健康），新版只有一条判定路径。

**租户**（tenant）
限流 / 配额 / 缓存键上的隔离维度，由 client 标识（API Key 或 JWT 的声明）退化而来。
一个租户可以持有多把 API Key；配额与缓存按**租户**算而不是按 key 算 —— 否则多签一把 key 就等于绕开限额。
> 禁用：不要叫 user / app / customer。`user` 是认证主体，租户是计费与限流主体，两者不必一一对应。

**失败转移**（failover）
把请求换到**同一 Service 的另一个实例**上再试一次。与「同实例退避重试」是两回事，两者会同时存在。
只对可重试的失败生效，**白名单必须显式写出来并被断言** —— 写成「除了取消都重试」就是把 4xx 也重试一遍，
于是「模型名写错」会变成一个要打满所有实例才返回的错误。
结算纪律：**只结算最终那一次**；中间尝试计入指标，但不重复扣配额（[ADR-0007](./docs/adr/0007-traffic-governance-model.md) 第 3 节 ⑥）。
> 禁用：不要叫 retry。混用之后「重试算几次结算」就没有答案了。

**InstanceHealth**（健康管理名单）
哪些实例被显式登记过。**未登记即不可用**（fail-closed）：配置漏项与"刚注册还没登记"落进同一侧，
表现为不可用，而不是静默放行。见 §四 **Fail-closed / Fail-open**。

**CircuitBreaker**（熔断器）
按实例粒度的状态机：`CLOSED` → `OPEN` → `HALF_OPEN` → `CLOSED`。OPEN 期间请求不被选中。
它**只消费 `Outcome`**：看不到状态码，也看不到异常类型。状态用 `AtomicReference + CAS` 无锁更新并带版本号，
迁移记录里带着触发它的那个 `requestId` —— 「什么时候开的」能回答，排查时有用的是「谁把它打开的」。

**CircuitState**
`CLOSED` / `OPEN` / `HALF_OPEN`。`OPEN` 在冷却结束之后被**读成** `HALF_OPEN`：
「可以再试一次」是时间推出来的事实，不需要先发一次请求来触发。

**RateLimitDecisionPoint**（限流决策点）
限流的**唯一**判定接口，挂在可用性那一道闸上，与熔断由同一条管道消费。
本阶段只有接口没有算法 —— 接口先定下来的价值是：将来那个算法只能长在这一处。

**HalfOpen**
OPEN 冷却结束后的试探态，放行有限数量的探测请求，决定是否回到 CLOSED。

**RateLimiter**（限流器）
按作用域 `global / service / instance / client` 限额（`client` 即**租户**）。
算法 `TokenBucket` / `SlidingWindow` / `LeakyBucket` / `WarmUp` 插在同一个端口下；
后端两级：Redis 原子 Lua → 本地降级窗口（有额度上限、有 TTL）。
**管理面限流不是另一套**：它是同一内核的另一条入口，作用域是管理面路径 / 管理员。

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

**SettlementObserver**（结算点在本阶段的形态）
容器里**唯一**一个 `OutcomeObserver` bean：留一份样本，并把结论投递给登记在它上面的消费者（熔断器）。
第二个消费者出现时是登记在这里，不是另起一个 bean —— 两个 bean 会让"注入一个 `OutcomeObserver`"变成二选一的歧义。
投递**互相隔离**：一个消费者抛异常不会让后面的消费者失去这次结果。

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

## 七、配置、存储与多副本

**注册表**（registry）
「配置 → 运行时对象」的**唯一**持有者：服务、实例、形态，以及挂在实例上的熔断状态、延迟样本、健康登记。
其它包不自己缓存这些东西 —— 一旦有两处持有，热重载必然漏改一处
（[ADR-0013](./docs/adr/0013-configuration-model-and-runtime-registry.md) 第 5 节）。
> 禁用：不要叫 ServiceRegistry / catalog / 目录。来源项目的 `ModelServiceRegistry` 是同一件事，
> 但它的实现同时承担了选择与粘性路由，那不是注册表该做的事。

**热重载**（hot reload）
配置变更后**不重建 Spring 上下文**，只替换注册表里的对象。重建会丢掉在途的流、熔断计数与延迟样本 ——
一次配置变更等于一次全站状态清零，那是重启，不是热重载。
在途请求继续用它开始时拿到的那一份对象，直到结束。

**配置版本**
每次配置变更产生的一个不可变快照，带版本号。它回答两个问题：现在生效的是哪一版、能回滚到哪一版。
多副本之间广播的是「变更事件 + 版本号」，**不是全量配置**。
> 来源项目的 `HttpConfigPushService` 把全量配置推给一个并不存在的端点，不搬。

**多副本**（multi-replica）
同一个进程跑多份，共享 Redis 与数据库。Redis 承载三件事：配置变更事件广播、
跨副本共享计数（限流 / 配额）、分布式锁。
> `[待定]` 是否需要一个独立的「多副本档位」尚未决定。倾向**不设**：档位说的是端点性质（local / cloud），
> 副本数是部署形态，两者正交；把它们塞进同一个开关会让「本机两副本」这种用法无处安放。

**管理面**（admin plane）
网关自己的配置与运维接口，与数据面（Face）分开。它是控制台的数据来源，也是 RBAC 的作用对象。
> 禁用：不要把它和「控制台」混用 —— 管理面是接口，控制台是页面。

**控制台**（console）
管理面的 Web 界面。**最后做**，且是适配 legacy 的 92 个 `.vue` 页面而不是重写
（[ADR-0001](./docs/adr/0001-project-scope-and-lineage.md) 第 5 节）；前置条件是管理面 API 契约已被契约测试钉住。

---

## 术语缺口

本表未覆盖、但你认为是真实概念的，先记在这里，由 `domain-modeling` 统一裁决，不要自行发明词。

**待裁决**

（空。ADR 第 2 版带来的三个词已裁决，见下表。）

### 已裁决（ADR 第 2 版）

| 词 | 裁决 | 落在 |
|---|---|---|
| 注册表（registry） | 入表。**不要**用 `ServiceRegistry` 这个类名 —— 它会把来源项目那层「注册表兼做选择」一起带进来 | §七 |
| 租户（tenant） | 入表，与作用域里的 `client` 指同一件事；正文用「租户」，键名与代码里用 `client` | §四 |
| 「多副本」独立成档位 | **暂不**。档位描述端点性质，副本数描述部署形态，两者正交；记为 `[待定]` 留在 §七 | §七 |
