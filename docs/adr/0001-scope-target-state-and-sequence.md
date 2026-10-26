# ADR-0001：目标态、能力面清单与顺序约束

- 状态：已接受
- 日期：2026-10-26
- 取代：第 1 版（骨架验证）与第 2 版（能力面路线图）全部作废，见文末「版本沿革」

## 1. 项目是什么

**AiRouter** —— 大模型 API 网关 + AI Agent 编排平台，是 `legacy/`（JAiRouter 的一份检出，
819 个主代码文件 / 149,148 行，外加 92 个 Vue 页面）的**全量重写**。

坐标 `io.github.airouter:airouter`。`legacy/` 不参与构建、不在 `<modules>` 里，它的角色见第 2 节。

## 2. 与 legacy 的关系：素材库，不是档案，也不是禁区

一句话：**以 legacy 为设计素材库，逐项评审后决定 搬 / 改 / 新增 / 不搬。**

「一律不搬」与「一律搬」都是懒政：前者浪费 149k 行里经过验证的部分，后者会把缺陷一起搬进来。

搬进来的代码必须过**三道关**：

1. **换坐标** —— 包名与术语换成新体系的（`Adapter` → `Face` / `Provider`，`ModelServiceRegistry` → `registry` 等）
2. **适配新契约** —— 只消费 `Outcome`；`core` 零依赖；Jackson 3；构造函数注入
3. **补它原本没有的测试** —— 尤其是失败路径与「依赖缺席」路径。legacy 的测试密度不足以支撑搬运

逐项裁决记录在 `docs/planning/legacy-review.md`，裁决结论散落在各能力面 ADR 的「裁决」小节。

已确认为**不搬**的三处（它们的存在本身就是缺陷）：

| 不搬的东西 | 为什么 |
|---|---|
| 94 个自研 tracing 类（`monitor/tracing`） | 自研 span/tracer，OpenTelemetry 已经解决；搬它等于把一套并行体系养起来 |
| `HttpConfigPushService` | 把全量配置推给一个并不存在的端点 |
| 4 套并存的 store / 版本服务 | 同一件事四种实现，不是抽象，是失控 |

已确认为**可搬**的部分（逐篇 ADR 里给出细节）：4 段限流 Lua、6 个负载均衡策略类与加权/粘滞实现、
无锁熔断状态机、`usageStrict` 语义、H2+PG 双方言 Flyway 骨架（V1–V4）、三档调用历史与 AES-256-GCM、
`AdapterDefinitionProperties` 声明式厂商机制、控制台 Vue 页面（适配而非重写）。

## 3. 目标态：九类能力面 + 认证

目标态是**补齐下面这张表里的能力**，不是「与 legacy 功能对等」。

判断一项工作该不该做，先问它补的是哪一类能力面。答不上来的，多半把实现细节当成了能力 ——
「legacy 有 20 张表，我们也得有 20 张」不是对齐。

