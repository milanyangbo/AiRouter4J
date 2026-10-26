# GLOSSARY.md

本项目的领域词汇表。**coder agent 在输出中命名领域概念时必须用这里的词**，避免同义词漂移。

> 状态说明：**默认即已定**（设计已裁决）；标 `[待定]` 的是尚未裁决的词 —— 定义可能调整，但**用词不许换**。
> 代码当前为空（阶段 0 已清空），因此词条描述的是**目标态**，不是「现在实现了多少」。

---

## 一、网关拓扑：Face / Provider / Service / 实例

**Face**（入站协议面）
客户端与网关之间说的协议。一个 Face 负责一种 wire format 的 encode/decode。
例：`OpenAiFace`、`AnthropicFace`。
> 禁用：不要叫 controller / handler / endpoint —— 那些是 Spring 的实现细节。Face 是协议边界，不是 HTTP 路由。

**Provider**（出站模型提供方适配器）
网关与上游模型服务之间说的协议。一个 Provider 负责把 CanonicalRequest 翻译成某个厂商的 wire format，并把它的响应译回来。
例：`OpenAiCompatibleProvider`、`OllamaProvider`、`AnthropicProvider`、`GeminiProvider`。

**Face 与 Provider 的关系**
两者通过 **Canonical** 中间表示解耦，形成 **N+M 扩展**而非 N×N 翻译。新增一个 Face 或 Provider 只需加一个类。

**Service**（逻辑服务名）
客户端可见的逻辑模型名，如 `chat`、`embedding`。一个 Service 下挂若干实例。
作用域类功能（限流 / 配额 / 规则路由）的 `service` 维度指的就是它。

**ModelInstance**（实例）
一个 Service 下的具体后端：`id` + `Endpoint`（baseUrl / 凭证 / 超时）+ 形态 + 厂商 + 权重 + 启用标志。
**同一个 Service 下的实例可以有不同形态** —— 一台走 OpenAI 兼容、一台走原生，是合法配置。
> 禁用：不要叫 backend / node / replica / provider。后两个尤其危险：`provider` 在本项目里指适配器实现。

**形态**（`UpstreamShape`）
对面**说哪一种话**：`openai`（OpenAI 兼容）/ `ollama`（Ollama 原生）/ `anthropic`（Anthropic 原生）/ `gemini`（Gemini 原生）。
它自己回答三个问题：地址该长什么样、这个协议要不要凭证、以及（经由各自的分帧器）流怎么结束。
**形态是实例的属性，不是全局设置**：每个 `ModelInstance` 声明自己说哪一种话。
> 禁用：不要叫 provider type / vendor / kind。「形态」说的是协议，不是厂商身份 ——
> 同一个厂商可以同时提供两种形态（本机 Ollama 的 `/v1` 与 `/api` 正是如此）。
> 旧词 `ProviderKind` 指同一件事，新版只用「形态」。

**厂商**（vendor）
上游的身份：Ollama / vLLM / GPUStack / Xinference / LocalAI / OpenAI / Anthropic / Gemini / Deepseek，
以及在适配器声明里登记的任意 OpenAI 兼容厂商。
**厂商与形态正交**：vLLM / GPUStack / Xinference / LocalAI / Deepseek / OpenAI 都是 `openai` 形态，差异只在默认地址与少量请求/响应转换。
> 因此「新增一个 OpenAI 兼容厂商」是**配置**，不是新类（ADR-0009）。只有形态真正不同（Anthropic / Gemini 原生）才需要新代码。

**适配器声明**（adapter definition）
对「某一个 OpenAI 兼容厂商」的声明式描述：默认 baseUrl、路径、请求/响应转换点、是否需要凭证。
它存在配置里并可持久化到库，支持运行时新增 —— 这是「任意 OpenAI 兼容厂商」的落地方式。
> legacy 的 `AdapterDefinitionProperties` + `AdapterDefinitionPersistenceService` 就是这个机制，可搬。

**Endpoint 的取值来源**
`Endpoint` 的 baseUrl 与凭证**只从环境变量读取**，配置文件里只能是占位符，
任何受跟踪文件中都不出现真实凭证；凭证只在形态需要它时才必填（ADR-0010 第 6 节）。

