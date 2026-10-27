# 04: 流式终态与结算

**What to build:** 上游不讲完就不是成功；客户端取消不产生事件但仍然结算；
一条流恰好结算一次。

这是全套 ADR 里最重要的一条纪律的落地。legacy 最贵的缺陷就是它：
响应头写出成功就记为成功，于是一个持续吐出半截响应的实例，在它的口径里是 100% 成功。

**Blocked by:** 03

**Status:** done（commit 见 `git log`，tag `baseline-3`）

## Acceptance criteria

- [x] `Terminal` / `Failed` 是终态的**唯一**形态，由 Provider **显式**发出，
      不允许依赖 `onComplete` / `onError` 隐式推断
- [x] 上游收了连接但没发结束哨兵 → 补发 `Failed(upstream_closed)`，**不是** `SUCCESS`
      （内容是否完整无从判断）
- [x] 帧间静默超过 `idleTimeout` → `UPSTREAM_TIMEOUT`
- [x] 客户端中途断开 → **无事件**（接收方已经不在了），但**必须结算**成 `CLIENT_CANCELLED`
- [x] `OutcomeObserver` 是结算的**唯一**收口点；本阶段它接到一个可观测的结算记录上
      （`SettlementLog`）
- [x] 结算记录能区分四种 `OutcomeKind`，于是「取消不会冒充失败」是可被断言的
- [x] 一条流**恰好结算一次**（`StreamState.claim()` 第二次返回 false）
- [x] `usage` 优先取上游回报，取不到时才讨论本地估算
- [x] 累积内容有上限且超限截断；**截断影响展示，不影响计费**
      （token 估算走独立的字符计数轨道）
- [x] 只有 Provider 能构造 `Outcome`（R9）；`routing` / `governance` 看不到 HTTP 状态码（R4）

## 落地 shape

**core** —— `StreamState`（`AtomicBoolean` + `claim()` 一次 CAS）、`OutcomeObserver`
（单方法 `settle(Outcome)`，非阻塞）。**依赖树没变**：core 仍然只有 JUnit + AssertJ。

**gateway** ——
- `observe/SettlementLog`：`OutcomeObserver` 在本阶段的唯一实现。它是**诊断用的窗口**
  （256 条），不是存储；按 `requestId` 取而不是「取最后一条」—— 后者在并发下会拿到别人的结果。
  返回类型写具体类，于是诊断与测试能按 `SettlementLog` 取到它，而 Provider 按接口注入
- `providers/openai`：每次调用一个 `Session`（按订阅创建）。结算的**时机是收到终态的那一刻**
  —— 终态、取消、`endWithoutTerminal` 三处都走同一个 `settle(...)`，由 `state.claim()`
  保证只有第一次进得去。顺序是 译码 → 截断 → 补发缺席的终态 → 终态处截断 → 取消时结算，
  换一处就会漏一条
- `config/UpstreamConfiguration`：`SettlementLog` 作为一个 bean 交给 Provider。
  两个端口仍然是**同一个对象**，因此「流式绕过治理」在装配上也不成立

## 验证

`./mvnw clean verify` 全绿：core 12 / gateway 14 / app 57（流式端到端 17）。

**故意违反实测过两处**，都确认会红：

1. 去掉 `.doOnCancel(session::settleCancelled)` → `cancellationSettlesAsCancelledAndNotAsUpstreamFailure`
   红（十秒内等不到结算，账本 0 条）。**取消这条路径没有任何别的断言能替它说话**：
   客户端那一侧看到的是「流断了」，与「网关忘了结算」在外表上一模一样
2. 把 `endWithoutTerminal()` 的结算类别改成 `SUCCESS` → `upstreamClosedWithoutTerminalIsNotSuccess` 红。
   这正是 legacy 那个最贵的缺陷本身，探针也因此选在这一处

**恰好结算一次**分两处断言，缺一不可：core 那边用 8 个线程同时 `claim()`
（先查后写的写法会在同一瞬间给出两个 true）；app 那边走完整条流之后断言
同一 `requestId` 只有一条结算 —— core 守的是 `claim()`，app 守的是**走到末端**仍然只有一条。

## 三处值得记住的判断

- **静默必须发生在关闭输出流之前。** 录制器第一版把 `sleep(silence)` 放在 `try (OutputStream ...)` 之后，
  于是分块编码的终止块先被发出 → 客户端看到的是「上游把话说完了」，
  判出来的是 `upstream_closed` 而不是 `upstream_timeout`。
  「讲完几句就沉默」与「讲完就关连接」在字节上是两种东西，开关必须造出前一种
- **SpotBugs 的 `EI_EXPOSE_REP2` 在本项目里是「改代码」不是「加抑制」。** 公开构造器里直接存一个
  **自定义接口**会被判「持有外部可变对象」（存 JDK 的 `Consumer` 或不可变 record 都不判）。
  ADR-0008 第 7 节写明处置路径是改代码，因此 Provider 改为**只持有结算这个动作**
  （`Consumer<Outcome>`，由 `observer::settle` 得来）—— 它需要的本来也只是这一件事。
  探针（一个同时存三种类型的临时类）确认过判定依据之后已删除
- **取消不对应任何状态码。** `Outcome` 要求非成功必带 `error`，因此取消也带一个，
  但它的 `httpStatus` 是 null —— 接收方已经不在了，「给客户端什么码」这件事不成立

## Notes

- **本阶段不交付熔断器本体**（它属阶段 7）。本票的交付是结算点与结算记录；
  熔断器接进同一个 `OutcomeObserver` 时，「取消不计入熔断」自动成立
- **「本地拒绝不出网、不结算」不在本票** —— 它需要注册表与可用性闸门，属阶段 3
- 故意制造上游失败的用例**必须交代它与熔断阈值的关系**：默认阈值是 3，
  同一测试类里三条失败就会把熔断器打开，于是后面某条用例收到的是
  `instance_unavailable` 而不是它想看的上游错误 —— 表现为一条**与执行顺序有关**的偶发红。
  **本票不受它影响**（熔断器尚不存在），但上面三条失败类用例在它落地那天要回来复查
- 时间相关的断言**等条件成立，不睡固定时长再比数字**
- 测试文件并入模块预算，需合并同类项：本票**没有新增测试文件** ——
  终态与结算的断言并进了既有的 `StreamingEndToEndTest`（14 个方法，上限 20），
  core 的 `StreamState` 并进既有的 `CanonicalTest`
