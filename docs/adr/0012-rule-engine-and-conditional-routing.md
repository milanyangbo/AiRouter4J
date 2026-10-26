# ADR-0012：规则引擎与条件路由

- 状态：已接受
- 日期：2026-10-26
- 相关：[0010](./0010-configuration-model-and-runtime-registry.md)、[0019](./0019-admin-plane-api-and-console.md)（可视化编辑）

能力面：**第 3 类（规则引擎）**

## 1. legacy 的事实（已核实）

`router/rule/` 下 8 个类：

```
RuleEngineService              规则匹配的执行者
RuleDefinition / RuleDecision  规则定义与匹配结果
RuleStatsService / RuleStat    命中统计
RuleDefinitionProperties       配置绑定
RuleDefinitionPersistenceService / RuleTemplate / RuleTemplateService   持久化与模板
```

**命中统计在 legacy 里是一等公民**（`RuleStatsService` + `RuleStat`）—— 这是对的，可搬。

## 2. 决定

**(a) 匹配维度六种**：模型名、服务类型、请求头、客户端 IP、权重、实例标签。

| 维度 | 语义 | 备注 |
|---|---|---|
| 模型名 | 精确或前缀匹配请求的 model | — |
| 服务类型 | `chat` / `embedding` / `rerank` / `tts` / `image` | 决定走哪套 Canonical 形态 |
| 请求头 | 任意头的存在性或取值匹配 | 用于灰度与租户分流 |
| 客户端 IP | CIDR 匹配 | — |
| 权重 | **同一优先级下多条规则都命中时**的加权挑选 | 与「实例权重」是两件事，不要混 |
| 实例标签 | 目标实例必须带的标签（如 `gpu:a100`） | 标签是**筛**，权重是**选** |

**(b) 首命即止，按优先级排序。**
规则按优先级从高到低匹配，**第一条命中的规则决定目标服务**，不再继续匹配。
> 不做「全部命中后合并」：合并会让「为什么走到这个服务」无法回答。

**(c) 命中统计必须持久化。**
它是规则调试与权重调优的依据。只放在内存里的话，重启即清零，看不出趋势 —— 那等于没有统计。

**(d) 无匹配时显式失败或走显式默认。**
没有规则命中，且没有配置默认服务 → **拒绝**（fail-closed），而不是随便挑一个实例。
> 「挑一个」表现为偶发的错路由，是最难查的一类缺陷。

**(e) 规则可持久化、可可视化编辑。**
定义落库（随 [0017](./0017-persistence-migrations-and-multi-replica.md) 的表结构），
由管理面 API 提供增删改与命中统计查询（[0019](./0019-admin-plane-api-and-console.md)）。

## 3. 裁决

| legacy 的东西 | 裁决 |
|---|---|
| `RuleDefinition` / `RuleDecision` | **搬**（改坐标与术语） |
| `RuleStatsService` / `RuleStat` | **搬** —— 命中统计是一等公民这个判断是对的 |
| `RuleTemplate` / `RuleTemplateService` | **改**：模板降为「预置规则的快捷方式」，不做独立的第二套机制 |
| `RuleDefinitionPersistenceService` | **搬**，但改成走统一 store（legacy 有 4 套 store，不搬那部分） |

## 4. 验收

- 首命即止：两条规则都匹配时，走优先级高的那条（有断言）
- 权重只在同优先级多条命中时生效（有断言）
- 命中统计在服务重启后仍在（持久化断言）
- 无匹配且无默认 → 拒绝，且不产生出网调用
- 实例标签筛选：目标服务下无带标签实例 → 拒绝而非忽略标签
- 规则定义写坏（非法 CIDR / 未知服务类型）→ 拒绝加载并报出具体规则，不是静默跳过

## 5. 后果

- 规则引擎是选择链第 ② 步，位于认证之后、可用性闸门之前
- 规则匹配**不做 IO**：候选由注册表提供，命中统计的写入是异步的（不阻塞请求）
