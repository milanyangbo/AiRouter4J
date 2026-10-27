# 10: 其余四个策略与 EWMA 延迟样本

**What to build:** 补齐六个策略里剩下的四个，并把 `latency` 要用的延迟样本做出来。

- `least-connections` —— 需要**活跃连接数**（`transport` 的在途计数）
- `ip-hash` / `consistent-hash` —— 需要**调用方标识**（第 ① 步的产物）
- `latency` —— 需要**每实例 EWMA**

**这些输入一律是参数，不是让策略去做 IO**（ADR-0011 (e)）。这是本票最容易写歪的地方：
策略一旦自己去问 transport 或去读 `Outcome`，`select` 就不再是纯函数，
ADR-0003 的结构规则会红，而「给定输入结果可复现」这条验收也就没了。

**Blocked by:** 09（工厂已存在，四个策略登记进它）

**Status:** pending

## Acceptance criteria

- [ ] `least-connections`：活跃连接数来自 `transport` 的在途计数，作**参数**交给策略；
      **拿不到时的行为显式且被断言**（不能默认 0 —— 那会让所有实例看起来一样闲，
      于是这个策略静默退化成随机）
- [ ] `ip-hash`：落点 = `hash(callerId) % 权重之和` → 走第 ④ 步的区间；同一标识稳定命中同一实例
- [ ] `consistent-hash`：键环 + 虚拟节点数 ∝ 权重。**不叠加**第 ④ 步的区间
      （虚拟节点数本身已经是加权，叠加就是双重加权）；
      实例增减时**迁移的键比例在预期量级**，证明虚拟节点真的生效
- [ ] `latency`：EWMA，`ewma = alpha * sample + (1 - alpha) * ewma`，权重 ∝ `1/(1+ewma)`
- [ ] 延迟样本由 `observe` 从 `OutcomeObserver` 收 `Outcome` 维护 —— 它是**唯一观测点**，
      然后作参数交给策略。**这样 `select` 仍然是纯函数**（ADR-0003）
- [ ] 样本口径：成功用 `Outcome.latency`（算到终态为止，ADR-0005），**失败用惩罚值**，
      于是高失败率的实例被自然降权
- [ ] 候选里消失的实例：EWMA 条目**惰性清理**，不再参与 ——
      否则一台下线的机器永远占着最差那一档
- [ ] `alpha` / 失败惩罚值 / 虚拟节点数：**显式常量且可配**，不接受藏在实现里
- [ ] **除一致性哈希外**，换任意策略时权重行为一致（一条断言覆盖全部策略）；
      一致性哈希的等价性质改由「键比例 ≈ 权重占比」承担

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
