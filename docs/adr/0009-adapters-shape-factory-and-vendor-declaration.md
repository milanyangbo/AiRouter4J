# ADR-0009：适配器、形态工厂与厂商声明

- 状态：已接受
- 日期：2026-10-26
- 相关：[0006](./0006-wire-shape-and-shape-self-description.md)（形态）、[0010](./0010-configuration-model-and-runtime-registry.md)（实例持有形态）

能力面：**第 1 类（适配器）**

## 1. legacy 的事实

`router/adapter/impl/` 下 11 个类：

```
OllamaAdapter          GeminiAdapter        ClaudeAdapter          ← 三个真实现（三种形态）
NormalOpenAiAdapter    VllmAdapter          XinferenceAdapter
LocalAiAdapter         GpuStackAdapter                             ← 5 个各自硬编码的 OpenAI 兼容类
ConfigurableAdapter    ExtendedAdapter      OllamaConfigurableAdapter ← 3 个变体，不是厂商
```

**9 个厂商里只有 3 个是真正不同的协议**（Ollama / Claude→anthropic / Gemini）。
剩下是 5 个各自硬编码的 OpenAI 兼容类（差异只在默认地址与少量请求/响应转换，
另有 `VllmRequestTransformer`、`GpuStackResponseTransformer` 这类配套），
以及 3 个变体（`Configurable` / `Extended` / `OllamaConfigurable`）—— 它们不是厂商。
也就是说：**新增一个 OpenAI 兼容厂商，在 legacy 里要写一个类。**

另外 legacy 有 `AdapterDefinitionProperties` + `AdapterDefinitionPersistenceService` ——
一个声明式定义 + 落库持久化的机制。**它是对的方向，但默认没启用**：
`config/router/adapter.yml` 里 `deepseek:` 那一段是注释掉的示例，注释自己写着
「适用于 DeepSeek、智谱、月之暗面、百川、零一万物、通义千问、Minimax 等」——
**这套机制准备支持的厂商一个都没启用**，实际在用的还是那 11 个硬编码类。

## 2. 决定

**(a) 只有形态差异才写类。** 三个原生形态：`ollama` / `anthropic` / `gemini`；
第四个是 `openai` 兼容内核，服务全部 OpenAI 兼容厂商。

**(b) 厂商是声明，不是类。** 声明内容：默认 baseUrl、路径、是否需要凭证、可选的转换点。
声明可以写在配置里，也可以持久化到库，支持运行时新增。

```yaml
airouter:
  vendors:
    deepseek:
      shape: openai
      base-url: https://api.deepseek.com/v1
    gpustack:
      shape: openai
      base-url: http://gpustack:80/v1-openai
```

**(c) 形态工厂登记构造器。** 形态 → 构造器的映射登记在一个工厂里，
按实例的形态创建 Provider。**不在调用路径上按形态分支。**

> 「创建」发生在 `config` 的装配器里，注册表**只持有**结果 —— ADR-0010 第 2 节说的
> 「唯一持有者」是持有，不是创建。让注册表自己去调工厂，等于让它认识形态，
> 那正是「transport 不认识 providers」那条方向约束要挡的同一类事。
> （2026-10-27 复核：代码一直是这个形状，是这一句写错了。）

## 3. 裁决

| legacy 的东西 | 裁决 | 说明 |
|---|---|---|
| `OllamaAdapter` / `GeminiAdapter` / `ClaudeAdapter` | **改**（搬思路，不搬代码） | 它们夹着大量指标与 tracing 调用，要剥掉；协议翻译部分可参考 |
| `ConfigurableAdapter` / `NormalOpenAiAdapter` | **改** | 合并成一个 OpenAI 兼容内核 + 声明驱动 |
| 各厂商的 `*RequestTransformer` / `*ResponseTransformer` | **搬**（降级为配置钩子） | 差异点保留，但不以类的形式存在 |
| `AdapterDefinitionProperties` + `AdapterDefinitionPersistenceService` | **搬** | 这正是「任意 OpenAI 兼容厂商」的落地机制 |
| 11 个类里的指标/tracing 内嵌调用 | **不搬** | 观测靠 `OutcomeObserver`，不靠各适配器自己埋点（[0016](./0016-observability-metrics-tracing-and-alerts.md)） |

## 4. 每实例一个 Provider，而不是让 Provider 无状态

两条路：

| | A 让 Provider 无状态（调用时传 Endpoint） | **B 每实例一个 Provider（选）** |
|---|---|---|
| core 端口签名 | 必须改成 `complete(request, endpoint)`，**core 要动** | **一行不动** |
| Face | 要改注入与调用 | **一行不动** |
| 热重载 | 难：调用期传什么由外面决定 | 天然契合「替换注册表里的对象」 |
| 代价 | — | 对象数 = 实例数（几十个，无压力） |

走 B。注册表的责任是持有「实例 → Provider」的映射，并在配置变更时替换其中的对象。

## 5. 验收

- 三个原生形态各有一组契约测试（`contract-ollama/` 是先例）
- **新增一个 OpenAI 兼容厂商：只改配置，不改任何 Java 文件** —— 这一条要有测试守（断言按声明创建出的 Provider 能出网）
- 同一 Service 下两个实例走不同形态是合法配置，且被端到端测试覆盖
- `SecondProviderTouchesNoFaceTest`：新增形态不触碰 `faces` 与 `core`

## 6. 后果

- 9 个厂商里 3 个需要真代码，其余是配置
- 声明式厂商的持久化随 [0017](./0017-persistence-migrations-and-multi-replica.md) 的表结构一起落地
