# 本机 OpenAI 兼容端点（Ollama）

**这份文档管什么**：本机那个供 `local` 档位使用的 OpenAI 兼容上游——怎么装的、怎么起停、实际能力到哪一步为止。
**不管什么**：云厂商端点的接线（见 `.env.example` 与 `docs/adr/`），以及 Provider 自身的业务逻辑（后续票）。

---

## 1. 为什么要有它

`spec.md` 故事 15 要求 `local` 档位「不访问外部服务也能启动并完成一次受支持的调用」。这条要求不是为了方便，而是为了把一批验证从「凭印象」挪到「对着真实字节」：

- **ADR-0006 的终态契约**要求 `Terminal` / `Failed` 必须显式发出。终态的形状（最后一个 chunk 携带 `finish_reason`、其后紧接 `data: [DONE]`）只有在真实端点面前才有意义——桩件是我们照着文档写的，写错的地方会跟着我们一路错下去。
- **错误语义**（未知模型返回 404 还是 400、错误体长什么样）决定了配置错误会以什么代价暴露。
- 手写桩件的最大问题不是写起来费事，而是**它的偏差不可见**：桩件是照着我们所理解的协议写的，理解错的地方会被原样固化，并且一直绿下去。

本机端点同时满足「不需要网络」和「是真实实现」这两件事。

## 2. 选型

**为什么是 Homebrew formula `ollama`，不是 cask `ollama-app`**

| | `brew install ollama`（formula） | `brew install --cask ollama-app` |
|---|---|---|
| 形态 | 无 GUI 的服务进程 + `ollama` CLI | 带菜单栏图标的桌面应用 |
| 适用 | 开发机与 CI 上的无头依赖 | 需要图形界面手动试模型 |
| 起停 | `ollama serve` / 进程 signal | 靠应用退出 |

网关要的是一个能被写进脚本、能在 `verify` 之外被拉起来的进程，不是托盘里的图标。

**为什么是 `qwen2.5:0.5b`**

| 候选 | 体积 | 取舍 |
|---|---|---|
| `qwen2.5:0.5b` | 397 MB | **选它**。一次拉取最省（本次实测约 675 KB/s），中文流利；而本仓库的测试语料多数是中文 |
| `qwen2.5:1.5b` | ~1 GB | 输出更像样，工具调用略稳，但拉一次要久得多；当 0.5b 不够用时再加 |
| `llama3.2:1b` | ~1.3 GB | 英文更强、中文偏弱；本仓库的测试文本多数是中文 |

选模型的第一约束是**小**：本机端点的任务是给出正确的协议形状，不是给出聪明的回答。用小模型把协议形状验掉，语义质量交给后续的契约测试与真实端点。

## 3. 安装步骤

环境：macOS arm64，内存 32 GB，可用磁盘 366 GB，Homebrew 6.0.22。

```bash
brew install ollama
```

**踩到的一个坑**：这台机器上有 `coursier/formulas` 与 `mongodb/brew` 两个未授信 tap，Homebrew 拒绝从它们加载 formula，直接让安装失败：

```
Error: Refusing to load formula mongodb/brew/mongodb-database-tools from untrusted tap mongodb/brew.
```

两条路可选：`brew trust`（改这台机器上的长期授信状态），或一次性环境变量。选后者——不想因为装一个模型服务，就去动与你其他项目相关的 tap 授信状态：

```bash
HOMEBREW_NO_REQUIRE_TAP_TRUST=1 brew install ollama
```

装完核对版本：

```bash
ollama --version   # 本文记录：0.40.10
```

## 4. 启动、停止与持久化

**按需拉起（推荐，也是本文的做法）**

```bash
ollama serve                     # 前台
ollama serve > serve.log 2>&1 &  # 后台
```

停止：`pkill -f "ollama serve"`。这样不会注册任何开机启动项——模型进程常驻着占一份内存，而它只在本地验证时才有意义。

**注册为常驻服务（备选）**

```bash
brew services start ollama
brew services stop ollama
```

代价是开机自启 + 登录即占用资源，而且一旦注册，再跑上面的 `ollama serve` 会撞端口。二选一，不要两个都留着。