**base-url 的形态约定**
地址形态由**形态**决定：OpenAI 兼容端点以 `/v1` 结尾，Ollama 原生端点以 `/api` 结尾，都不带尾斜杠。
Spring AI 2.x 不再自动补这个后缀：少了它不在启动期报错，只会变成一次运行期的 404，
因此它是一条**启动期校验**。
> 同一台本机 Ollama 两种地址都在 —— 这正是形态必须由配置显式给出、不能按地址猜的理由。

**档位**（local / cloud）
同一份代码运行在哪一类环境上：`local` 指向本机端点（Ollama），无网络也能起来；
`cloud` 指向真实厂商端点。切换只改 profile 或环境变量，**不改代码**。
> 禁用：不要按 env / prod / dev 这类与环境生命周期绑定的词称呼它 —— 那是部署语义，不是端点语义。

**逐行 JSON**（NDJSON）
一种流式分帧约定：每行一个完整 JSON，**没有** `data:` 前缀、**没有**空行分隔、**没有**结束哨兵，
由最后一帧的内容（原生接口的 `done:true`）宣告说完。
它与 **SSE** 是并列的两种约定，不是同一种的变体：用 SSE 的读法读逐行 JSON，一条事件都产不出来。
> 分帧方式属于形态而不属于传输层的参数：两种约定的差异在字节层就已经存在。

**Adapter** [旧词]
`legacy/` 里用来同时指代「入站面」和「出站适配」的词，语义含糊，新版**不再使用**。见到 legacy 代码里的 `Adapter`，先判断它实际是 Face 还是 Provider。

---

## 二、中间表示与端口

**CanonicalRequest**
网关内部的请求中间表示。与任何厂商的 wire format 无关。
所有 Face 都必须先译到 CanonicalRequest，所有 Provider 都只接受 CanonicalRequest。

**CanonicalResponse**
一次完整（非流式）调用的中间表示。

**CanonicalStreamEvent**
流式调用的事件中间表示，`sealed interface`。概念层四态（**Opening / Delta / Terminal / Failed**），实现层子类型：

| 子类型 | 概念层 | 含义 |
|---|---|---|
| `Opening` | Opening | 首个事件，携带 id / model / created |
| `TextDelta` | Delta | 文本增量 |
| `Usage` | Delta | 纯用量帧。厂商常单独发一帧用量，那一帧里没有内容增量 |
| `Ping` | Delta | 上游的保活注释。**不是终态判断的依据** |
| `Terminal` | Terminal | **正常终态**，携带 FinishReason 与 Usage |
| `Failed` | Failed | **异常终态**，携带 GatewayError |

> `ToolCallDelta` 尚未引入：工具调用的增量形态取决于上游真实怎么发，等有了可回放的上游再补 —— 那时它是被样本钉住的。

**Canonical / Unified / IR**
同义词。**本项目只用 Canonical**，另外两个词不要出现在代码与文档里。

**wire format**
协议在传输层上的实际字节/JSON 形状。翻译层的全部工作就是 Canonical ↔ wire format 的互转。

**Passthrough**（透传位）
Canonical 上装「网关不解释但必须转发」的字段的容器，值是 JSON 原文。
挂在响应顶层、每个候选、每条消息、以及 usage 内部。
> 值是原文而不是解析后的对象：core 不许依赖 Jackson，所以只能是文本。

**CompletionPort** / **StreamingCompletionPort**
Face 与 Provider 之间的端口。非流式返回 `CompletionStage<CanonicalResponse>`，
流式返回 `java.util.concurrent.Flow.Publisher<CanonicalStreamEvent>`。
> 不返回 `Mono` / `Flux`：Reactor 进入 core 的依赖树会被 enforcer 判红，因此用 JDK 类型表达异步结果。

**GatewayError**
网关自己定义的错误形态（`type` / `message` / `httpStatus`）。从传输层故障到上游 4xx/5xx，最终收敛成它，让错误只有一处收口。

**UpstreamFailure**
携带 `GatewayError` 的失败，unchecked。调用链上的中间层都不处理它，只有 Face 在边界上收口一次。

