# ADR-0006: 流式终态上报契约

- **状态**：已接受
- **日期**：2026-10-23
- **相关**：[0005 Canonical](./0005-canonical-intermediate-representation.md)、[0007 流量治理](./0007-traffic-governance-model.md)、[0008 验证策略](./0008-verification-and-quality-gates.md)

> **这是全套 ADR 里最重要的一篇。** 它修的是从来源项目带回的最严重缺陷，
> 而且 `0005` 的事件模型、`0007` 的健康状态判定都依赖这里的定义。

## 背景

来源项目的流式请求，其三处代码单独看都合理，连起来构成故障。

**第一段**：流式请求在写出响应头之后立刻返回成功。

```java
// StreamingRequestProcessor.java:412-414
return Mono.just(ResponseEntity.ok()
        .contentType(TEXT_EVENT_STREAM)
        .body(v1Stream));
```

此时流还没有开始输出任何内容。

**第二段**：外层的 `doOnSuccess` 据此结算。

```java
// BaseAdapter.java:191-203
.doOnSuccess(response -> {
    long duration = System.currentTimeMillis() - startTime;      // ≈ 0ms
    boolean success = response.getStatusCode().is2xxSuccessful(); // true
    metricsRecorder.recordCompleteCallStats(..., success, ...);
})
```

**第三段**：成功直接穿透到熔断器。

```java
// ModelServiceRegistry.java:466
circuitBreakerManager.recordSuccess(instanceId, baseUrl);
```

而流式中途失败走的是另一条路径（`:474-485` `recordStreamingError`）——
它记录指标，**但不碰熔断器、不更新错误追踪、不触发降级**。

### 由此产生的三个实际后果

| 后果 | 说明 |
|---|---|
| **熔断器永不 OPEN** | 实例只要还能返回 200 响应头 + 半个 SSE 帧，就一直被评为健康，请求会持续被路由过去 |
| **延迟感知负载均衡被污染** | 流式样本恒为 ≈0ms，`LatencyAwareLoadBalancer` 的 EWMA 输入是假的，在流式为主的负载下退化成随机选择 |
| **降级逻辑是死代码** | `processRequestWithFallback`（`BaseAdapter:250-289`）对流式路径从来不会生效 |

### 根因

不是某一处少写了一行 `recordFailure`。逐个补这三处也只是把错误平移。

真正的根因是**缺少统一的终态概念**：每个组件各自在自己能够观测到的时机判定结果，
而对流式来说，那些时机都是错的 —— 响应头写出成功不代表下游成功，上游关闭连接也不等于失败。

## 决策

### 1. 终态必须显式发出，禁止隐式推断

`CanonicalStreamEvent` 的两个终态子类型（`Terminal` / `Failed`）**必须由 provider 显式发出**。

- **禁止**依赖 Reactor 的 `onComplete` / `onError` 推断流的结果。
  上游可能在发出 `[DONE]` 之前就关闭连接；也可能先报错、随后又补发正常帧。
  这两种情况下，"流如何结束"都无法从操作符层面判断。

- **兜底**：超过 idleTimeout 仍未收到终态时，由框架层补发 `Failed`（归类为 `UPSTREAM_TIMEOUT`），
  保证任何一条流最终都有且只有一个终态。

- **终态是这条流的最后一个事件**：上游在 `[DONE]` 之后又发来的帧不进入事件流。
  「终态唯一」与「终态最后」不是同一件事 —— 前者管的是条数，后者管的是位置，
  而消费方（含客户端与未来的脱敏管线）都是按「终态之后不会再有内容」写的。

### 2. `OutcomeObserver` 是全链路唯一的结果结算点

```java
public interface OutcomeObserver {
    void onTerminal(Outcome outcome);
}

public record Outcome(
    String requestId,
    String serviceId,
    String instanceId,
    OutcomeKind kind,                 // SUCCESS | UPSTREAM_FAILURE | UPSTREAM_TIMEOUT | CLIENT_CANCELLED
    Duration latency,                 // 从请求发出到收到终态的时长
    CanonicalUsage usage,             // 可能未上报，允许为空
    GatewayError error                // SUCCESS 时为 null
) {}
```

> **一处用词在实现时统一**：这里原写 `TokenUsage`，实现用的是 ADR-0005 的 `CanonicalUsage`。
> 两者指同一个东西，而 Canonical 已经有表达「上游没给就是没给」的类型，
> 为一个字段再定义一个同构类型只会带来一次无意义的转换。

规则：

- **熔断器、限流回放、健康检查、指标、配额结算，全部只从 `OutcomeObserver` 取结果。**
  任何组件不得自行判断一次调用是成功还是失败。这一条由依赖方向测试（ADR-0003）强制。
