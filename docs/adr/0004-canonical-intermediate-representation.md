# ADR-0004：领域模型与中间表示（Canonical）

- 状态：已接受
- 日期：2026-10-26
- 相关：[0005](./0005-streaming-terminal-outcome-contract.md)、[0006](./0006-wire-shape-and-shape-self-description.md)

## 1. 为什么要有中间表示

N 个入站协议 × M 个上游协议，直接互转是 N×M 份翻译代码。
加一层 Canonical 之后是 N+M：**每个 Face 只译到 Canonical，每个 Provider 只从 Canonical 译出。**
新增一端的代价是 1 而不是 M 或 N。

## 2. 类型清单（`airouter-core`）

```
CanonicalRequest            请求中间表示
CanonicalResponse           非流式响应中间表示
CanonicalStreamEvent        流式事件，sealed interface
  ├ Opening                 首事件：id / model / created
  ├ TextDelta               文本增量
  ├ Usage                   纯用量帧
  ├ Ping                    上游保活注释
  ├ Terminal                正常终态：FinishReason + Usage
  └ Failed                  异常终态：GatewayError
GatewayError                type / message / httpStatus
UpstreamFailure             携带 GatewayError 的 unchecked 失败
CompletionPort              非流式端口：CompletionStage<CanonicalResponse>
StreamingCompletionPort     流式端口：Flow.Publisher<CanonicalStreamEvent>
Outcome                     一次调用的结算结果（见 0005）
```

## 3. 三条形状上的决定

**(a) 端口返回 JDK 类型，不返回 Reactor 类型。**
`CompletionStage` 与 `java.util.concurrent.Flow.Publisher`，不是 `Mono` / `Flux`。
理由：`core` 的依赖树里不许出现 Reactor（enforcer 判红）。两侧各用一次 `JdkFlowAdapter` 转换即可接上。

**(b) `CanonicalStreamEvent` 是 sealed，概念层四态、实现层六型。**
概念层讨论协议语义时用 Opening / Delta / Terminal / Failed；写代码时用上表的实现层子类型。

`ToolCallDelta` **尚未引入**：工具调用的增量形态取决于上游真实怎么发，而现阶段没有能稳定复现它的端点。
与其先画一个未经验证的类型，不如等有了可回放的上游再补 —— 那时它是被样本钉住的。
`Ping` 保留为显式类型，是为了让「保活帧不是内容、也不是终态」这件事在类型上可见。

**(c) Passthrough 存 JSON 原文，不存解析后的对象。**
`Map<String, String>`，值是文本。它挂在响应顶层、每个候选、每条消息、以及 usage 内部 ——
厂商在这些位置挂私有字段都很常见。`core` 不许依赖 Jackson，所以只能是原文；解码时再解析一次。

## 4. 端口的契约

`StreamingCompletionPort` 的契约由**实现方**保证：

- 一条流**有且只有一个**终态，且它是最后一个事件
- 流不以 error 信号结束 —— 失败先在事件层变成 `Failed`，再正常结束
- 终态之后的帧不出现在客户端

> 这三条是「流式绕过熔断器」的反面：终态是唯一可结算的点，[0005](./0005-streaming-terminal-outcome-contract.md) 建立在它之上。

## 5. `core` 的边界

- 零框架依赖：`org.springframework*` / Jackson / Reactor 出现在依赖树即构建失败
- 领域模型**不含实例概念之外的运行时状态**：熔断计数与健康登记挂在 `registry` 的运行时对象上，
  延迟样本与在途计数是**观测到的**状态、各住自己的包（`observe` / `transport`，ADR-0010 §2 票 13 修订），不在 `core` 里
- 需要的词不在 `GLOSSARY.md` 里时，先改术语表再改代码

## 6. 后果

- 新增一个 Face 或 Provider 不需要动 `core`
- 任何「让 core 直接读 HTTP 状态码」的提议都会撞 enforcer，设计上也不该通过
