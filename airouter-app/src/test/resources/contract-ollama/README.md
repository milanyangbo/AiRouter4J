# 第二种形态的契约用例（Ollama 原生）

> **这一目录目前只有样本，没有 runner。** 样本是从本机端点抓下来的真实字节，
> 属于数据资产，因此随阶段 1 一并取回落位；发现它的 runner 要等 `ollama` 形态落地时才建 ——
> 不为尚未存在的形态建 runner（阶段 12）。

这个目录与 `contract/`（非流式）、`contract-stream/`（流式）平行，
比的东西是同一件 —— **客户端会看到的 wire format**，差别只在输入那一侧：
这里的样本是**逐行 JSON**（`ndjson`），那边是 SSE（`sse`）。

不把它们放进同一份用例表，是因为「同一份输入喂给两种形态」没有意义：
一段 SSE 字节不是原生端点会说的话，喂给它的翻译器只能得到一个人造的结论。

```
contract-ollama/
├── <用例名>/
│   ├── upstream.json   # 二选一：{"status": 200, "body": {...}} 或 {"status": 200, "ndjson": "…"}
│   └── expected.json   # {"status": ..., "body": {...}} 或 {"frames": [...]}
```

- `body` 表示非流式：一问一答，比对响应体。
- `ndjson` 表示流式：一行一个事件，**没有 `data:` 前缀、没有空行、没有 `[DONE]`**，
  终结由最后一帧的 `done:true` 宣告。回放时按行分块写出并 flush。
- `expected.json` 里出现 `[DONE]` 是因为**客户端**那一个是 Face 写的：
  上游没有哨兵，客户端仍然需要知道该停止读取。这一条正是本目录要钉住的东西之一。

## 样本来源

四组用例全部来自本机 Ollama `/api/chat` 的真实响应
（安装与起停见 `docs/runbook/local-openai-endpoint.md`），
只有 `native-upstream-4xx` 一组是手写的 —— 真实端点在模型不存在时确实回
`{"error":"..."}`（**字符串**，不是对象），这里只是把模型名换成好认的。

| 用例 | 覆盖什么 |
|---|---|
| `native-non-streaming` | 原生响应译成 OpenAI 形态：内容在 `message.content`、用量两个计数 + 明细、`total_tokens` 为 0（不编造合计）、`created` 由 `created_at` 换算 |
| `native-stream` | 逐行 JSON → 帧序列：增量帧、纯用量帧、终态帧带 `usage`、`[DONE]` 收尾 |
| `native-stream-without-terminal` | 上游没发 `done:true` 就收了连接：补发 `upstream_closed`，`[DONE]` 仍然出现 |
| `native-upstream-4xx` | 错误体是字符串而不是对象：类别 `upstream_4xx`，状态码原样透传 |

新增用例同样不需要改代码：建一个目录，放入两个文件即可被 `SecondProviderContractTest` 发现。
