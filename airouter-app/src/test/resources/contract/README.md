# 契约用例的存放约定

> 本目录与 `contract-stream/` 是**第一种形态**（OpenAI 兼容端点）的基线。
> 第二种形态（Ollama 原生，逐行 JSON）的基线在 [`../contract-ollama/`](../contract-ollama/README.md)，
> 那边由 `SecondProviderContractTest` 发现 —— 两者比的东西相同，输入那一侧的形态不同。
> 改这里的任何文件都会让 `SecondProviderTouchesNoFaceTest` 变红，那是刻意的：加形态应当加新基线，不是改旧的。

新增一个用例**不需要改任何代码**：在这个目录下建一个子目录，放入两个文件即可，
`GoldenWireContractTest` 会自动发现它并参与构建。

```
contract/            # 非流式：一问一答
├── <用例名>/
│   ├── upstream.json   # 录制的上游响应
│   └── expected.json   # 期望网关返回给客户端的东西

contract-stream/      # 流式：一段 SSE 字节 → 一串帧
├── <用例名>/
│   ├── upstream.json   # {"status": 200, "sse": "data: {...}\n\ndata: [DONE]\n\n"}
│   └── expected.json   # {"frames": [ {...}, "[DONE]" ]}
```

两个文件的形状相同：

```json
{
  "status": 200,
  "body": { ... }
}
```

- **`status` 参与比对**。上游的 4xx 与 5xx 对应什么样的客户端状态码，本身就是契约的一部分，
  只比对响应体会让这一层失去保护。
- **`body` 是语义级比对**（解析后比树），不断言键顺序。键顺序变化不影响任何客户端，
  把它当契约只会带来无意义的基线改动。
- 比对失败时构建变红，并打印期望与实际的整齐缩进，逐字段对。

## 流式那一份（`contract-stream/`）

形状与上面不同，因为要比的东西不同：流式比的是**帧序列**。

- `upstream.json` 的 `sse` 是一整段 SSE 文本，事件之间用空行分隔；回放时按事件分块写出并 flush，
  因此「逐帧到达」这件事本身是被测到的，不是假设的。
- `expected.json` 的 `frames` 是客户端会看到的每一帧的 `data` 载荷，**包含 `[DONE]` 哨兵本身**。
  哨兵的出现时机与次数是流式契约里最容易在重构中丢掉的东西：丢了它客户端就不知道该停止读取，
  而服务端这一侧一切正常。
- 元素是对象时按树比对，是字符串时按串比对 —— 后者只用于 `[DONE]`。
- 需要「一个字节块把某个字符切成两半」这类样本时不要写进这里：那是端到端测试
  （`StreamingEndToEndTest`）的事，它能控制到块一级。

## 录制上游字节的两种方式

1. **从真实端点录**：直接问本机或厂商端点一次，把响应体原样存进 `upstream.json`。
   这是首选 —— 契约测试保护的是与真实世界的兼容性，编造的样本保护不了任何东西。
2. **手写**：只在上游产生不了这种响应时才用（例如本机端点不会返回 5xx），
   并在用例名里体现它的性质（本目录的 `upstream-5xx` 就是这种）。

## 现有用例

| 用例 | 覆盖什么 | 样本来源 |
|---|---|---|
| `normal-non-streaming` | 正常非流式响应：usage 如实返回、上游特有字段原样带回 | 本机 Ollama 真实响应 |
| `upstream-4xx-unknown-model` | 上游 4xx：状态码原样透传，错误体是 OpenAI 信封 | 本机 Ollama 真实响应 |
| `upstream-5xx` | 上游 5xx：收敛到 502，不把上游的失败细节往外抛 | 手写（端点产生不了） |

流式（`contract-stream/`）：

| 用例 | 覆盖什么 | 样本来源 |
|---|---|---|
| `stream-normal` | 每一帧都带 id / created / model；增量帧与终态帧的 `finish_reason`；`[DONE]` 收尾 | 手写（按真实端点的帧形态） |
| `stream-usage-frame` | `choices` 为空的纯用量帧，与「带内容的帧」形态不同 | 手写（对应上游的独立用量帧） |
| `stream-without-done` | 上游讲完但没发哨兵就关闭：补发 `upstream_closed`，且 `[DONE]` 仍然出现 | 手写（真实端点不会这么做） |