**值得知道的环境变量**（`ollama serve --help` 列全）

| 变量 | 用途 | 本文的建议值 |
|---|---|---|
| `OLLAMA_HOST` | 监听地址 | 默认 `127.0.0.1:11434`，不改 |
| `OLLAMA_KEEP_ALIVE` | 模型在内存里的驻留时长，默认 5m | 跑测试时用 `-1`（不卸载），避免冷加载混进耗时判断 |
| `OLLAMA_CONTEXT_LENGTH` | 上下文长度 | 默认即可；0.5b 模型用不满 |
| `OLLAMA_MODELS` | 模型存放目录 | 默认 `~/.ollama/models`，本文实测 379 MB |
| `OLLAMA_ORIGINS` | 允许的跨域来源 | 仅当浏览器直连本端点时才需要开 |

## 5. 模型管理

```bash
ollama pull qwen2.5:0.5b     # 拉取，本次实测 675 KB/s，397 MB
ollama list                  # 已装模型
ollama rm qwen2.5:0.5b       # 删除，占 ~397 MB
```

模型落在 `~/.ollama/models/manifests/registry.ollama.ai/library/`。它的内容不是仓库的一部分，**不要把它加进 Git**。

## 6. 实测台账

以下每条都是本机实际打出来的响应，不是从文档抄的。

| 探测 | 结果 | 证据要点 |
|---|---|---|
| `GET /` | 200 `Ollama is running` | 它只看进程在不在，不看模型有没有装好——更适合当存活探针，也更适合区分「服务没起」和「模型没拉」 |
| `GET /v1/models` | 200，`data` 数组 | 未拉模型时返回 `"data":null`，不是空数组——解析要容 `null` |
| 非流式 `chat/completions` | 200 | `choices[0].finish_reason = "stop"`，`usage` 齐全 |
| **流式 SSE** | 200 | 每个 chunk 一帧 `data: {...}`，空行分隔；倒数第二帧携带 `finish_reason: "stop"`，最后一帧固定 `data: [DONE]` |
| 长度截断（`max_tokens: 3`） | 200 | `finish_reason = "length"`，`completion_tokens = 3`——**这是除了 `stop` 之外的第二个终态理由** |
| 非流式工具调用 | 200 | `choices[0].message.tool_calls` 有值，`finish_reason = "tool_calls"`；500M 参数也能给出合规的参数 JSON |
| **流式工具调用** | **不稳定** | 同参数连打三次：一次 135 帧且全程是正文、一次 92 帧无工具调用、一次 2 帧带 `tool_calls`。**不能拿它做 golden-file 断言** |
| 未知模型 | **404** | `{"error":{"message":"model 'no-such-model' not found","type":"not_found_error","param":null,"code":null}}` |
| 缺 `messages` | **400** | `{"error":{"message":"[] is too short - 'messages'","type":"invalid_request_error",...}}` |
| `/v1/embeddings` 与 `/api/embed` | **501** | 错误串建议「用 `--embeddings` 启动」，但 0.40.10 的 `ollama serve --help` 只有 `-h` 一个 flag——**提示指向的标志在这个版本里不存在** |
| 不带 `Authorization` 头 | 200 | 本机端点不校验凭证 |
| `stream_options.include_usage` | 200 | 见下方「最后一个陷阱」 |

**最后一个陷阱**（最值得记的一条）：开启 `include_usage` 后，`[DONE]` 之前会多出这样一帧

```
data: {"id":"chatcmpl-30","object":"chat.completion.chunk",...,"choices":[],"usage":{...}}
```

它的 **`choices` 是空数组**。任何「取 `choices[0]` 拿 `delta` 或 `finish_reason`」的写法到这里都会出问题：轻则取 null 让终态被静默吞掉，重则越界。网关的流解析器必须显式处理「无 `choices` 的纯用量帧」，而这条细节只有在真实端点上才会遇到。

**延迟基线**（同一台机器、同一模型）：冷加载首次调用 0.60 s，热调用 0.07 s。写超时相关的测试时，用的是后者的量级，但它们之间差了近十倍——所以 `OLLAMA_KEEP_ALIVE=-1` 值得开。