---

## 三、流式管道

**TerminalEvent**（终态事件）
一次流式调用的结束语信号，`Terminal` 或 `Failed` 二者之一。**必须显式发出**，不允许依赖 `onComplete` / `onError` 隐式推断。它同时是这条流的**最后一个**事件。
> 这是修复「流式绕过熔断器」的关键机制。

**IdleTimeout**（块间空闲超时）
两帧之间的最大静默时长，超过即判超时。**不是总时长**。

**ResponseTimeout**（总响应超时）
整个响应从发起到结束的上限。⚠️ **对 `text/event-stream` 的下游响应禁止设置**，否则长流会被基础设施掐断（legacy 在 60s 处踩过）。

**SLO 三角**
流式资源的三个明确边界：`idleTimeout` × 最大累积内容 × 入站/出站缓冲区。三者必须同时显式配置。

**Backpressure**（背压）
消费者消费速度低于生产者时的压力传导。网关处于中间位置，**必须向两端传导**。

**Cancellation**（取消）
客户端断连。必须传播到上游并释放资源；它**不产生事件**，但**仍然要结算**成 `CLIENT_CANCELLED`。
> 它是唯一一种「有结算而无事件」的终态。终态唯一这条断言因此落在结算点上，不落在事件序列上。

**BoundedAccumulator**（有界累积器）
流式过程中累积 content 的缓冲区，带上限，超限截断。**截断影响展示，不影响计费** —— token 估算走独立的字符计数轨道。

---

## 四、实例维度：注册表 / 选择链 / 策略

**选择链**（selection chain）
一个请求从进来到出网之间**固定的一条**编排，六步，顺序被断言（ADR-0013 第 3 节）：

```text
① 认证与租户识别（API Key / JWT → client 标识）
② 规则路由（条件匹配 → 目标服务，首命即止）
③ 可用性闸门（InstanceAvailability）
④ 实例选择（select，纯函数）
⑤ 出网调用（transport）
⑥ 失败转移（换实例重试，仅对可重试的失败）
```

它落在 `routing` 的**一处**编排里 —— 「流式路径绕过了其中一步」正是 legacy 最贵的一个缺陷，而分散是它的成因。
> 禁用：不要叫 pipeline / chain of responsibility。前者与 Reactor 的流混淆，后者暗示每一步都能短路。

**SelectionStrategy**（实例选择策略）
六种：`random` / `round-robin` / `least-connections` / `ip-hash` / `consistent-hash` / `latency`（EWMA 延迟感知）。

**加权**（weight）
**不是第七种策略**，是作用在每个策略上的修饰：候选先按权重展开再交给策略。
legacy 用 `CachedWeightedSelector` 在轮询与最少连接里各实现一遍；新版把它做成选择链里**独立的一步**，于是 6 个纯函数 × 1 个加权步骤覆盖全部组合。
> 文档里出现过的 `WEIGHTED` 作为独立策略**不予采用**。

**粘滞**（sticky）
把同一会话（按客户端标识或自定义键）持续引到同一实例。它是**包装器**而不是第七种策略 —— 包在任意策略之外。
> legacy 的 `StickyLoadBalancer` 就是包装器。可搬，但要搬成「包装器」而不是「策略」。

**InstanceSelector**
从候选实例中选一个。纯函数式决策：**只看 ③ 筛过的候选与权重，不看健康、不做 IO**。
纯是为了可确定性断言 —— 给定候选与随机源，结果可复现。

**Availability**（可用性）
实例是否可以被选中 = 被纳入健康管理 ∧ 熔断器未 OPEN ∧ 限流未拒绝。三个条件在**同一处**判定（`routing` 的 `InstanceAvailability`），
判不过的请求在本地结束，不产生出网调用，也不产生结算。
> legacy 里同一个 handler 对「健康」的默认值自相矛盾（一处默认健康、一处默认不健康），新版只有一条判定路径。

**InstanceHealth**（健康管理名单）
哪些实例被显式登记过。**未登记即不可用**（fail-closed）。

