# ADR 索引

**架构决策记录（Architecture Decision Record）**。`docs/adr/` 是技术决策的**唯一权威**。

- 动手写一个模块之前，先读与之相关的 ADR
- 产出与某条 ADR 冲突时：**显式指出**（编号 + 为什么值得重开），**不要静默覆盖**
- 收录门槛是「未来会有人想推翻的决定」。实现细节、排期微调、代码风格不进 ADR

编号**按功能组织**：0001–0008 是地基与契约，0009–0019 是九类能力面各一篇，0020 是阶段排期。
编号一旦分配就不再复用；被推翻的篇目移入 `archive/` 并在原编号上重写。

## 索引

| 编号 | 标题 | 能力面 | 状态 |
|---|---|---|---|
| [0001](./0001-scope-target-state-and-sequence.md) | 目标态、能力面清单与顺序约束 | — | 已接受 |
| [0002](./0002-tech-stack-and-version-pinning.md) | 技术栈与版本锁定 | — | 已接受 |
| [0003](./0003-module-structure-and-dependency-direction.md) | 模块结构与依赖方向 | — | 已接受 |
| [0004](./0004-canonical-intermediate-representation.md) | 领域模型与中间表示（Canonical） | — | 已接受 |
| [0005](./0005-streaming-terminal-outcome-contract.md) | **流式终态与结算契约** | — | 已接受 |
| [0006](./0006-wire-shape-and-shape-self-description.md) | wire 形态与形态自述 | 1 适配器 | 已接受 |
| [0007](./0007-spring-ai-boundary.md) | Spring AI 的边界 | — | 已接受 |
| [0008](./0008-verification-and-quality-gates.md) | 验证与质量门禁 | — | 已接受 |
| [0009](./0009-adapters-shape-factory-and-vendor-declaration.md) | 适配器、形态工厂与厂商声明 | **1** | 已接受 |
| [0010](./0010-configuration-model-and-runtime-registry.md) | 配置模型与运行时注册表 | 1 / 2 地基 | 已接受 |
| [0011](./0011-instance-selection-and-load-balancing.md) | 实例选择与负载均衡 | **2** | 已接受 |
| [0012](./0012-rule-engine-and-conditional-routing.md) | 规则引擎与条件路由 | **3** | 已接受 |
| [0013](./0013-resilience-circuit-failover-ratelimit-cache.md) | 韧性：熔断 / 失败转移 / 限流 / 缓存 | **4** | 已接受 |
| [0014](./0014-quota-multi-window-ledger.md) | 配额（多维度多窗口账本） | **5** | 已接受 |
| [0015](./0015-call-history-and-record-governance.md) | 调用历史与记录治理 | **6** | 已接受 |
| [0016](./0016-observability-metrics-tracing-and-alerts.md) | 可观测性（指标 / 链路 / 慢查询 / 告警） | **7** | 已接受 |
| [0017](./0017-persistence-migrations-and-multi-replica.md) | 持久化、迁移与多副本 | **8** | 已接受 |
| [0018](./0018-authentication-api-key-and-rbac.md) | 认证、API Key 与 RBAC | **10** | 已接受 |
| [0019](./0019-admin-plane-api-and-console.md) | 管理面 API 与控制台 | **9** | 已接受 |
| [0020](./0020-stage-plan-and-task-board.md) | 阶段排期与任务清单 | — | 已接受 |

> 0005（流式终态与结算）是全套里最重要的一篇：它修的是 legacy 最贵的缺陷，
> 也是 0013 / 0014 / 0015 / 0016 的共同前提。

## 阅读顺序

```text
0001 目标态 ──┬── 0002 技术栈 ── 0003 模块结构
              ├── 0004 Canonical ── 0005 流式终态（最重要）
              │                  └── 0006 形态 ── 0009 适配器
              ├── 0007 Spring AI 边界
              └── 0008 验证门禁

实例维度（先于一切作用域类功能）：0010 注册表 ── 0011 选择
然后：0017 持久化 ── 0018 认证 ── 0013 韧性 ── 0012 规则 ── 0014 配额 ── 0015 记录 ── 0016 观测
补齐：0009 适配器 ── 0017 多副本 ── 0019 控制台
排期：0020
```

## 如何新增

1. 先确认它补的是 [0001](./0001-scope-target-state-and-sequence.md) 第 3 节里的哪一类能力面
2. 取下一个未使用的编号，文件名 `00NN-<短横线英文短语>.md`
3. 正文至少回答：背景（含 legacy 对照）、决定、裁决（搬 / 改 / 新增 / 不搬）、验收
4. 回到本文件补一行索引

## 版本沿革

| 版本 | 前提 | 取回方式 |
|---|---|---|
| 第 1 版（0001–0011） | 骨架能不能立住还不知道 | `archive/` + 更早的提交 |
| 第 2 版（2026-10-25 重写，13 篇） | 目标态 = 九类能力面，但保留已实现代码 | tag `baseline-pre-11` |
| **第 3 版（本版，20 篇）** | 代码已清空，按能力面从零实现 | HEAD |