**经网关打一次流式**（`LocalModelStreamingEndToEndTest` 实跑）：`POST /v1/chat/completions` 带 `"stream":true`，
客户端收到多条 `data: {...}` + 末尾一条 `data: [DONE]`，`X-Airouter-Request-Id` 在场，
结算点上报的 `latency` 是**百毫秒量级而不是 0**。最后这一条是 ADR-0006 那个缺陷的反面证据：
来源项目在响应头写出时就结算，流式样本因此恒为零。

## 7. 接进本项目的 local 档位

`airouter-app/src/main/resources/application.yml` 的 `local` 段已经指向这个端点：

| 变量 | local 档位默认值 |
|---|---|
| `AIROUTER_ENDPOINT_BASE_URL` | `http://127.0.0.1:11434/v1` |
| `AIROUTER_ENDPOINT_API_KEY` | `ollama`（占位串，不是凭证） |
| `AIROUTER_ENDPOINT_MODEL` | `qwen2.5:0.5b` |

三个写法各自的理由：

- **base-url 写 `127.0.0.1` 而不是 `localhost`**：Ollama 只监听 IPv4，而 `localhost` 在本机同时解析出 `127.0.0.1` 与 `::1`；`curl` 会自动退回到能通的那一侧，Java 客户端不一定。少一步解析就少一处抖动。这条是装完才发现的，配置里的默认值是跟着实测结果改的。
- **base-url 带 `/v1`**：Spring AI 2.x 不补这个后缀，缺了不报错、只会变成一次运行期 404。`EndpointProperties` 在启动期就校验它。
- **密钥给占位串而不是空**：`EndpointProperties` 的 `@NotBlank` 对两个档位一视同仁，本机端点虽不校验凭证，也不该在本档位里开一个「可以不给」的口子——那样 `local` 与 `cloud` 就要走不同的校验分支。

**从 JVM 侧也验证过一次**（不能只用 curl 下结论）：用 `java --source 21` 跑一段单文件程序，走 `java.net.http.HttpClient` 打 `/v1/models`，返回 200。

这一步之所以要单独记一笔：这台机器的环境里设置了 `HTTP_PROXY` / `HTTPS_PROXY`（指向本机 `127.0.0.1:57793`）。
实测 curl 带与不带 `--noproxy` 都是 200，JVM 侧也是 200 —— 说明当前这条环回链路经不经过那个代理都能通，**不需要额外加参数**。
但它是一个会变的前提：一旦将来某个客户端开始读系统代理配置（`-Djava.net.useSystemProxies=true`、Reactor Netty 显式配了代理等），环回请求可能被送到代理上去。
真到本机端点连不通的时候，这一层要第一个排除。

**一处待办**：网关发出的请求会带上 `Authorization` 头，本机端点忽略它这件事尚未做镜像验证（本次验证时的请求被凭据内容识别拦下，没有重试）。票 05 打通上游时，这条要在那里补验证。

## 8. 已知限制，以及它们约束了什么

1. **输出质量**：0.5B 模型会答非所问、会中途复读。**不要对它做语义断言**——断言协议形状不断言内容对错。要验证「翻得对不对」，用 golden-file 契约测试。
2. **流式工具调用不确定**：同上，工具调用语义只在非流式下验证。
3. **没有 embeddings**：如果后续票需要向量化能力，本机端点给不了，得另找（换支持 embed 的模型，或改用只校验形状的方式）。
4. **单模型**：目前只装了一个。加第二个模型时记住磁盘账：每个都是几百 MB。

## 9. 排错清单