**失败转移**（failover）
把请求换到**同一 Service 的另一个实例**上再试一次。与「同实例退避重试」是两回事，两者会同时存在。
只对可重试的失败生效，**白名单必须显式写出来并被断言**。结算纪律：**只结算最终那一次**，中间尝试计入指标但不重复扣配额。
> 禁用：不要叫 retry。混用之后「重试算几次结算」就没有答案了。

**注册表**（registry）
「配置 → 运行时对象」的**唯一**持有者：服务、实例、形态，以及挂在实例上的熔断状态、延迟样本、健康登记。
其它包不自己缓存这些东西 —— 一旦有两处持有，热重载必然漏改一处（ADR-0010 第 5 节）。
> 禁用：不要叫 ServiceRegistry / catalog / 目录。legacy 的 `ModelServiceRegistry` 是同一件事，
> 但它的实现同时承担了选择与粘性路由，那不是注册表该做的事。

**热重载**（hot reload）
配置变更后**不重建 Spring 上下文**，只替换注册表里的对象。重建会丢掉在途的流、熔断计数与延迟样本 —— 那是重启，不是热重载。
在途请求继续用它开始时拿到的那一份对象，直到结束。

---

## 五、流量治理

**租户**（tenant）
限流 / 配额 / 缓存键上的隔离维度，由 client 标识（API Key 或 JWT 声明）退化而来。
一个租户可以持有多把 API Key；配额与缓存按**租户**算而不是按 key 算 —— 否则多签一把 key 就等于绕开限额。
> 禁用：不要叫 user / app / customer。`user` 是认证主体，租户是计费与限流主体，两者不必一一对应。

**RateLimiter**（限流器）
按作用域 `global / service / instance / client` 限额（`client` 即**租户**）。
算法 `TokenBucket` / `SlidingWindow` / `LeakyBucket` / `WarmUp` 插在同一个端口下；后端两级：Redis 原子 Lua → 本地降级窗口。
**管理面限流不是另一套**：它是同一内核的另一条入口，作用域是管理面路径 / 管理员。
> legacy 的业务限流与管理面限流是两套并存、零共享，这是新版必须合掉的一处。

**Fail-closed / Fail-open**
治理组件自身故障时的默认行为。**本项目默认 fail-closed**。确需 fail-open 必须写进 ADR 并说明补偿措施。
> legacy 的配额在 Redis 故障时默认 fail-open → 限额整体失效。

**CircuitBreaker**（熔断器）
按实例粒度的状态机：`CLOSED` → `OPEN` → `HALF_OPEN` → `CLOSED`。OPEN 期间请求不被选中。
它**只消费 `Outcome`**：看不到状态码，也看不到异常类型。状态用 `AtomicReference + CAS` 无锁更新并带版本号，
迁移记录里带着触发它的那个 `requestId` —— 排查时有用的是「谁把它打开的」。
`OPEN` 在冷却结束之后被**读成** `HALF_OPEN`：「可以再试一次」是时间推出来的事实，不需要先发一次请求来触发。

**响应缓存**（response cache）
对**确定性请求**（温度归零、无工具、请求体一致）缓存完整响应。可选开启，默认关闭。
缓存键必须包含**租户**维度（否则跨租户串数据）；只缓存非流式；键由规范化后的请求体哈希得出。

**配额**（quota）
按租户（可叠加服务 / 模型维度）在**多个时间窗口**上记账的额度：`MINUTE` / `HOUR` / `DAY` / `MONTH`。
默认关闭（`airouter.quota.enabled=false`）；开启时以 Redis 计数为主、本地窗口降级。
> 与限流的区别：限流管**瞬时速率**（保护系统），配额管**累计用量**（控制成本）。两者共用键前缀但语义不同，不合并。

**账本**（ledger）
配额的记账结构：一次调用先**预留**（reservation），终态时按实际用量**结算**（settlement），差值回补。
`usageStrict` 决定用谁的数字：严格模式只认上游回报的 usage，宽松模式允许本地估算兜底。
> 先预留后结算是为了让并发下的超额可判定 —— 只在结束时检查，N 个并发会同时通过。

