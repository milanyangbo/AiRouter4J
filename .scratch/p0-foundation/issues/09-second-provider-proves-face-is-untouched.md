# 09: 用第二个 Provider 验证 Face 侧零改动

> 来源：[spec.md](../spec.md) — 用户故事 30

**What to build:** 接入第二个 wire 形态确实不同（帧命名或 usage 字段形态有别）的 Provider，并且整个改动完全落在 Provider 自己的范围内，Face 侧与 `core` 的差异统计为空。

这是 ADR-0005 消除 N×N 那条主张的唯一实证手段。不实际加第二个 Provider，「加 Provider 不用改 Face」就只是一句写在文档里的声明 —— 而来源项目最不缺的就是这类无法被推翻的声明。

**Blocked by:** 06 流式 SSE 路径与显式终态

**Status:** done（2026-10-24，基线 `baseline-pre-09`）

- [x] 接入第二个 Provider，其 wire 形态在至少一个具体方面与第一个不同（例如 SSE 帧命名、usage 字段形状、私有字段位置）
      → **Ollama 原生接口**（`/api/chat`）。差异远不止一个方面：分帧是逐行 JSON 而不是 SSE、没有结束哨兵、
      生成参数在 `options` 里、用量是平铺的 `prompt_eval_count` / `eval_count` 且没有合计、
      没有流标识、创建时间是 RFC 3339 字符串、错误体是字符串而不是对象。
      清单见 `OllamaUpstreamCodec` 的 javadoc 与 ADR-0009。
- [x] 该改动在 Face 侧的文件上没有任何变更（以差异统计断言：本次变更触及的 Face 侧文件数为 0）
      → `SecondProviderTouchesNoFaceTest.noFaceOrCoreFileIsTouched`
- [x] 该改动在 `core` 上没有任何变更
      → 同上（同一条断言里同时看 `airouter-core/` 与 `/faces/`）
- [x] 第一个 Provider 的全部 golden-file 基线保持通过且未被修改
      → `SecondProviderTouchesNoFaceTest.firstProviderBaselinesAreUntouched`
- [x] 两个 Provider 通过同一个对外接口路径可达，由配置决定走哪一个，而不是代码分支
      → `airouter.endpoint.provider`：`@ConditionalOnProperty` 两个 `@Bean`，没有 `if`。
      `WireShapeIsChosenByConfigurationTest` 断言两者发出的路径 / 参数位置 / 凭证都不同。
- [x] 第二个 Provider 同样具备自己的端对端覆盖：同步与流式两条路径都能通
      → `OllamaNativeEndToEndTest`（打本机真实的 `/api/chat`）+ `SecondProviderContractTest`
      （`src/test/resources/contract-ollama/`，四组样本全部来自真实响应，只有 4xx 那一组手写）
- [x] 新增这个 Provider 的过程中没有引入任何新的 Face 侧条件分支 —— 若出现，说明抽象体表错了，应当回来修正而不是接受
      → 没有。Face 侧文件在本票里连一行都没改（差异断言守着）。

## 落地记录

**两个连带的结构变化**（都是为了让差异只出现在形态该出现的地方）：

1. `StreamSettlement` —— 流式结算纪律从 OpenAI Provider 里抽出来，两种形态共用。
   抽它的理由不是「长得像」，而是「恰好结算一次」只有一份才成立：
   各写一遍等于承认它可以有两份实现。
2. `LineFrameReader` 与 `OutboundWire.postLines` —— 逐行 JSON 的分帧。
   与 SSE 那一侧**并列**而不是同一个读法的参数：两种约定的差异在字节层就存在
   （前缀、分隔符、结束标志）。

**顺带被纠正的两条全局规则**：「地址必须以 `/v1` 结尾」与「必须有密钥」此前写在配置校验里，
其实只是 OpenAI 兼容端点的事实。现在由 `UpstreamShape` 回答（原生接口在 `/api` 上，且不要凭证）。

**没做的事与代价**：Canonical 的非流式响应没有创建时间这个字段，
而本票要求 `core` 零改动，因此 `created_at` 的换算结果走 passthrough 出境
（OpenAI 兼容形态的 `created` 本来就走这条路）。缺口记在 ADR-0009 的「被否决的方案」里，没有抹掉。

**验证**：`./mvnw clean verify` —— 86 个测试（本票新增 12）。反向验证四条，都红过并已还原：

1. `postLines` → `postStream`：流式契约用例红（解析次数 0，逐行 JSON 一条事件都读不出来）。
2. `toEvents` 认不出 `done:true`：`native-stream` 红（流没有终态，被判为 `upstream_closed`）。
3. 在 `OpenAiFace` 里加一行注释：差异断言红。
4. 改第一个 Provider 的任一 golden 基线一个字符：基线未改断言红。