| 现象 | 先看什么 |
|---|---|
| `curl: Failed to connect` | 服务起了没有：`curl http://127.0.0.1:11434/` |
| `localhost` 超时但 `127.0.0.1` 通 | ollama 只监听 IPv4。`localhost` 在本机解析出 `127.0.0.1` 与 `::1` 两个地址，`::1` 那条路走不通。**配置与测试里写 `127.0.0.1`，不要写 `localhost`**——少一步解析就少一处抖动 |
| 响应慢到十几秒 | 模型被 `OLLAMA_KEEP_ALIVE` 卸载后冷加载；或在换/拉模型的过程中 |
| `model 'x' not found` | `ollama list` 看实际名字——tag 必须完整，比如 `qwen2.5:0.5b` 不能省成 `qwen2.5` |
| `brew install` 报 out of tap trust | 见第 3 节 |
| 网关起来了，但健康入口不在 8080 | 见下方「一个与本机端点无关、但每次都会遇上的坑」 |
| 形态配成 `ollama` 却起不来，报地址形态不符 | 原生接口的地址根是 `/api` 不是 `/v1`：`AIROUTER_ENDPOINT_BASE_URL=http://127.0.0.1:11434/api`。同一台机器两个都在，靠形态区分，不靠地址猜 |

**一个与本机端点无关、但每次都会遇上的坑**：这台机器的会话环境里带着 `SERVER__PORT=57456` 与 `SERVER__HOST=127.0.0.1` 两个变量。
Spring 的宽松绑定会把它们认成 `server.port` / `server.host`，于是 `application.yml` 里写的 `server.port: 8080` 被静默覆盖：

```
# 带上环境变量：Netty started on port 57456 (http)
# env -u SERVER__PORT -u SERVER__HOST 之后：Netty started on port 8080 (http)
```

两个来回都已实测，不是推断。这条不涉及任何本项目的缺陷，但它决定了「健康入口到底在哪个端口」这件事不能靠默认值猜：跑进程时显式给 `--server.port=`，或先把变量去掉。

---

## 10. 同一台机器上还有另一种形态：原生接口（`/api/chat`）

Ollama 同时提供两种接口，这是本项目第二种 wire 形态的来源（见 `docs/adr/0009-...`）：

| | 兼容接口 | 原生接口 |
|---|---|---|
| 地址 | `http://127.0.0.1:11434/v1` | `http://127.0.0.1:11434/api` |
| 路径 | `/chat/completions` | `/chat` |
| 凭证 | 随便填（它不看） | **没有这一项** |
| 流式 | SSE：`data: {...}`，以 `data: [DONE]` 收尾 | **逐行 JSON**：每行一个 JSON，没有 `data:` 前缀、没有 `[DONE]`，最后一帧的 `"done":true` 就是说完了 |
| 用量 | `usage{prompt_tokens, completion_tokens, total_tokens}` | 顶层 `prompt_eval_count` / `eval_count`，**没有合计** |

两条实测（本机 `qwen2.5:0.5b`）：

```bash
# 非流式
curl -s http://127.0.0.1:11434/api/chat -d '{"model":"qwen2.5:0.5b",
  "messages":[{"role":"user","content":"只回复两个字：你好"}],"stream":false}'
# → {"model":"...","created_at":"2026-10-24T11:40:36.222856Z",
#    "message":{"role":"assistant","content":"你好"},"done":true,"done_reason":"stop",
#    "prompt_eval_count":35,"eval_count":2,...}

# 生成参数写在 options 里，写在顶层会被静默忽略
curl -s http://127.0.0.1:11434/api/chat -d '{"model":"qwen2.5:0.5b","messages":[…],
  "stream":false,"options":{"temperature":0,"num_predict":8,"stop":["。"]}}'

# 模型不存在：错误体是字符串，不是对象
curl -s http://127.0.0.1:11434/api/chat -d '{"model":"nope","messages":[…],"stream":false}'
# → {"error":"model 'nope' not found"}   HTTP 404
```

**切到这一形态只改一个变量**（`local` 档位下密钥可以留着不用，它不会被发出去）：

```bash
AIROUTER_ENDPOINT_PROVIDER=ollama
AIROUTER_ENDPOINT_BASE_URL=http://127.0.0.1:11434/api
AIROUTER_ENDPOINT_MODEL=qwen2.5:0.5b
```

客户端那一侧看到的字节与兼容形态下**完全一致**（`chat.completion` / `chat.completion.chunk` / `[DONE]`），
差别全在上游那一侧 —— 这正是 `contract-ollama/` 那四组基线要钉住的东西。
