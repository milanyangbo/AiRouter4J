# ADR-0007：Spring AI 的边界

- 状态：已接受
- 日期：2026-10-26
- 相关：[0003](./0003-module-structure-and-dependency-direction.md)（`airouter-agent` 的职责）

## 1. 决定

**Spring AI 只出现在 `airouter-agent`。网关侧（`gateway` / `core`）零引入。**

`airouter-agent` 通过 **core 的端口**拿网关数据，**不走回环 HTTP**（不起一个本地 HTTP 客户端打自己的 8080）。

## 2. 为什么网关不用 Spring AI 的 ChatModel

Spring AI 的 `ChatModel` 是一个不错的**客户端**抽象，但网关要做的三件事与它的假设冲突：

| 网关要做的 | ChatModel 的假设 | 冲突点 |
|---|---|---|
| 每次调用**按实例选择** baseUrl 与凭证 | 构造期绑定一个端点 | 网关要在运行期换端点；用 ChatModel 就得为「每个实例 × 每个形态」预建一个 bean |
| 精确控制**分帧与终态** | 它把流式封装成 `Flux<String>` | [0005](./0005-streaming-terminal-outcome-contract.md) 要求终态是**事件**且可结算；`Flux<String>` 里没有「上游没收尾就断开了」这个信息 |
| 形态差异留在 codec 一层 | 各家 model 各自一套 option 类型 | 会把厂商类型渗进网关的领域模型 |

结论：网关用 WebClient 直接出网，形态差异留在 `providers/<形态>/`。
这不是拒绝框架，是**网关的抽象层级比 ChatModel 低一层**。

## 3. Agent 侧怎么用

- `airouter-agent` 里 Spring AI 是**一等公民**：ChatClient 装配、Advisor 链、Tool、Subagent 都用它
- Agent 要读网关的运维数据（实例健康、熔断状态、调用历史、配置变更审计）时，
  走 core 定义的端口，由 `app` 装配进真实实现。**不走 HTTP 回环** —— 回环会引入序列化、端口、超时，
  这些与「读一份数据」无关，却会在测试里变成噪声

## 4. Advisor 的安全语义必须成对

`CallAdvisor` 与 `StreamAdvisor` 是**两个独立接口**。
任何有安全语义的 Advisor（鉴权、审计、脱敏）必须**同时实现两者**，
否则流式路径被静默跳过且不报错 —— 这是「只做一半 = 静默失效」的一个具体形态。

## 5. 后果

- `gateway` 的依赖树里出现 `org.springframework.ai.*` 即违反本 ADR（结构规则可加一条）
- Agent 侧新增能力不需要动网关；网关新增形态不需要动 Agent
