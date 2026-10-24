# 07: 终态契约：失败、超时、取消三类结算

> 来源：[spec.md](../spec.md) — 用户故事 4、6、15、17

**What to build:** 「上游报错」「上游超时」「调用方中途断开」这三种结束方式各自走到正确的终态上，且每种都恰好产生一个终态。调用方能区分「上游拒绝了请求」和「连接断了」这两种性质完全不同的失败。

注意本票验证的事实依据和一个已知缺口：本阶段的上游是真实模型端点，字节序列不可控，因此超时是通过把阈值调到极小来触发的，而不是靠真的等待。至于「流成功开篇后在中途静默断开」这类精确到字节的截断场景，在此 seam 下不可构造 —— 缺口已在 spec 中记录，留给后续引入可控本地上游时补齐。

**Blocked by:** 06 流式 SSE 路径与显式终态

**Status:** done（`b2b6d1a` 之后提交，见 git log）

## 验收

- [x] 上游返回 4xx 时，调用方在 `[DONE]` 之前收到错误帧，错误结构与其他失败路径形态一致，但可区分为「上游拒绝了请求」
- [x] 上游不可达时，收到同一套错误结构，可区分为连通性失败而非上游拒绝
- [x] 超过 IdleTimeout 时补发 Failed 并把类别标为上游超时，即使上游从未说过话也有终态
- [x] 调用方中途断开后不再收到任何帧；重复执行取消若干次后连接资源回收是稳定的（没有随次数增长的残留）
- [x] 取消被记为 Cancellation 而不是 Terminal，且不会被归因成实例故障
- [x] 上述每一条路径产生的终态数量都恰好是一，没有路径产生零个或两个
- [x] 慢消费调用方不会导致网关侧出现无界缓冲（用一个刻意不读取的消费方验证积压有上界）
- [x] 非流式路径走同一套错误结构与结算，不存在「只有流式才有这个错误形状」的情况

## 落点

**结算侧**：`OpenAiCompatibleProvider` 增加取消路径（`doOnCancel` → `Outcome(CLIENT_CANCELLED)`），
并用 `StreamState.claim()` 保证一条流在四条结算路径（终态、失败、补发的失败、取消）之间恰好结算一次。

**可观测侧**：`transport` 新增 `InFlightStreams`（在途流的条数），`OutboundWire.postStream` 用
`doOnSubscribe` / `doFinally` 挂钩 —— 记「收回」用 `doFinally` 而不是分头写三个回调，
正是因为漏掉取消那一种就是本票要防的事。`RecordingOutcomeObserver` 增加 `forRequest`，
让「恰好一次」能按请求数出来，而不是看总数或看最近一条。

**测试**（全部从入站 HTTP 打进去，除了一条，理由写在那个类上）：

| 用例 | 断言 |
|---|---|
| `FailureAndTimeoutEndToEndTest` | 上游 4xx → `upstream_4xx`（404 原样）；静默超过 IdleTimeout → `UPSTREAM_TIMEOUT`；非流式与流式错误结构同形 |
| `UnreachableUpstreamEndToEndTest` | 上游够不着 → `upstream_unreachable`（502），流式与非流式同形 |
| `CancellationEndToEndTest` | 断开 → `CLIENT_CANCELLED`；断开后回放停在原处；重复取消 5 次，在途条数每次归零；不读取的消费方 → 回放卡住 |
| `ExactlyOneSettlementTest` | 终态到达的同一瞬间断开 → 仍然只结算一次，且记的是成功 |
| `StreamingEndToEndTest`（新增一条） | 上游在 `[DONE]` 之后又发帧 → 客户端收不到那些内容 |

## 实现过程中被逮到的三个真问题

1. **超时被记成 `UPSTREAM_FAILURE`**。`OutboundWire` 构造 `UpstreamFailure` 时用的是两参构造器（默认类别），
   于是错误类型是 `upstream_timeout`、结算类别却是 `UPSTREAM_FAILURE`。两者不一致正是本票要防的那类偏差，
   而它是被「断言类别」的那条用例逮到的 —— 只断言错误类型的话会绿着过去。
2. **取消没有错误形态**。`Outcome` 要求 SUCCESS 之外都带 `error`（否则下游要处理「失败了但说不出为什么」），
   这条不变式在第一次跑取消用例时把 `IllegalArgumentException` 抛了出来。
   最终给取消一个**没有状态码**的 `GatewayError("client_cancelled", ...)`：
   接收方已经不在了，「给客户端什么状态码」这件事不成立，但原因本身必须留下。
3. **上游在结束哨兵之后又发帧**。`takeUntil(Terminal)` 之前，那些帧会变成终态之后的内容帧。
   「终态唯一」管条数，「终态最后」管位置，之前只有前者。

## 反向验证（每一条都实测会红）

| 改动 | 结果 |
|---|---|
| 去掉 `state.claim()` 守卫 | `ExactlyOneSettlementTest`：1 条结算变 2 条 |
| 去掉 `doOnCancel` | `CancellationEndToEndTest`：结算条数 1 → 0 |
| 去掉 `takeUntil(Terminal)` | `StreamingEndToEndTest`：终态之后多出内容帧（"你" → "你好"） |
| 超时的类别退回默认值 | `FailureAndTimeoutEndToEndTest`：`UPSTREAM_TIMEOUT` 变 `UPSTREAM_FAILURE` |

## 已知代价与限制

- **在途条数不是连接数**。它统计的是「开出去的流」减「收回来的流」，保护的是
  「每一次订阅都对应一次收回」；底层连接池的复用与 socket 泄漏不归它管。
- **背压用例的绝对上界依赖操作系统缓冲区大小**，因此断言写成「停止增长」而不是「小于某个数」；
  另有一条弱一些的绝对断言（停在整条流的四分之一之前），它依赖的样本量是 6 万帧。
- **取消用例不经过 HTTP 边界的那一条**（`ExactlyOneSettlementTest`）是 spec 里 seam 选择的明确例外：
  隔着一层 HTTP，「终态到达的同一瞬间断开」变成竞态，而它要钉住的是 Provider 那一层的结算行为。
- 熔断器本身还没实现，因此「取消不熔断」当前断言的是**它没有被记成上游故障**，
  而不是熔断器状态不变。