**Outcome**
一次调用的结算结果：`requestId` / `serviceId` / `instanceId` / `OutcomeKind` / `latency` / `usage` / `error`。
`latency` **一律算到收到终态为止**，这让流式与非流式的延迟具有可比性。

**OutcomeKind**
`SUCCESS` / `UPSTREAM_FAILURE` / `UPSTREAM_TIMEOUT` / `CLIENT_CANCELLED`，互斥且穷尽。
熔断口径：前两者计入熔断，`CLIENT_CANCELLED` **不计入** —— 用户关页面都会产生取消，
把它算作上游失败会让一次网络抖动熔断掉一个健康的实例。

**OutcomeObserver**（结果结算点）
**唯一的**成功/失败/取消观测点。所有协作组件（熔断器、延迟感知、指标、配额结算）都只从这里取结果，**不得各自观测**。

**Single-flight**（单飞）
同一 key 的同一操作在同一时刻只允许一个执行者，其余跳过或等待。用于高并发下的压缩、结算、刷新场景。

**SSRF 守卫**
出站请求的双层地址校验：创建期校验 + 连接期再判 DNS 解析结果（堵 DNS rebinding）。**所有出站必须经过唯一出口**，不允许散落的 WebClient。

---

## 六、规则引擎

**规则**（rule）
一条条件路由规则：匹配条件 → 目标服务。条件维度包括**模型名、服务类型、请求头、客户端 IP、权重、实例标签**。
多条规则按优先级排序，**首命即止**。

**命中统计**（hit count）
每条规则被命中的次数。它是规则调试与权重调优的依据，因此**必须持久化**（否则重启即清零，看不出趋势）。

**服务类型**（service type）
请求的能力类别：`chat` / `embedding` / `rerank` / `tts` / `image` 等。它是规则的匹配维度之一，也决定走哪一套 Canonical 形态。

**实例标签**（instance tag）
打在实例上的自由标签，供规则与选择做筛选（如 `gpu:a100`、`region:sz`）。
> 与「权重」不同：标签是**筛**（在不在候选里），权重是**选**（在候选中的概率）。

---

## 七、记录治理

**调用历史档位**（history level）
调用历史记录到什么程度，三档：

| 档位 | 记什么 |
|---|---|
| `metadata` | 仅元数据：时间、服务、实例、租户、耗时、用量、成败。不记内容 |
| `summary` | 脱敏摘要：加上请求/响应的**脱敏后**摘要 |
| `full` | 加密全文：请求与响应**完整**记录，AES-256-GCM 加密后落库 |

默认 `metadata`。档位可按服务单独配置。

**脱敏**（sanitization）
按规则把敏感片段替换成占位符。规则覆盖手机号 / 邮箱 / 身份证 / 银行卡等内置类型，并支持自定义正则；规则可配置、可持久化。
> 脱敏失败的处理必须是 fail-closed（拒绝记录），不能「记原文」—— 那样脱敏配置写错就等于明文落库。

**加密全文**
AES-256-GCM。密钥来自环境变量，每条记录独立 IV，AAD 绑定记录主键（防止整条记录被替换）。
**解密端点仅 ADMIN 角色**，且解密动作本身要记审计。

---

## 八、可观测性

**指标**（metrics）
Prometheus 格式。计数 / 直方图 / 仪表三类；核心维度：服务、实例、形态、租户、成败。
指标是**告警的唯一载体**之一（另一个是结构化日志）。

**链路追踪**（tracing）
**用 OpenTelemetry，不自研**。legacy 有 94 个自研 tracing 类，那是它最贵的一处自建 —— 不搬。
span 覆盖：请求入口 → 选择 → 出网 → 终态。

**慢查询分析**
超过阈值的调用单独记录并聚合，阈值可配。它的价值在于**按实例聚合** —— 慢是实例的属性，不是请求的。

**错误追踪**（error tracking）
按错误类型聚合成异常事件，带首次/最近出现时间与样本。与指标的差别：指标回答「多少」，错误追踪回答「哪些」。

