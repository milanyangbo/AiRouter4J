# ADR-0009: 第二种 wire 形态与「形态自述」

- **状态**：已接受
- **日期**：2026-10-24
- **相关**：[0003](./0003-module-structure-and-dependency-direction.md) 模块结构与依赖方向、
  [0005](./0005-canonical-intermediate-representation.md) Canonical 中间表示、
  [0006](./0006-streaming-terminal-outcome-contract.md) 流式终态契约、
  [0008](./0008-verification-and-quality-gates.md) 验证策略

## 背景

[0005](./0005-canonical-intermediate-representation.md) 的一条正面后果是
「新增一个 Provider 不需要改动 Face」，理由是 N×N 被收敛成 N+N：
Face 只认识 Canonical，Provider 只认识 Canonical 与自己那一种 wire format。

只有一个 Provider 时，这条主张**无法被推翻**。它写在文档里，
而它的成立与否取决于「真的加第二个 Provider 时会发生什么」——
在那之前，它与来源项目里那些「看起来成立、永远不会红」的声明没有区别。
来源项目最不缺的就是这一类句子。

因此需要一个第二种形态**确实不同**的 Provider。挑谁不是随意的：
本机 Ollama 同时提供两种接口（`/v1` 兼容与 `/api` 原生），
而原生接口与 OpenAI 兼容形态的差异是结构性的，不是命名习惯：

| 方面 | OpenAI 兼容 | Ollama 原生（本机实测） |
|---|---|---|
| 路径 / 地址形态 | `/chat/completions`，地址以 `/v1` 结尾 | `/chat`，地址以 `/api` 结尾 |
| 生成参数 | 顶层 `temperature` / `max_tokens` | 嵌在 `options`，且 `max_tokens` 叫 `num_predict` |
| 凭证 | `Authorization: Bearer` | 无 |
| 内容位置 | `choices[0].message.content` | `message.content`，没有 choices 这一层 |
| 停止原因 | `finish_reason` | `done_reason` |
| 用量 | `usage{prompt_tokens, completion_tokens, total_tokens}` | 顶层 `prompt_eval_count` / `eval_count`，**没有合计** |
| 流标识 / 创建时间 | `id`、Unix 秒 | 没有 id、`created_at` 是 RFC 3339 字符串 |
| 流式分帧 | SSE：`data:` 前缀 + 空行分隔 + `[DONE]` 哨兵 | **逐行 JSON**：无前缀、无空行、无哨兵 |
| 流怎么结束 | 上游发 `[DONE]` | 最后一帧带 `done:true` |
| 错误体 | `error.message`（对象） | `error`（字符串） |

最后三条是本 ADR 存在的主要理由。它们决定了「Provider 什么时候发终态」，
而那是 [0006](./0006-streaming-terminal-outcome-contract.md) 里最容易被写错的一处：
没有哨兵可认的时候，「说完了」只能从帧的内容里读出来。

## 决策

### 1. 分帧方式属于 wire 形态，不属于传输层的配置

新增 `LineFrameReader`（逐行 JSON），与 `SseFrameReader`（SSE）**并列**，
不是同一个读法的两种参数。

理由：两种约定在字节层就不同（前缀、分隔符、结束标志），
把它们塞进一个读法意味着「这一行是数据还是别的」要靠猜 —— 猜错的后果是
一个被截断的 JSON 被当成完整事件交给下游。
反过来，`OutboundWire` 上新增的 `postLines` 与 `postStream` 只差分帧一步：
超时、在途记账、非 2xx 的处理都不随形态变化，它们共用一段代码。

「SSE 的读法读不出逐行 JSON」现在是一条**被断言着的**事实：
`SecondProviderContractTest` 里每一条流式用例都要求解析次数等于帧数，
把 `postLines` 换成 `postStream` 之后这些用例变红（解析次数为 0）。

### 2. 形态自述：地址规则与凭证要求由形态回答

`UpstreamShape` 回答两个问题：地址该长什么样、要不要凭证。

此前「地址必须以 `/v1` 结尾」与「必须有密钥」是写在配置校验里的全局规则，
而它们其实只是 OpenAI 兼容端点的事实——第二条尤其明显：
Ollama 原生接口没有「证明你是谁」这一步，
把本地档位那个占位串当 bearer 发出去，只会让读日志的人以为这次调用被授权过。

**凭证缺失的校验留在校验器里而不是构造器里**，理由是：
构造器抛异常时，同一个对象上其它字段的校验消息一条也拿不到，
于是「三个变量全缺」会变成「只报缺密钥」。缺值要能被一次说全。

### 3. 走哪一种形态由配置决定，不由代码分支决定

