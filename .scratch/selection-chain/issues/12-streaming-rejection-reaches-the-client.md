# 12: 流式路径上「选不出实例」也要收口成 400/503，而不是断流

**What to build:** 票 08 的验收「选不出实例**不出网、也不结算**，由 Face 在边界上收口成一个错误」
目前**只对非流式成立**。把流式的那一半补上。

来源：两轴 code review（2026-10-28，固定点 `eedaff6`）的 Spec 轴第 2 条。

- `OpenAiFace.complete` 的流式分支是 `Mono.just(stream(requestId, request, context))`：
  `ResponseEntity`（200 + `text/event-stream`）在**装配期**就造好了，
  真正的解析与选择包在 `Flux.defer` 里，**订阅时才发生**。
  于是 `NoUsableInstanceException`（503）、`CallerIdentityMissingException`（400）、
  `ActiveCountMissingException`（503）都在订阅期抛出，落在 `.onErrorResume(...)` **之外** ——
  客户端拿到的是「200 之后断流」，而不是那三个错误体
- 全仓没有一条断言覆盖流式那两个码（`NonStreamingEndToEndTest` 只验非流式那一侧）
- **关键区分，别搞反**：流式协议一旦发出第一帧就改不了状态码（`stream()` 的 javadoc 正是这么写的），
  但**选择链的拒绝发生在第一帧之前** —— 那时状态码还定得下来。
  本票要的是「在说话之前把拒绝说出来」，**不是**「给一条已经开始的流补一个状态码」

**Blocked by:** 10（与票 11 无依赖，可并行；两条都动 `faces/openai` 之外的不同文件）

**Status:** done（2026-10-30，提交见 git log；实现结论附在文末）

## Acceptance criteria

- [x] 流式请求缺调用方标识 → **400 `caller_identity_required`** 的错误体，不是 200 + 断流
- [x] 流式请求选不出实例 → **503**（rejection 命名与非流式同一份），同上
- [x] 流式在途计数查不到 → **503 `instance_selection_unavailable`**，同上
- [x] 三个错误体的形状与非流式**同一份**（`OpenAiInboundCodec.encodeError`），
      「同样是缺标识，流式给断流、非流式给 400」正是要被消灭的那种不一致
- [x] **第一帧写出之后**的上游失败仍然只能是帧上的错误载荷（不改状态码）——
      这条已有断言守着：`StreamingEndToEndTest.upstreamClosedWithoutTerminalIsNotSuccess`
      （第一帧之后上游收连接 → 客户端拿到的是帧上的 `upstream_closed`，状态码不动）
- [x] 拒绝的那一次**仍然不出网、不结算**（ADR-0013 (e)）：账本里没有它的记录
- [x] 六步链的解析必须发生在 `ResponseEntity` 组装**之前**；
      用哪个入口做到（`RoutedPort` 新增「只解析不调用」，还是把 `defer` 的边界挪到选择之后）
      由本票决定，并**写进那个入口的 javadoc** —— 这是本票唯一的设计决定，
      决定之后 Face 仍然不认识 `providers`（R3）

## Known gaps（本票不做）

- 其余 wire 形态各自的 Face：现在只有 `openai` 一个，新形态落地时照这里的形状办
- 管理与控制台面怎么展示「这次调用被拒绝」→ 阶段 16
- 「被粘滞的实例下线之后换一个」的端到端断言 → 阶段 3（热重载落地后），理由同票 09

## 落点

| 文件 | 内容 |
|---|---|
| `faces/openai/OpenAiFace` | 流式分支：先解析、再组装响应；拒绝在边界上收口 |
| `routing/RoutedPort`（可能） | 「只解析不调用」的入口，若采用那一种形状 |

## 测试（两条接缝）

1. **HTTP 入口（主）**：并进既有的 `StreamingEndToEndTest` 或 `InstanceSelectionEndToEndTest`
   —— 三条断言（400 / 503 / 503），**不新建文件**（app 已 11 个，过软上限）
2. **单元接缝**：本票的判据是「客户端收到什么」，那条接缝在 HTTP 入口上；
   若采用 `RoutedPort` 新入口，给它一条不起容器的断言（解析失败 → 拒绝原因）

## 实现时要守的

- 每条新断言故意破坏一次、确认会红；**探红恢复用 `cp` 备份，不用 `git checkout`**
- 断言**状态码 + 错误体里的 `type`**，不只断言状态码：断流与 200 空流也会让某些断言绿
- 流式路径上不许出现阻塞等待（`Mono.block()` 之类）—— 收口要发生在响应式链上
- 预算：同上，新增文件前先找合并目标

## 实测结论（2026-10-30 收尾时补）

**设计决定**：不新增 `RoutedPort` 入口，把 `defer` 的边界**挪到选择之后** ——
Face 的流式分支先调 `streaming.stream(request, context)`（六步链在这里同步跑完、拒绝从这里抛出），
`defer` 只包编码与订阅。契约写进了 `RoutedPort.stream` 的 javadoc：
「解析发生在本方法被调用时，不在订阅时；同步抛出的拒绝意味着响应尚未提交」。
Provider 的 `stream()` 本来就是冷的（`Flux.defer` 在 Provider 内部），提前调用没有副作用。

**三条断言的接缝都比预想低一层（本票最重要的发现）**：三类拒绝**在真实 HTTP 接缝上一个都造不出来**——

- 缺标识：真实连接永远带远端地址，而它是缺显式标识时的最后一条来源（`callerIdOf` 的回退链）
- 候选为空：启动期配置闸（票 07）保证每个服务至少一个启用实例；
  「全部关闭」根本起不来（`StreamingRejections` 嵌套上下文当场验证过）
- 标签不匹配：`requiredTags` 阶段 8 之前恒空；在途计数查不到是装配错误，真实装配对不上

于是三条断言各退一层、但全部用**真实对象**（真链、真 `RoutedUpstream`、真 Face，无 stub）：

1. 400 → 容器里**真实的 Face bean** 直接调入口方法（粘滞开着的那个上下文），
   入参用 `MockServerHttpRequest`（没有远端地址 —— 这正是缺标识的真实来路）
2. 503 `no_enabled_instances` → 起容器、真实装配：`ServiceView` 里只放一只**关闭着的**实例
   （启动闸只挡配置层，运行时视图不挡 —— 这正是热重载之后它的真实来路）
3. 503 `instance_selection_unavailable` → 同上，策略给 `least-connections`、
   在途数注入「什么都不知道」的函数（真实签名下的退化值，与单测同一把钥匙）

代价与收获：`RoutedUpstream` 从包私有改成 public（它是 public 接口 `RoutedPort` 的唯一实现；
三类拒绝只有不起容器才装配得出来）。app 测试文件数不变（并进 `InstanceSelectionEndToEndTest`）。

**探红两轮**，各自恰好红掉三条新断言：A）把 `defer` 边界挪回去（包住选择）→
三条全拿到 200 而不是错误体；B）`describe()` 三个映射全改成 500 → 三条全红在状态码上。
