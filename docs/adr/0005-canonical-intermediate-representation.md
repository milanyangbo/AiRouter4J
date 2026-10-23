# ADR-0005: Canonical 中间表示

- **状态**：已接受
- **日期**：2026-10-23
- **相关**：[0003 模块结构](./0003-module-structure-and-dependency-direction.md)、[0004 Spring AI 边界](./0004-spring-ai-boundary.md)、[0006 流式终态](./0006-streaming-terminal-outcome-contract.md)

## 背景

来源项目的协议翻译是**两两直接互译**。`BaseAdapter` 526 行 + 15 个实现 + 若干 transformer，
其中 Anthropic 方向的两个翻译器分别有 723 行与 923 行。

这种结构的成本随协议数量**相乘**增长：N 个 face × M 个 provider 意味着 N×M 条翻译路径。
每加一个 provider，就要为每一个已支持的 face 补一条路径。

还有一个更隐蔽的成本：**同一个流式 chunk 被反复解析**。评审里记录到的情况是同一份 JSON
在一次处理流程中被 `readTree` 四次 —— 每一处只取自己需要的几个字段，且分散在不同类里，谁也不知道别人已经解过一次。这不是设计决定，是没有收口点的自然结果。

## 决策

### 1. 引入 Canonical 作为唯一收口点

方向变成 N + M 而不是 N×M：

```text
外部请求 ──face──▶ CanonicalRequest ──provider──▶ 厂商 wire format
厂商响应 ──provider──▶ CanonicalStreamEvent ──face──▶ 外部响应
```

Canonical 定义在 `airouter-core`，是不带任何框架标注的 Java 类型。

```java
public record CanonicalRequest(
    String requestId,
    String serviceId,
    String model,
    List<CanonicalMessage> messages,
    List<CanonicalTool> tools,
    GenerationParams params,      // temperature / maxTokens / ...
    boolean stream,
    Map<String, String> passthrough  // 无法归一的厂商扩展字段
) {}
```

`passthrough` 是刻意的：协议里总存在无法映射的厂商自有字段，
如果没有这个地方，它们会被塞进某个不合适的字段里，然后很难再取出来。

### 2. 流式事件类型

```java
public sealed interface CanonicalStreamEvent permits
    CanonicalStreamEvent.TextDelta,
    CanonicalStreamEvent.ToolCallDelta,
    CanonicalStreamEvent.Usage,
    CanonicalStreamEvent.Ping,
    CanonicalStreamEvent.Terminal,
    CanonicalStreamEvent.Failed {}
```

`Terminal` 与 `Failed` 是 [ADR-0006](./0006-streaming-terminal-outcome-contract.md) 的载体 ——
终态是事件模型的一等成员，而不是在 Reactor 的操作符里隐含。

`Ping` 保留为显式类型：部分上游会定期发送注释行保活，它们在语义上不是内容帧，
也不应该被当成终态判断的依据。

### 3. 一次解析，多次使用

**CHUNK_PARSE_COUNT = 1**：同一次处理流程中，同一份上游字节只允许被解析一次。
provider 的职责是把字节变成 `CanonicalStreamEvent`；此后所有 consumer（脱敏、指标、
配额、会话记录、face 编码）都操作已经成形的对象。

这条可以直接被计数型集成测试验证。

### 4. 为什么不用 Spring AI 的消息模型代替

见 [ADR-0004](./0004-spring-ai-boundary.md)：那是沿另一个方向设计的模型（应用侧消费），
并且带着的行为假设与网关冲突。Canonical 必须能表达网关才关心的东西 ——
上游偏差、未知字段、保活帧、缺终态 —— 这些在应用侧模型里没有位置。

### 5. 渐进收口，不一次性铺满

**这是本 ADR 里最重要的取舍。**

Canonical 不会因为它的引入而立刻消灭所有翻译路径。推进顺序：

| 阶段 | 做什么 | 依据 |
|---|---|---|
| 第一步 | 所有 **provider 的响应**统一译成 `CanonicalStreamEvent` | 这是终态契约（0006）的前提，也是 CHUNK_PARSE_COUNT=1 的前提；这一步收益最大、影响面最清晰 |
| 第二步 | 所有 **provider 的请求**统一从 `CanonicalRequest` 编码 | 请求侧没有流式的复杂性，风险低于响应侧 |
| 第三步 | face 侧收口 | **只有在真正出现第三个 face 时才做**。两个 face 时收口的收益还不足以抵消重写已验证页面的风险 |

现阶段 face 只有两个（OpenAI 兼容、Anthropic）。过早把已经通的 face 重画一遍，
是把低风险的东西变成高风险改动，不划算。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 保持两两互译 | 加 provider 的成本是乘以 face 数量的；且无法解决重复解析问题 |
| 引入 Canonical 的同时把所有 face / provider 重画 | 已经在工作的代码变成未验证代码；收益（对称美）撑不住这个风险 |
| 让 Canonical 直接复用某个厂商的请求格式（如直接用 OpenAI 格式当中枢） | 会让中枢带着某一方的偏见：另一方协议里表达不了的东西会变形，且这个变形很难被发现 |
| 用 Map / JsonNode 作为中间表示，不定义类型 | 编译检查全失；改一处字段名不会报错，只会在线上静默失效 |
| 把所有厂商字段都显式建模，不设 passthrough | 每次对接新实例都要改 Canonical；而这些字段网关本身并不解释，建模只会带来维护负担 |

## 后果

**正面**

- 新增 provider 不需要动任何 face 的代码（可测）
- CHUNK_PARSE_COUNT=1 直接降低 CPU 开销，在高并发流式场景是实打实的
- 流式终态、脱敏、配额等后继能力有了统一的挂载点

**负面**

- 多一层转换意味着**多一点开销与一点延迟**。这是真实成本，换来的是可扩展性
- Canonical 的设计一旦定错，纠正成本比两两互译高很多（所有方向都受影响）——
  因此第一步只收口 provider 响应，就是在给这个设计争取验证时间
- `passthrough` 是个逃逸口，缺乏约束时会被滥用。

## 如何验证

- [ ] 存在 golden-file 契约测试：同一份上游字节 → 期望的 `CanonicalStreamEvent` 序列
- [ ] 计数断言：一次流式处理中对同一 chunk 的 JSON 解析次数为 1
- [ ] 新增一个 provider 的改动集中在 `providers` 包内，`faces` 包零改动
- [ ] `airouter-core` 中的 Canonical 类型不含任何 Jackson / Spring 标注
