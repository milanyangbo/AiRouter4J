# 10: 其余四个策略与 EWMA 延迟样本

**What to build:** 补齐六个策略里剩下的四个，并把 `latency` 要用的延迟样本做出来。

- `least-connections` —— 需要**活跃连接数**（`transport` 的在途计数）
- `ip-hash` / `consistent-hash` —— 需要**调用方标识**（第 ① 步的产物）
- `latency` —— 需要**每实例 EWMA**

**这些输入一律是参数，不是让策略去做 IO**（ADR-0011 (e)）。这是本票最容易写歪的地方：
策略一旦自己去问 transport 或去读 `Outcome`，`select` 就不再是纯函数，
ADR-0003 的结构规则会红，而「给定输入结果可复现」这条验收也就没了。

**Blocked by:** 09（工厂已存在，四个策略登记进它）

**Status:** done（`7805a31` 之后；本票提交见 git log）

## Acceptance criteria

- [x] `least-connections`：活跃连接数来自 `transport` 的在途计数，作**参数**交给策略；
      **拿不到时的行为显式且被断言**（不能默认 0 —— 那会让所有实例看起来一样闲，
      于是这个策略静默退化成随机）
- [x] `ip-hash`：落点 = `hash(callerId) % 权重之和` → 走第 ④ 步的区间；同一标识稳定命中同一实例
- [x] `consistent-hash`：键环 + 虚拟节点数 ∝ 权重。**不叠加**第 ④ 步的区间
      （虚拟节点数本身已经是加权，叠加就是双重加权）；
      实例增减时**迁移的键比例在预期量级**，证明虚拟节点真的生效
- [x] `latency`：EWMA，`ewma = alpha * sample + (1 - alpha) * ewma`，权重 ∝ `1/(1+ewma)`
- [x] 延迟样本由 `observe` 从 `OutcomeObserver` 收 `Outcome` 维护 —— 它是**唯一观测点**，
      然后作参数交给策略。**这样 `select` 仍然是纯函数**（ADR-0003）
- [x] 样本口径：成功用 `Outcome.latency`（算到终态为止，ADR-0005），**失败用惩罚值**，
      于是高失败率的实例被自然降权
- [x] 候选里消失的实例：EWMA 条目**惰性清理**，不再参与 ——
      否则一台下线的机器永远占着最差那一档
- [x] `alpha` / 失败惩罚值 / 虚拟节点数：**显式常量且可配**，不接受藏在实现里
- [x] **除一致性哈希外**，换任意策略时权重行为一致（一条断言覆盖全部策略）；
      一致性哈希的等价性质改由「键比例 ≈ 权重占比」承担

## 实测结论

### 1. 录制器的静默只在**块与块之间**发生（本票最贵的一条）

端到端那条断言一度恒为 `[heavy, heavy, heavy, light, ...]` —— 延迟感知完全没起作用。
根因不在策略：样本里两个实例都是几毫秒。原因是 `chunksWith(...)` 只给**一块**，
而 `RecordedUpstream` 的 `between` 只在 `i > 0` 时睡 —— 一块写完的模式给多大的静默都不落下去。

**「慢的实例」必须先拆成若干块才造得出来**（现在拆成三个 SSE 事件，块间各 300ms：
`heavy=315ms` vs `light=2~4ms`，差两个数量级）。已把这句话写进 `replayBytes` 的 javadoc。

### 2. 端到端那条断言的两个前提，缺一个就绿得毫无意义

- **每次换调用方**：粘滞若开着，同一个调用方会被钉死，那时验的不是延迟。
  换调用方之后这条断言与粘滞开关的取值无关。
  （中途踩过一次：还没加 `OVERRIDE` 时，嵌套上下文**继承**了外层的动态属性源，
  而外层那份把粘滞打开了，于是八次全落在同一个实例上。加了 `OVERRIDE` 之后
  嵌套自带一份属性源、粘滞回到关，`application.yml` 里的默认值本来就是关。）
- **每次等结算落地再发下一个**：下一个请求要看得见上一个学到的东西。
  等条件成立（轮询账本，10 秒超时），不睡固定时长。
- 顺带把「读完整条流」也变成断言（`endsWith("[DONE]")`）：样本只在终态那一刻产生，
  只拿响应头就走的话，被测的是「客户端有多快放弃」。

### 3. 探红抓到一条空断言

