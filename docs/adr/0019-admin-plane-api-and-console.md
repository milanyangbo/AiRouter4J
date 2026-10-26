# ADR-0019：管理面 API 与控制台

- 状态：已接受
- 日期：2026-10-26
- 相关：[0001](./0001-scope-target-state-and-sequence.md) 第 5 节（控制台最后）、[0018](./0018-authentication-api-key-and-rbac.md)

能力面：**第 9 类（控制台）**

## 1. 管理面是接口，控制台是页面

两个词不要混用（术语表里有明确区分）：

- **管理面**（admin plane）：网关自己的配置与运维接口。它是控制台的数据来源，也是 RBAC 的作用对象
- **控制台**（console）：管理面的 Web 界面

**顺序是硬约束**：先把管理面 API 的契约钉住，**再做页面**。反过来做会出现「页面能点但接口语义没定」。

## 2. 管理面 API 的覆盖面

对齐 legacy 前端需要的十一个面：

服务、实例、适配器（厂商声明）、规则、版本与回滚、配额、调用历史、链路追踪、告警、API Key、RBAC。

**其中两个面的数据不在我们这边**，管理面 API 不为它们提供取数：

| 面 | 数据在哪 | 控制台做什么 |
|---|---|---|
| 链路追踪 | OTel 后端（我们只负责导出） | 只给出入口，不实现存储与查询 |
| 告警 | Alertmanager / 日志平台（出口只有指标 + 结构化日志，[0016](./0016-observability-metrics-tracing-and-alerts.md) 第 2 节 f） | 只给出入口，不实现告警存储与推送 |

> 否则等于在仓内实现一套链路与告警存储 —— 那是一条被明确否掉的路（[0001](./0001-scope-target-state-and-sequence.md) 第 4 节）。

另外：**RBAC 全集对齐在本阶段完成**（[0018](./0018-authentication-api-key-and-rbac.md) 第 4 节把「接口级权限 → 全集」推迟到这里）。
前置阶段只做精简版（固定角色、接口级权限），本阶段按控制台需要的权限粒度对齐。

管理面限流走**同一内核**（[0013](./0013-resilience-circuit-failover-ratelimit-cache.md) 第 3 节 d），
作用域是管理面路径 / 管理员 —— 不是另一套 WebFilter。

## 3. 控制台：适配 legacy 前端，不重写

legacy 前端是 Vue 3，`frontend/src/views/` 下 **14 个页面目录**（另有 3 个 `.vue` 根文件与 `__tests__`）：

```
dashboard  config  load-balancer  rate-limiter  circuit-breaker
callHistory  tracing  monitoring  exception  security
system  login  playground  tools
```

**适配，不是重写。** 保留的还有：深/浅主题、中英双语。
> 重写一套页面的工作量与风险都由前端承担，而它换不来任何网关侧的能力。

适配的前提是**管理面 API 契约已被契约测试钉住** —— 前端改的是取数，不是猜接口。

## 4. 版本与回滚在控制台上长什么样

配置版本列表 → 选一版 → diff → 回滚。
接口只提供「版本号 + 变更事件」，**不提供全量配置的推送**。

## 5. 裁决

| legacy 的东西 | 裁决 |
|---|---|
| 92 个 `.vue` 页面 | **搬**（适配：改取数层与字段命名） |
| 深/浅主题、中英双语 | **搬** |
| 管理面限流 WebFilter | **不搬**（并入同一内核） |
| 告警推送通道（若有前端配置） | **不搬** —— 出口只有指标与日志（[0016](./0016-observability-metrics-tracing-and-alerts.md) 第 2 节 f） |

## 6. 验收

- 每个页面组对应的管理面 API 有契约测试（请求/响应形状被钉住）
- 管理面接口的鉴权被断言：未带 ADMIN 拒绝
- 回滚接口：回到上一版后，运行时对象回到等价状态
- 前端构建通过（不做视觉回归，只保证取数契约对得上）
- 控制台阶段不新增网关侧能力 —— 它只消费已有的管理面 API