- `latency` 一律计算到收到终态为止。这让流式与非流式的延迟具有可比性，
  延迟感知负载均衡重新拿到真实的 EWMA 输入。
- 订阅 `Outcome` 是廉价的：它们统一在收到终态事件之后才投递，不在热路径上

### 3. 客户端取消是独立的第三种终态

```text
CLIENT_CANCELLED  ——  影响资源回收与部分指标，但不判为实例故障
```

理由很直接：移动端切后台、用户关页面都会产生 cancel。
如果把它算作上游失败，一次共享网络的抖动就能熔断掉一个健康的实例。

**它不产生事件，只产生结算。** 取消发生时接收方已经不在了 —— 往一个断开的下游发事件
既不成立也没有意义。但结算仍然必须发生：少记一次与记成成功，对下游的熔断器是同一种误导。
因此「终态唯一」这条断言落在**结算点**上，不落在事件序列上：

- 事件序列上，取消的那条流没有终态事件（也没有任何后续事件）；
- 结算点上，它恰好产生一条 `CLIENT_CANCELLED`。

**一条流恰好结算一次由显式守卫保证**，不是靠「两条路径不会同时发生」这种假设：
终态事件、失败事件、补发的失败、取消这四条路径里任意两条在同一条流上先后发生时，
守卫让第二条被丢弃。少了它，一次调用会被计成两次，而这类错误不报错，
只会让指标与熔断阈值慢慢失真（有反向验证：去掉守卫，定向测试立刻由 1 条结算变成 2 条）。

### 4. 熔断器只看归因到自己身上的失败

```java
// 判定口径：只有 causedByUpstream = true 的失败才进入熔断统计
UPSTREAM_FAILURE   → 计入
UPSTREAM_TIMEOUT   → 计入
CLIENT_CANCELLED   → 不计入
```

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 在三处证据点分别补一句 `recordFailure` | 根因没变：组件仍在各自观测各自的结果。下一次加一个观测点，同样的事会再发生一遍 |
| 把响应体整体 buffer 完再返回 | 那就不再是流式网关了 |
| 用一个后台线程定时扫描未完成流并标记失败 | 引入了"多久算失败"的第二套时间语义，与 idleTimeout 竞争；而且失败判定会比实际晚很多 |
| 取消不计入任何统计 | 资源泄漏与部分计费会丢失；观测侧无法区分"正常结束"和"无声无息地没有了" |
| 让每个订阅组件自己处理终态 | 那就是没有收口点，回到原状 |

## 后果

**正面**

- 熔断器开始对流式故障真实生效（这是能被集成测试直观验证的：连续让上游流式失败，熔断器必须 OPEN）
- 延迟指标可用，延迟感知负载均衡恢复有效性
- 降级路径不再是死代码
- 不再出现"某个组件恒认为成功"的可能：成功只有一个来源

**负面**

- provider 实现多了一份责任：必须保证在任何结束方式下都发出终态。
  漏发是新的故障模式，因此必须有兜底 + 必须有针对"上游异常关闭"的测试
- `Outcome` 是整个治理层的输入，它的字段变更会影响多个下游组件，需要谨慎演进
- 相比原来的实现多了一次事件投递，虽然不在热路径上，但这是真实的额外开销

## 如何验证

这几条必须都是**自动化**的，不是人工确认：

- [ ] **流式失败驱动熔断**：连续 N 次让上游流式中途失败，熔断器进入 OPEN 状态。改动来源是原来的"永不 OPEN"
- [x] **延迟语义**：上报的 `latency` 是到收到终态为止的真实耗时，流式样本不再恒为零。
      由 `StreamingEndToEndTest`（上游停顿已知）与 `LocalModelStreamingEndToEndTest`（真实端点）断言
- [x] **取消不判为实例故障**：客户端在中途断开，结算的是 `CLIENT_CANCELLED` 而不是失败
      （熔断器本身还没有实现，因此「状态不变」改为断言它**没有被记成上游故障** ——
      那正是会让熔断器误伤健康实例的那一种误记）。由 `CancellationEndToEndTest` 断言
- [x] **终态唯一**：正常结束、「上游未发哨兵即关闭」、超时、取消四条路径各恰好结算一次。
      按请求标识挑出结算记录来数，避免被别的用例干扰；
      「终态之后立刻断开」另有定向用例（`ExactlyOneSettlementTest`），
      反向验证过去掉唯一性守卫会由 1 条变成 2 条
- [x] **取消之后资源收回**：重复取消若干次，在途条数每次都回到零，不随次数增长。
      由 `InFlightStreams` 与 `CancellationEndToEndTest` 断言；
      同一个用例还断言了断开之后网关不再从上游取数据（回放停在原处）
- [ ] **唯一结算点校验**：没有任何组件在 `OutcomeObserver` 之外提交成功/失败。这是代码实现层面的约束，由依赖方向扫描保证