`airouter.endpoint.provider`（`openai` / `ollama`）决定装配哪一个 Provider，
做法是 `@ConditionalOnProperty` 而不是方法里的 `if`：
前者是装配期读配置后的取舍，不在调用路径上；
后者一旦出现，「新增一种形态」就变成「回到这里加一个分支」。

### 4. 结算纪律只有一份

两种形态的流式路径共用 `StreamSettlement`（恰好结算一次、终态最后、
上游没说完就补发失败）。抽出来不是为了消除重复，而是因为这条纪律只有一份才成立：
在两个 Provider 里各写一遍，等于承认它可以有两份实现，
那么「某一个 Provider 少结算一次」不会与任何东西冲突。

形态差异仍然存在的唯一位置是「一帧字节 → 哪几个事件」，以及
「话没说完就断了」那句话怎么讲（有的形态有哨兵可以提，有的没有）。

### 5. 不编造上游没给的数字

原生接口给两个计数，不给合计。`CanonicalUsage.totalTokens` 因此是 0，
含义是「上游没有给」；明细（含 `prompt_eval_duration` 等）原样带出境。
把它改成两个计数相加会让对账的人以为这个数对得上，而事后无法区分它是算出来的还是编出来的。

同理，原生接口不返回流标识，`Opening` 带的是空串而不是一个编造的 `chatcmpl-` 前缀。

## 被否决的方案

**让 SseFrameReader 兼容逐行 JSON**（遇到不带 `data:` 前缀的行就当数据）。
否决理由：它把「这一行到底是什么」变成一个只能靠猜的问题，
而两种约定的差异恰恰在字节层就已经存在。兼容性读法不会有报错，
只会在某一次上游多写了一个空行的时候给出一个被截断的事件。

**按地址猜形态**（地址里有 `/v1` 就是兼容端点）。
否决理由：本机 Ollama 同时提供两种接口，`/v1` 与 `/api` 都在同一个主机上。
按地址猜在这类端点上必然猜错一次，而且错的时候不报错。

**让 Provider 自己 new 一个 HTTP 客户端**（绕开 `OutboundWire`）。
否决理由：SSRF 守卫要求所有出站经过唯一出口；
多一个客户端就等于多一处不需要遵守超时、连接池与守卫的地方。

**给 CanonicalResponse 加一个 created 字段**（把 `created_at` 装进 Canonical）。
否决理由：那是 `core` 的改动，而本 ADR 要验证的正是「不加 core 也能接进第二种形态」。
现在换算结果以客户端那一侧的形态走 passthrough ——
OpenAI 兼容形态的 `created` 本来就走这条路，两种形态因此一致。
代价是 Canonical 的非流式响应里没有创建时间，这个缺口被记下而不是被抹掉。

## 后果

- 结构规则仍是九条，没有新增：新代码落在 `providers` 与 `transport` 包内，
  R3（faces 与 providers 互不感知）、R5（transport 不知业务包）都未被触动。
- `EndpointProperties` 多了一个字段，因此它的构造签名变了 ——
  受影响的是直接构造它的测试，共 12 处。
- 真机测试从 2 条变成 4 条：同一台本机 Ollama 上，兼容接口与原生接口各两条路径。
- 门禁里多了一类断言：**差异统计**（`SecondProviderTouchesNoFaceTest`）。
  它是唯一会红的「加 Provider 别碰 Face」，也是本 ADR 第 1 节的验证手段。

## 如何验证

```bash
./mvnw clean verify          # 86 个测试，含下面几条
```

| 断言 | 落在哪里 |
|---|---|
| 自基线 `baseline-pre-09` 以来，Face 与 `core` 没有任何文件被改动 | `SecondProviderTouchesNoFaceTest.noFaceOrCoreFileIsTouched` |
| 第一个 Provider 的 golden 基线一个字节都没改 | `SecondProviderTouchesNoFaceTest.firstProviderBaselinesAreUntouched` |
| 原生字节 → OpenAI 形态的帧序列（含「上游没说完」与 4xx 两类） | `SecondProviderContractTest`（`src/test/resources/contract-ollama/`） |
| 两个形态发出去的路径、参数位置、凭证都不同 | `WireShapeIsChosenByConfigurationTest` |
| 配置成 `ollama` 之后真机两条路径都能通 | `OllamaNativeEndToEndTest` |

反向验证（都做完并还原）：

1. 把 `postLines` 换成 `postStream` —— 流式契约用例红（解析次数 0）。
2. 让 `toEvents` 认不出 `done:true` —— `native-stream` 用例红（流没有终态，被判为 `upstream_closed`）。
3. 在 `OpenAiFace` 里加一行注释 —— 差异断言红。
4. 改第一个 Provider 的任一 golden 基线一个字符 —— 基线未改断言红。
