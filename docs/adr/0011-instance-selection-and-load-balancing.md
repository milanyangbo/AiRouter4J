# ADR-0011：实例选择与负载均衡

- 状态：已接受
- 日期：2026-10-26
- 相关：[0010](./0010-configuration-model-and-runtime-registry.md)（候选来自注册表）、[0005](./0005-streaming-terminal-outcome-contract.md)（延迟样本来自 Outcome）

能力面：**第 2 类（负载均衡）**

## 1. legacy 的事实（已核实）

`loadbalancer/impl/` 下 8 个类：

```
RandomLoadBalancer        RoundRobinLoadBalancer     LeastConnectionsLoadBalancer
IpHashLoadBalancer        ConsistentHashLoadBalancer LatencyAwareLoadBalancer   ← 6 个策略
CachedWeightedSelector                                                          ← 加权辅助类
StickyLoadBalancer                                                             ← 包装器
```

`LoadBalancerManager` 对外报的策略名只有 6 个：
`random / round-robin / least-connections / ip-hash / consistent-hash / latency`。

**「加权」不是第 7 种策略**，它在 legacy 里是 `CachedWeightedSelector` —— 被轮询与最少连接**各自调用一遍**。
**「粘滞」也不是第 7 种**，它是 `StickyLoadBalancer` 这个包装器，包在任意策略之外。

> 文档里出现过的「7 种策略」是误传。真实结构是 **6 个策略 × 1 个加权修饰 × 1 个粘滞包装器**。

## 2. 决定

**(a) 六个策略，纯函数。** `random` / `round-robin` / `least-connections` / `ip-hash` /
`consistent-hash` / `latency`（EWMA）。

`select` 包里的策略**只看 ③ 筛过的候选与权重，不看健康、不做 IO**。
纯是为了可确定性断言 —— 给定候选与随机源，结果可复现，判「策略对不对」不需要起一个端点。

**(b) 加权是独立的一步，不是每个策略各实现一遍。**
候选先按权重展开（或按权重计算区间），再交给策略。
于是「6 个纯函数 × 1 个加权步骤」覆盖全部组合，而 legacy 需要在每个策略里各写一遍加权。

**(c) 粘滞是包装器，不是策略。**
按客户端标识（或自定义键）把会话持续引到同一实例，包在任意策略之外。
搬 `StickyLoadBalancer` 时**必须搬成包装器** —— 搬成策略会丢掉「粘滞可与任意策略组合」这个性质。

**(d) EWMA 延迟感知的口径。**
`ewma = alpha * sample + (1 - alpha) * ewma`，权重 ∝ `1/(1+ewma)`。
样本来自 `Outcome.latency`（算到终态为止，见 0005）：
成功用实际延迟，**失败用惩罚值**（legacy 用 30 秒）作为样本，让高失败率的实例自然被降权。
候选列表里消失的实例要**惰性清理**陈旧条目，否则一个下线的实例永远占着最差值。

**(e) 需要外部输入的两个策略。**
`least-connections` 要活跃连接数（来自 transport 的在途计数）；
`ip-hash` / `consistent-hash` 要客户端标识（来自选择链第 ① 步的认证结果）。
**这些输入是参数，不是让策略去做 IO。**

## 3. 裁决

| legacy 的东西 | 裁决 |
|---|---|
| 6 个策略类 | **搬**（改：剥掉内嵌的指标与 tracing，输入改成纯参数） |
| `CachedWeightedSelector` | **改**：从「被各策略调用」提升为选择链里的独立一步 |
| `StickyLoadBalancer` | **搬**（保持包装器形态） |
| EWMA 的失败惩罚与惰性清理 | **搬**（这是少数经过验证的细节） |

## 4. 验收

| 要守的事 | 断言 |
|---|---|
| 策略是纯函数 | 给定候选 + 固定随机源，结果可复现（无需起端点） |
| 加权独立于策略 | 换任意策略，权重展开行为一致 |
| 粘滞可与任意策略组合 | 粘滞 + 随机、粘滞 + 轮询 都能稳定命中同一实例 |
| 失败被降权 | 注入失败样本后，该实例被选中的概率下降 |
| 陈旧条目被清理 | 实例从候选移除后，其 EWMA 条目不再参与 |
| 一致性哈希稳定 | 实例增减时，迁移的键比例在预期量级（虚拟节点生效） |

## 5. 后果

- 新增策略 = 加一个纯函数类 + 在策略工厂登记，不动注册表与路由
- 策略不许读健康状态 —— 那是第 ③ 步（可用性闸门）的事，混进来会让「策略对不对」无法单独断言