**安全告警**（security alert）
针对异常模式（凭据失效暴增、越权尝试、单租户突增）的告警。
**告警出口被有意限定为 Prometheus 指标 + 结构化日志** —— 仓内**不实现任何推送通道**（邮件 / Webhook / IM），请在 Alertmanager 或日志平台里接。

**告警冷却**（alert cooldown）
同一告警在冷却期内不重复触发。多副本下冷却状态必须共享（Redis），否则 N 个副本会各发一次。

---

## 九、持久化、多副本、管理面

**多副本**（multi-replica）
同一个进程跑多份，共享 Redis 与数据库。Redis 承载三件事：**配置变更事件广播**、**跨副本共享计数**（限流 / 配额）、**分布式锁**。
> `[待定]` 是否单列一个「多副本档位」。倾向**不设**：档位说的是端点性质（local / cloud），副本数是部署形态，两者正交。

**配置版本**
每次配置变更产生的一个不可变快照，带版本号。它回答：现在生效的是哪一版、能回滚到哪一版。
多副本之间广播的是「变更事件 + 版本号」，**不是全量配置**。
> legacy 的 `HttpConfigPushService` 把全量配置推给一个并不存在的端点，不搬。

**分布式锁**
用于定时任务与告警冷却。实现必须是可重入且带 TTL 与续期；锁丢失时的行为要被断言。

**管理面**（admin plane）
网关自己的配置与运维接口，与数据面（Face）分开。它是控制台的数据来源，也是 RBAC 的作用对象。
> 禁用：不要把它和「控制台」混用 —— 管理面是接口，控制台是页面。

**控制台**（console）
管理面的 Web 界面。**最后做**，且是**适配 legacy 的 Vue 3 页面**而不是重写；前置条件是管理面 API 契约已被契约测试钉住。

**API Key**
调用方凭据。库中只存哈希，明文只在创建时返回一次；支持启用/禁用与过期。
校验位置在入口统一处，不在各业务分支。

**RBAC**
角色（Role）与权限（Permission）模型。管理面接口按权限鉴权，**默认拒绝**（fail-closed）。
> 先做精简版：角色固定、权限到接口级。等控制台适配时再按 legacy 的全集对齐。

---

## 十、Agent 层

**ChatClient**
Spring AI 的请求入口，负责编排 Advisor 链。

**Advisor**
挂在 ChatClient 请求管线上的横切处理单元。`getOrder()` 决定顺序，不调用 `chain.next()` 即短路。

**CallAdvisor / StreamAdvisor**
两个**独立**接口。任何有安全语义的 Advisor 必须**同时实现两者**，否则流式路径被静默跳过且不报错。

**Tool** / **Subagent** / **Orchestrator**
`Tool` 是模型可调用的能力单元；`Subagent` 在独立上下文中运行、由主 Agent 委派；`Orchestrator` 负责任务分解与委派。
本项目的主 Agent 是 **Ops Agent**：运维场景，工具的数据源就是本网关自己的可观测数据。

**Session / Turn / Compaction**
会话历史的组织方式：`Turn` 是压缩的最小原子粒度；`Compaction` 由 Trigger + Strategy 成对驱动，两者必须同时设置。

**A2A** / **MCP**
Agent2Agent 协议（跨系统互操作）与 Model Context Protocol（工具接入）。

---

## 十一、质量保证

**Golden-file Contract Test**（契约测试）
用真实的上游请求/响应样本做快照比对，锁定 wire format 的完整行为（含边界：跨 chunk 的 UTF-8 截断、空 data 帧、错误帧不补 `[DONE]` 等）。
**这是未来任何框架升级的唯一安全网。**

**Property-based Test**
对翻译层的纯函数用生成式输入断言不变量（如「任意 chunk 序列切分方式，合并结果唯一」）。

**CHUNK_PARSE_COUNT**
一次流式 pipeline 中同一个 chunk 被解析的次数。**验收指标：必须恒为 1。**

**gate**
测试分三档：`unit` / `integration` / `architecture`，标准是启不启容器、出不出网。每个测试类必须标 `@Tag`。

---

## 术语缺口

本表未覆盖、但你认为是真实概念的，先记在这里，由 `domain-modeling` 统一裁决，不要自行发明词。

（空）
