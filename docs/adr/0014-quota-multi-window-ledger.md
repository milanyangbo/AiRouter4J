# ADR-0014：配额（多维度多窗口账本）

- 状态：已接受
- 日期：2026-10-26
- 相关：[0013](./0013-resilience-circuit-failover-ratelimit-cache.md)（限流管速率，配额管用量）

能力面：**第 5 类（配额）**

## 1. legacy 的事实（已核实）

`auth/security/quota/` 下 18 个类，这是 legacy 里**最扎实的一块**：

```
QuotaLedgerService / QuotaEnforcementService   账本与执行
QuotaReservation / QuotaSettlement             预留与结算（分离！）
QuotaWindow / QuotaDimension / QuotaLimitSet   窗口、维度、限额集
QuotaUsage / QuotaDecision / QuotaLimitViolation
RedisCounterBackend / LocalCounterBackend      双后端
QuotaTokenEstimator / QuotaCounterKey / QuotaCounterMetrics
```

**预留与结算在 legacy 里就是分离的** —— 这个判断是对的，直接搬。

配额放在 `auth/security/` 包下也说明了它的定位：它是**授权**的一部分，不是可观测的一部分。

## 2. 配额与限流的区别（不要合并）

| | 限流 | 配额 |
|---|---|---|
| 管什么 | **瞬时速率**（每秒/每分钟多少次） | **累计用量**（这个月用了多少 token / 多少钱） |
| 目的 | 保护系统不被打垮 | 控制成本与公平分配 |
| 单位 | 次数 | token / 金额 / 次数 |
| 拒绝后 | 稍后可重试 | 到窗口结束前都不可用 |

两者共用键前缀与 Redis，但**语义不同、不合并**。

## 3. 决定

**(a) 维度**：租户（必有）× 服务 / 模型（可选叠加）。`QuotaDimension` 可搬。

**(b) 窗口**：`MINUTE` / `HOUR` / `DAY` / `MONTH` 四个窗口同时记账，`QuotaWindow` 可搬。

**(c) 预留 → 结算**：
一次调用**先预留**（按预估上限），终态时按实际用量**结算**，差值回补。
> 这是为了**让并发下的超额可判定**。只在结束时检查，N 个并发会同时通过 —— 预留是唯一能挡住它的机制。

**(d) 严格口径 vs 宽松口径**：
严格模式只认上游回报的 usage；宽松模式允许本地估算兜底（`QuotaTokenEstimator` 可搬）。
**默认严格** —— 宽松模式下用量被低估，配额形同虚设。

**(e) 后端**：Redis 计数为主，本地窗口降级。

**(f) 默认关闭**（`airouter.quota.enabled=false`，沿用 legacy 的 opt-in 默认值）。

**(g) fail-closed 而非 fail-open。**
legacy 在 Redis 故障时默认 fail-open → **限额整体失效**，这是必须修掉的。
新版：计数后端不可用时按配置决定拒绝还是放行，**默认拒绝**，且这个行为要有断言。

## 4. 裁决

| legacy 的东西 | 裁决 |
|---|---|
| 预留/结算分离、多窗口、多维度 | **搬**（这是全套 legacy 里最值得直接搬的一块） |
| `QuotaTokenEstimator` | **搬**（降级为宽松口径下的兜底，不作为默认） |
| Redis/Local 双后端 | **搬**（改：Local 不再意味着放行） |
| fail-open 的默认值 | **不搬** —— 改成 fail-closed |
| `QuotaCounterMetrics` | **改**：并入 [0016](./0016-observability-metrics-tracing-and-alerts.md) 的统一指标，不单独一套 |

## 5. 验收

- 并发下的超额可判定：N 个并发请求同时到达时，超出的被拒绝（预留生效）
- 结算回补：实际用量小于预留时，差额回到额度里
- 四个窗口各自独立滚动，互不干扰
- Redis 不在时的行为**被断言**（默认拒绝；可配置为放行且会记日志与指标）
- 关闭时零开销（不开配额就不做任何计数）
- 失败转移只扣最终那一次（不重复扣）
