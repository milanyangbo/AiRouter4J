# 08: OutcomeObserver 结算与熔断器最小闭环

> 来源：[spec.md](../spec.md) — 用户故事 16、18、19、23

**What to build:** 连续的上游失败会让熔断器真的打开，打开之后的请求在本地快速失败而不是继续打到坏实例上；成败只有一个结算来源；未被纳入健康管理的实例不接收流量。为了让这些状态可被断言，顺带引入一个只读的运维查询面。

这条闭环修的是来源项目那个三跳缺陷：流式请求在写出响应头就算成功，于是实例只要还能返回半个 SSE 就永远健康，熔断器永不 OPEN。修法不是在散落的三处各补一句失败记录，而是让全链路只有 `OutcomeObserver` 一个结算点 —— 熔断器、健康管理、延迟统计都只消费 `Outcome`。

只读查询面（暴露每个实例的 CircuitState 与可用状态）不在原 spec 的 story 列表里，是为了让「熔断器真的开了」这件事能被机器判定才引入的。它后续也是运维层要消费的同一份数据。

**Blocked by:** 07 终态契约：失败、超时、取消三类结算

**Status:** done

- [x] 连续若干次上游失败后熔断器进入 OPEN，此后的请求在本地快速失败，不再产生针对上游的出网调用（同时断言状态读值与「没有出网」两件事）
- [x] 只读运维查询面返回每个实例的熔断器状态与可用状态；异常实例与正常实例在这里可以被区分
- [x] 经过配置的冷却时间后熔断器进入 HALF_OPEN，一次成功的调用使其闭合
- [x] 未纳入健康管理的实例不接收流量：配置漏项导致的实例缺失会立刻表现为不可用，而不是静默通过
- [x] 客户端取消不算实例故障 —— 反复取消同一实例不会让它进入 OPEN（这条是对 ADR-0006 区分 cancellation 与 failure 的直接验证）
- [x] 结构性规则：没有任何组件可以在 `OutcomeObserver` 之外提交成功或失败结论；新写一段自行判断 HTTP 状态码来决定成败的代码会让构建变红
- [x] 同步路径与流式路径通过同一个观察者结算：本票内两类路径都要有对应的验证，不允许出现只对其中一条路径覆盖的情况
- [x] 熔断器状态迁移带有请求标识，可以从一次失败追到它触发的那次状态变化
- [x] 限流留出一个且仅一个决策点接口，由同一条管道消费；本票不实现具体算法与阈值

## 落点

| 层 | 新增 / 改动 | 作用 |
|---|---|---|
| `governance` | `CircuitState` / `AvailabilityPolicy` / `CircuitBreaker` / `CircuitBreakers` / `CircuitBreakerObserver` | 熔断状态机与它的唯一输入 |
| `governance` | `DecisionPoint` / `RateLimitDecisionPoint` | 限流的唯一决策点（只有接口，没有算法） |
| `routing` | `InstanceHealth` / `InstanceAvailability` / `AvailabilitySnapshot` / `AvailabilityGate` | 「这个实例现在接不接流量」只在这里判一次 |
| `observe` | `SettlementObserver`（原 `RecordingOutcomeObserver` 更名）/ `AvailabilityEndpoint` | 结算 fan-out 与 `GET /ops/instances` |
| `config` | `GovernanceConfiguration`，`UpstreamConfiguration` 改为产出闸门 | 装配 |
| 配置 | `airouter.governance.failure-threshold`（默认 3）/ `cooldown`（默认 15s） | 阈值来自环境，默认值集中在 `AvailabilityPolicy` |
| 结构规则 | R4 扩展 + 新增 R9 | 「治理看不到状态码」「只有产生终态的那一处能造 `Outcome`」 |

## 三处值得记住的决定

**1. 本地快速失败不产生结算。** 一次本地拒绝不是一次上游调用；把它算作失败会让它继续喂熔断器，
于是「拒绝 → 失败 → 继续打开」构成一个自己维持自己的循环，打开的熔断器永远等不到冷却结束之后的那次成功探测。
`CircuitBreakerEndToEndTest` 对那次请求断言的是**结算条数为零**。

**2. 结算点只有一个 bean。** 熔断器不是另起一个 `OutcomeObserver` bean，而是登记在 `SettlementObserver` 上：
两个 bean 会让「注入一个 `OutcomeObserver`」变成二选一的歧义，而压住歧义的 `@Primary` 含义是
「有多个候选时选我」，与「结算点只有一个」正好相反。投递互相隔离：一个消费者抛异常不会让后面的失去这次结果。

**3. 半开是**读**出来的，不是被触发的。** 冷却结束之后 `state(now)` 直接把 `OPEN` 读成 `HALF_OPEN`：
「可以再试一次」是时间推出来的事实，不需要先发一次请求来触发。少了这一层派生，冷却结束之后
要等下一次上报才转半开，而那时已经放行或拒绝过一次了。

## 实现过程中撞到的东西

- **`SubmissionPublisher` 会丢掉订阅之前提交的条目**，只补发一个 `onComplete` —— 表现为一条空流且不报错。
  流式拒绝那一侧因此改用 Reactor 的 `Flux.just(...)`，原因写在了 `AvailabilityGate` 的注释里。
- **熔断闭环上线之后，任何「故意制造上游失败」的用例都必须交代它与熔断的关系。** 默认阈值是 3，
  同一个测试类里三条失败就会打开熔断器，于是后面某条用例收到的是 `instance_unavailable`
  而不是它想看的上游错误 —— 先是 `FailureAndTimeoutEndToEndTest` 顺序相关地红了，
  处置是给那几个类显式把阈值调高并写明理由（它们断言的是失败的归类，不是熔断）。这条已写进 AGENTS.md。
- **`CompletableFuture` 的失败会被包一层 `CompletionException`**，于是本地拒绝的 503 在 Face 那一层
  被认成内部错误变成 500。`OpenAiFace.describe` 现在先剥一层 —— 状态码是对客户端说的第一句话。

## 验证与反向验证

新增：`CircuitBreakerEndToEndTest`（9 条，含非流式与流式两条路径、冷却后闭合、取消不开闸、
未纳管不可用、异常与正常可区分、迁移带 requestId、运维面 HTTP 读值）、`CircuitBreakerTest`（7 条，
时刻作为参数传入，不靠真的等待）、`ExactlyOneDecisionPointTest`、结构规则 R9 及两条违规样例。
`verify` 74 个测试全绿。

反向验证过四种改动都会红：闸门永远放行（2 条）、取消计入失败（1 条）、
配置里不做那一次登记（9 条）、`takeUntil` 之外把终态之后的帧放出去（票 07 已验）。

## 留给后面

- **主动健康探测还不存在**：「纳入健康管理」现在等于「配置里显式登记」。
  探测出现时它改的是同一个集合，判闸那一侧一行都不用动。
- **限流只有接口没有算法**：`InstanceAvailability` 的构造参数里留着它，运行时是空列表。
  实现算法时要一并回答 Redis 可用时的原子判定与不可用时的本地降级窗口（额度上限 + TTL）。
- 配额结算的幂等（同一 `requestId` 只扣一次）与索引都还没有对应的自动化验证（ADR-0007 第 6、7 节）。
- 运维面现阶段没有鉴权，与管理端点一样默认只在内网可达；接入认证那一票要一起处理。