| # | 能力面 | legacy 现状 | 新版目标态 | 落哪篇 |
|---|---|---|---|---|
| 1 | 适配器 | 9 个厂商；仅 Ollama / Claude / Gemini 为真实现，其余同套 OpenAI 兼容 + 默认地址差异 | 3 个原生形态（ollama / anthropic / gemini）+ 1 套 OpenAI 兼容；其余厂商是**配置声明** | [0009](./0009-adapters-shape-factory-and-vendor-declaration.md) |
| 2 | 负载均衡 | 6 个策略类 + 加权辅助 + 粘滞包装器 | 6 个纯函数策略 + 独立的加权步骤 + 粘滞包装器 | [0011](./0011-instance-selection-and-load-balancing.md) |
| 3 | 规则引擎 | `router/rule`，按模型/服务类型/头/IP/权重/标签匹配，带命中统计 | 同维度，**首命即止**，命中统计持久化 | [0012](./0012-rule-engine-and-conditional-routing.md) |
| 4 | 韧性 | 实例级熔断、请求级失败转移、限流四算法、响应缓存 | 同上，但限流**只留一套内核**（含管理面入口） | [0013](./0013-resilience-circuit-failover-ratelimit-cache.md) |
| 5 | 配额 | 多维度多窗口账本（MINUTE/HOUR/DAY/MONTH），Redis 计数 + 本地降级，默认关闭 | 同语义，但默认 **fail-closed** 且预留/结算分离 | [0014](./0014-quota-multi-window-ledger.md) |
| 6 | 记录治理 | 三档：仅元数据 / 脱敏摘要 / AES-256-GCM 全文；解密仅 ADMIN | 同三档，脱敏失败 fail-closed | [0015](./0015-call-history-and-record-governance.md) |
| 7 | 可观测 | Prometheus + 94 个自研 tracing + 慢查询 + 错误追踪 + 安全告警 | OTel 替代自研；**告警出口只有指标 + 结构化日志** | [0016](./0016-observability-metrics-tracing-and-alerts.md) |
| 8 | 持久化与多副本 | 默认 H2，`DATABASE_URL` 指 PG 时用 PG + Flyway；Redis 承载共享状态 | 同，但**只允许一套 store**，依赖缺席是契约 | [0017](./0017-persistence-migrations-and-multi-replica.md) |
| 9 | 控制台 | `/admin/` 的 Vue 3 界面，14 个页面组 | **最后做**，适配 legacy 前端而非重写 | [0019](./0019-admin-plane-api-and-console.md) |
| 10 | 认证与 RBAC | API Key + JWT + 角色权限 | 精简版先行（固定角色、接口级权限），控制台阶段再对齐全集 | [0018](./0018-authentication-api-key-and-rbac.md) |

## 4. 非目标（写下来是为了少争论）

- **不做告警推送通道**。仓内不实现邮件 / Webhook / IM 推送，出口只有 Prometheus 指标与结构化日志。请在 Alertmanager 或日志平台里接
- **不追求表数量对等**。表结构由新版的需求倒推，不由 legacy 的 20 张表倒推
- **不自研链路追踪**。用 OpenTelemetry
- **不引 JPA**。关系存储用 **MyBatis**，SQL 集中在 XML（理由见 [0002](./0002-tech-stack-and-version-pinning.md)；用法八条纪律见 [0017](./0017-persistence-migrations-and-multi-replica.md) 第 2 节）

## 5. 顺序约束（硬约束，不是排期建议）

三条，阶段内的先后可以调，这三条不行。要推翻就改本文件，不要在 AGENTS.md 或票里改。

1. **实例维度先于一切作用域类功能。**
   限流的作用域 `global/service/instance/client`、规则路由的目标服务、失败转移的换谁重试、配额的维度，
   全部以「一个服务下有多个实例」为前提。在实例维度建立之前做限流，做出来的是一个全局开关。
2. **A 地基 → B 广度 → C 深度 → D 控制台**。
   B 是九类各铺一层且每类都有测试；C 紧接 B，不得无限延后。
3. **控制台最后**，前置条件是管理面 API 契约已被契约测试钉住；且是适配 legacy 前端，不是重写页面。

阶段与任务清单见 [0020](./0020-stage-plan-and-task-board.md)。

## 6. 后果

- 每张能力面 ADR 的开头都要能回答「它补的是第几类能力面」
- legacy 的任何一段代码被引用时，必须有裁决记录（搬 / 改 / 新增 / 不搬 + 三道关是否走完）
- 新增能力面要先改本文件的表，再写它的 ADR

## 7. 版本沿革

| 版本 | 前提 | 为什么作废 |
|---|---|---|
| 第 1 版（0001–0011） | 骨架能不能立住还不知道 | 票 01–09 交付后这个未知项消失；它把 legacy 写成「不搬运」 |
| 第 2 版（2026-10-25 重写） | 目标态 = 九类能力面，但保留已实现代码 | 决定改为**清空实现、按功能重排**；它保留了大量「本阶段实现了什么」的叙述 |
| **第 3 版（本版）** | 代码已清空，按能力面从零实现 | — |

前两版仍可从 git 取回：第 2 版在 tag `baseline-pre-11`，第 1 版在更早的提交。