`OutboundWireTest` 的流式在途计数原先只断言「结束之后是 0」，
而**登记过的实例本来就答 0** —— 把 `counted(Flux)` 整段拆掉，那条断言照样绿。
补上「订阅时在途是 1」之后才红（与非流式那条同形）。

其余探红（每条都先 `cp` 备份、确认红、再恢复）：

| 破坏 | 红掉的断言 |
|---|---|
| `LatencySelector` 一律当 0 | 单测 `latencyPrefersTheFasterInstance` / `instanceWithoutHistoryIsProbingRatherThanLast` + 端到端 `slowInstanceStopsReceivingTraffic`（后者报出正是 `[heavy,heavy,heavy,light]` 那一串） |
| `least-connections` 把查不到当 0 | `unknownActiveCountIsRejectedRatherThanCountedAsZero` |
| `LatencySamples` 失败用实际延迟、不清理 | `failuresAreRecordedAsThePenalty` / `staleEntriesAreDroppedOnTheNextSettlement` / `cancellationIsNotASample` |
| 一致哈希虚拟节点数不看权重 | `consistentHashDistributionFollowsWeights` |
| 环上点的位置依赖整个候选集合 | `removingAnInstanceOnlyMovesItsOwnKeys` / `addingAnInstanceOnlyTakesKeysForTheNewcomer` |
| `requireCallerId` 不抛 | `hashStrategiesRejectMissingCallerIdentity` |
| `counted(Mono)` / `counted(Flux)` 不计数 | `inFlightCountingCountsEveryExit` / `inFlightCountingCoversStreamingToo` |

### 4. 门禁

- SpotBugs 也扫**测试**文件：`DLS_DEAD_LOCAL_STORE` ×2、`WMI_WRONG_MAP_ITERATOR` ×1
  （`keySet` + `get` 改 `entrySet`）。按 ADR-0008 §7 改代码，没有加抑制。
- 前几轮我自己 `-Dspotbugs.skip` 跑测试，把这三條拖到了最后一次 `verify` 才暴露 ——
  **单模块跑测试不要跳门禁**。
- 全项目行覆盖率 **88.6%**（票 09 落地时 87.8%）；`./mvnw clean verify` BUILD SUCCESS。

## Known gaps（本票不做）

- **延迟样本挂在哪**这件事与 `GLOSSARY.md` 现有的一句冲突：术语表写「注册表持有延迟样本」，
  而 ADR-0003 要求 `select` 是纯函数。本票按 ADR-0003 放 `observe`，
  **术语表那一句待 `domain-modeling` 裁决**（已记进 [spec](../spec.md) 的 Further Notes）
- 熔断与失败转移 → 阶段 7（ADR-0013）。它们到来之后 `latency` 与 `least-connections`
  才有第三个输入来源，本票不预留那个位置
- 多副本之间共享延迟样本与在途计数 → 阶段 13
- 「活跃连接数拿不到」在真实运行下会不会发生，由 `transport` 那一边回答；
  本票只保证行为显式且被断言

## 落点

| 文件 | 内容 |
|---|---|
| `select/LeastConnectionsSelector` + `IpHashSelector` + `ConsistentHashSelector` + `LatencySelector` | 四个策略，纯函数 |
| `observe/LatencySamples` | 从 `Outcome` 维护每实例 EWMA；成功用 latency、失败用惩罚值；惰性清理 |
| `config/UpstreamConfiguration` | 把活跃连接数与延迟样本**接成参数**交给策略 |

## 测试（两条接缝）

1. **单元接缝**（主）：四个策略各一组断言（含上面每条验收里的失败路径）；
   EWMA 降权（注入失败样本后选中概率下降）与惰性清理（实例从候选移除后不再参与）；
   一致性哈希的迁移比例；`least-connections` 在计数缺失时的行为
2. **HTTP 入口**：只挑**一条**落地断言（例如 `latency` 配一次、让一个录制器明显慢，
   断言流量偏向另一个）—— 不把单元接缝的断言重抄一遍

## 实现时要守的

- 每条新断言故意破坏一次、确认会红；探红恢复用 `cp` 备份，不用 `git checkout`
- **EWMA 与一致性哈希的断言不要写成「断言具体数值」**：那会把实现抄一遍
  （testing.md 判据三）。断言的是**性质**：降权发生了、迁移比例在量级内、键比例 ≈ 权重占比
- 预算：`airouter-app` 测试文件已顶破软上限（11 个）；本票优先把新断言并进票 08 建的那份端到端
