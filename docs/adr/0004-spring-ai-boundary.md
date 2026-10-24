# ADR-0004: Spring AI 在分层中的边界

- **状态**：已接受（第 2 版，2026-10-25 重写）
- **日期**：2026-10-25
- **相关**：[0002 技术栈](./0002-tech-stack-and-version-pinning.md)、[0003 模块结构](./0003-module-structure-and-dependency-direction.md)

## 背景

Spring AI 2.x 是本项目引入 Agent 能力的理由（[0002](./0002-tech-stack-and-version-pinning.md)），
但它带来的依赖是重的：`ChatClient`、Advisor 链、向量存储、MCP、会话与压缩。

这里有一个真实的诱惑：既然 Spring AI 已经会「调模型」，那网关的出站调用干脆也用它 ——
一次引入，两处复用。来源项目没有这个问题（它不依赖 Spring AI），所以没有可继承的答案。

另一个必须回答的问题是**数据方向**：本项目的 Agent 主场景是 **Ops Agent**（查实例健康 / 熔断限流状态 /
调用历史 / 异常事件 / 配置变更审计），而它要查的数据全部由网关产生。
Agent 在依赖树上位于 `app → agent → core`，**不 Java 依赖 `gateway`** —— 那么它怎么拿到这些数据？

## 决策

### 1. 网关侧不引入 Spring AI，一点都不引入

| 位置 | Spring AI |
|---|---|
| `airouter-core` | **禁止**（依赖树零框架，[0003](./0003-module-structure-and-dependency-direction.md) R1） |
| `airouter-gateway` | **不引入** |
| `airouter-agent` | 全量引入 |

理由：网关的核心价值是「协议翻译 + 路由 + 治理」，这三件事 Spring AI 都不做，
而 `ChatModel` 的抽象恰好会盖住网关最需要的东西 —— **逐帧的字节与精确的错误分类**。
用 `ChatModel` 调模型，拿不到「上游 429 的那一帧长什么样」，也就无法把失败归因
（[0006](./0006-streaming-terminal-outcome-contract.md)）；拿不到分帧控制权，就无法保证
`CHUNK_PARSE_COUNT = 1`。

代价是出站调用要自己写（`transport` + `providers`）。这个代价是刻意承担的：
它换来的是错误可归因、分帧可控、以及不把网关绑在某个 AI 抽象的版本节奏上。

### 2. Agent 不 Java 依赖 gateway；需要网关数据时走 core 里的端口

`agent` 与 `gateway` 在依赖树上平行，唯一的交汇点是 `core`（端口）与 `app`（装配）。

Ops Agent 要的运维数据（实例健康、熔断状态、调用历史、配置变更）通过 **core 里定义的查询端口**获取，
由 `gateway` 提供实现，`app` 装配。

```text
agent ──(依赖)──→ core ──(接口 OpsQueryPort)──┐
                                              ├── 实现在 gateway，装配在 app
gateway ─(依赖)──→ core ───────────────────────┘
```

**不选择「Agent 通过 HTTP 调自己的管理面 API」**，理由：

- 回环调用引入鉴权负担（Agent 要持有一份凭据去调自己）
- 链路里多一跳网络，而这个调用发生在同进程内
- 管理面 API 的形状会随控制台需求变化，让它成为 Agent 的耦合对象会把两件事绑在一起

管理面 API 的消费者是**控制台与外部系统**，不是 Agent。两者的形状需求不同，不要复用成一条。

### 3. Advisor 链：安全语义必须双实现

`CallAdvisor` 与 `StreamAdvisor` 是**两个独立接口**（不是同一接口的可选方法）。
任何有安全语义的 Advisor（鉴权、脱敏、审计）必须**同时实现两者**，否则流式路径被静默跳过且不报错 ——
这是来源项目踩过的同一类坑（"只做一半 = 静默失效"）在 Agent 层的镜像。

这条由结构规则或测试断言，不靠 review 记得。

### 4. 会话与压缩：Trigger 与 Strategy 成对

长期对话需要压缩。压缩由 **Trigger（何时压）+ Strategy（怎么压）** 成对驱动，两者必须同时设置：
只设 Strategy 等于永不压缩，只设 Trigger 等于压缩时不知道怎么压 —— 两种情况都不报错。

压缩的最小原子粒度是 **Turn**（一个 UserMessage 之后的所有事件），
保证保留窗口永远从用户消息开始，不会切断工具调用序列。

### 5. Subagent 的定义是配置文件，不是代码

专职 Agent（Subagent）由 Markdown 配置定义，主 Agent 通过 Task 工具委派，只把结论返回主上下文。
这让「加一个专职角色」是一次配置变更，不是一次代码变更。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 网关出站也用 Spring AI 的 `ChatModel` | 盖住逐帧字节与精确错误分类；而这两者正是网关要修的核心缺陷（[0006](./0006-streaming-terminal-outcome-contract.md)） |
| Agent 直接 Java 依赖 gateway 拿数据 | 破坏依赖方向；`agent` 会随 `gateway` 的包重构而碎 |
| Agent 通过 HTTP 调自己的管理面 API | 回环 + 鉴权负担 + 与管理面 API 形状耦合 |
| 只实现 `CallAdvisor` | 流式路径被静默跳过，且不报错 |
| 只设压缩 Strategy 不设 Trigger | 等于永不压缩，且不报错 |
| Subagent 用 Java 类定义 | 加一个角色变成一次代码变更与一次发版 |

## 后果

**正面**

- 网关的错误归因与分帧控制权不被 AI 抽象稀释
- Agent 层可以独立演进 Spring AI 版本（受 [0002](./0002-tech-stack-and-version-pinning.md) 的协同变更约束）
- Ops Agent 的数据源就是本网关自己的可观测数据，演示时自洽

**负面**

- 出站调用全部自己写，`transport` 与 `providers` 的代码量无法靠框架省掉
- core 里要定义运维查询端口，等于为 Agent 增加了一组接口；这组接口是与 `gateway` 的契约，改动成本不低
- 两套「调模型」的方式并存（网关自写、Agent 用 Spring AI），新人需要理解为什么

## 如何验证

- [ ] `airouter-core` 与 `airouter-gateway` 的依赖树中不出现 `org.springframework.ai`
- [ ] `airouter-agent` 不 Java 依赖 `airouter-gateway`（结构规则 R2 / R7）
- [ ] 有安全语义的 Advisor 同时实现 `CallAdvisor` 与 `StreamAdvisor`（测试或结构规则断言）
- [ ] 压缩的 Trigger 与 Strategy 缺失其一时启动期报错
- [ ] Ops Agent 的运维查询走 core 端口，测试里不需要起 HTTP 服务即可断言
