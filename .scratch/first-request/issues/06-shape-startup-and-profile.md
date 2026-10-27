# 06: 形态自述、启动期校验与档位

**What to build:** 形态自己回答三个问题；地址配错则应用**起不来**并指名具体值；
`local` 档位不配任何端点变量也能起来并完成一次调用。

「配错了起不来」是它独立可验证的行为 —— 不是「配置文档里写着必须以 `/v1` 结尾」。

**Blocked by:** 02

**Status:** done（`baseline-5`）

## Acceptance criteria

- [x] `UpstreamShape` 自述三件事：地址该长什么样、这个协议要不要凭证、
      流怎么结束（经由各自的分帧器）
- [x] 地址形态是**启动期校验**而不是约定：`openai` 形态的端点必须以 `/v1` 结尾
      且不带尾斜杠；违反则启动失败并**指名具体值**
- [x] 形态由配置**显式**给出，不允许按地址猜 ——
      同一台本机 Ollama 上 `/v1` 与 `/api` 两种地址都在，猜必然猜错
- [x] 凭证只在形态需要它时才必填（`openai` 形态需要）
- [x] `local` 档位在**无网络**环境下能起来并完成一次受支持的调用
- [x] 档位切换只改 profile 或环境变量，**不改代码**
- [x] baseUrl 与凭证只从环境变量读取，配置文件里只能是占位符
- [x] 有一条测试断言受跟踪文件里不含真实凭证
- [x] 措辞（错误文案与「什么算异常结束」的解释）由形态自己提供，
      **不写在共享的错误映射里**
- [x] 本机地址一律写 `127.0.0.1` 不写 `localhost`

## 落点

- `transport`：`UpstreamFramer` 接口 + `SseFrame` 改名 `UpstreamFrame`；
  `OutboundWire.postStream` 多一个「怎么造分帧器」的参数
- `providers`：`UpstreamShape`（本阶段只有一个取值 `OPENAI`）
- `config`：`EndpointProperties` 多一位 `shape`；`UpstreamConfiguration` 把形态的分帧器交出去
- `app`：`application-local.yml`、`.env.example`（从 `baseline-pre-11` 取回并改写）

## 三条值得记住的判断

### 1. 方向只能是「形态把分帧器交出去」

R5 规定 `transport` 不认识 `providers`。所以「形态自述流怎么结束」不能实现成
「管道问形态是哪一个」，只能实现成**形态给一个 `IntFunction<UpstreamFramer>`**。
顺带把 `SseFrame` 改名 `UpstreamFrame`：让一个 shape-agnostic 的包往外发一个
名字里写着 SSE 的类型，等于替所有未来的形态做了一次决定。

### 2. `local` 档位的测试不能挂在 `@SpringBootTest` 上

档位本身就是被断言的对象，而 `@DynamicPropertySource` 会抢在档位之前生效，
于是「默认值是什么」反而看不出来。这里直接用 `SpringApplicationBuilder` 起两个上下文：
一个原样（证明不设任何端点变量也能起来）、一个只换地址（证明能完成一次调用）。
换地址是必要的妥协 —— 真 Ollama 不进常规测试；其余四项全部来自档位默认值。

### 3. 凭证那条断言不写「密钥长什么样」的正则

扫描 `sk-` 形状的串既会误伤，也会让测试自己变成一份「怎么写假密钥」的说明书。
改成断言**形状**：配置里出现 `api-key` / `base-url` 的行，值里必须有 `${`。
它守的是同一件事（真实值不入库），而且不会因为某个不是密钥的串而红。

顺带一条：`surefire` 的工作目录是**模块**目录，`Path.of(".")` 扫不到仓库根 ——
「仓库里有没有 .env」会变成「airouter-app 里有没有 .env」，一个永远为真的问题。

## 故意破坏三处，都确认会红

| 破坏 | 红的断言 | 现象 |
|---|---|---|
| 去掉 `shape.requireValidBaseUrl` | 地址不符合形态 | 启动期不再拦 |
| 形态缺失时按地址兜底成 openai | 形态必须显式给出 | 「不能猜」这条失效 |
| `application-local.yml` 去掉形态默认值 | local 档位能起来 | 起不来，指名 `AIROUTER_ENDPOINT_SHAPE` |

## 验证

`./mvnw clean verify` 全绿：core 12 / gateway 25 / app 66 + 门禁 2；
checkstyle 0 / spotbugs 0；行覆盖率 85.4%（615/720）。

## 测试文件预算

app 仍是 **10 个**：`StandardClientParsesResponseTest`（1 个方法：官方 OpenAI SDK 能解析响应）
并入 `NonStreamingEndToEndTest`（同为 wire 契约、同一个上下文），腾出的名额给 `LocalProfileTest`。

## Notes

- 本阶段只落地 **`openai` 一种形态**、SSE 一种分帧。
  `ollama` 形态与其逐行 JSON（NDJSON）分帧不在本阶段（见 ADR-0020 阶段 12）
- 测试文件并入模块预算，需合并同类项（已做到：净增 0）
