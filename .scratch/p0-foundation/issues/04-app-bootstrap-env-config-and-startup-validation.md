# 04: app 启动：环境变量配置、base-url 校验、离线档位

> 来源：[spec.md](../spec.md) — 用户故事 20、21、22

**What to build:** 应用真的起得来，且模型端点地址与 API 密钥只从环境里来；配置形态写错时在启动阶段就报错，而不是等到第一次调用才撞上 404。同时存在一个不依赖网络的本地档位。

base-url 这条要求是具体的：Spring AI 2.x 底层不再自动补 `/v1`，少写这个后缀不会报错，只会变成一次 404 —— 而且是在运行期很深处才暴露。启动校验把它提前到「起不来」而不是「跑不对」。

**Blocked by:** 02 四个模块骨架与依赖方向闸门

**Status:** ready-for-human

- [x] 应用在未配置任何凭证时会启动失败，失败信息指明缺的是哪个环境变量名
- [x] 端点地址与密钥只从环境变量解析；仓库受跟踪的任意文件中都不出现真实的端点凭证
- [x] 配置文件里出现的是指向环境变量的占位符，不含带凭证的字面地址
- [x] base-url 缺少 `/v1` 后缀时，应用启动失败并给出「应当在结尾补上 /v1」级别的可读提示
- [x] 存在无网络可用的本地档位：不访问任何外部服务也能启动并完成一次受支持的调用
- [x] 应用启动后其存活/健康入口可达，这是后续所有端到端行为测试能打进去的前提
- [x] 切换云端 / 本地两种档位不需要改动代码，只需要切换 profile 或环境变量

## 落地内容

### `airouter-gateway/config/EndpointProperties`

Endpoint（GLOSSARY 的术语）的绑定与校验，三条约束：

| 字段 | 约束 | 报错指向 |
|---|---|---|
| baseUrl | 非空、`^https?://.+/v1$` | `AIROUTER_ENDPOINT_BASE_URL`，缺 `/v1` 时连带给出补后缀的理由 |
| apiKey | 非空 | `AIROUTER_ENDPOINT_API_KEY` |
| model | 非空 | `AIROUTER_ENDPOINT_MODEL` |

两个刻意的选择：

1. **缺省绑到空串再交给校验判红**，而不是让占位符不可解析。占位符不可解析时 Spring 的报错虽然会提到变量名，但它同时给出一堆解析细节；三条由本项目自己写的消息则会并列出现，缺哪几个一眼看全。
2. **正则给空串留了一条通路**（`^$|^https?://.+/v1$`），让「没给」与「给错了」由各自规则各报一次，而不是同一处既说没给又说给了错的。

注册放在 gateway 自己的 `EndpointConfiguration`：`@ConfigurationProperties` 没有被 `@Component` 元标注，
组件扫描不会捡起它 —— 谁定义契约谁负责接进容器，否则每加一组属性都要回 app 改一次注解。

### `airouter-app/resources/application.yml`

两个档位：`local`（默认，指向本机 Ollama）与 `cloud`（真实厂商端点，零默认值）。三个名字同时
出现在 `.env.example` 与 `EndpointProperties` 的常量里 —— 改名时不只改一处的成本很高，这是刻意的。

记录一处被钉住的偏差：**默认档位写成 `spring.profiles.active: local` 而不是 `spring.profiles.default`**。
后者在这里取不到（那是 Boot 2.4 之前由 configfile listener 认领的键）。代价是它只能被更高优先级的来源覆盖，
环境变量 `SPRING_PROFILES_ACTIVE` 正好在其中。

### 执行环境与健康入口

app 侧引入 WebFlux 与 Actuator，并打出可执行 jar（`repackage`）。健康入口只开 `health,info`，
其余管理端点默认全关。

选择 WebFlux 而不是 Servlet 的理由写在 pom 注释里：网关两头的流量都是长连接与流式，
阻塞式 IO 模型会把一条流的成本压在一个线程上（AGENTS.md 纪律 4）。它同时是后续唯一的入站 seam。

## 真进程验证（不是靠测试推断）

直接跑 `java -jar`，而不是只在测试里断言：需要被证明的是整条链路都通 —— yml 占位符、relaxed binding、校验、失败冒泡。

| 场景 | 结果 |
|---|---|
| 本地档位、未设任何环境变量 | 起来，`local` profile，`/actuator/health` 返回 `{"status":"UP"}`，HTTP 200 |
| 云端档位、未设任何环境变量 | **起不来**，三条 Reason 各自指名 `AIROUTER_ENDPOINT_MODEL` / `_API_KEY` / `_BASE_URL` |
| 云端档位、base-url 缺 `/v1` | **起不来**，Reason 给出「必须以 /v1 结尾且不带尾斜杠」并说明会变成运行期 404 |
| 云端档位、给足三个环境变量 | 起来，云端点被绑定，健康入口 200 |

测试侧另有 11 个用例覆盖同一件事：`EndpointPropertiesValidationTest`（纯函数调用 validator，不起容器）、
`EndpointStartupValidationTest`（真的跑一遍 `SpringApplication`）、`LocalProfileBootsTest`（随机端口的真 Server + 真 socket）、
`EndpointSecretsAreNotCommittedTest`（断言 yml 里端点取值只能是占位符，且不出现 `sk-` / `Bearer`）。

## 已知边界

- **「完成一次受支持的调用」还没有被验证**，只验证了「起来」与「健康入口可达」。
  链路截至目前不含 Provider，调用行为由票 05 打通；届时需要在本机起一个 OpenAI 兼容端点
  （默认档位指向 `http://localhost:11434/v1`），当前这台机器上没有。
- **本地档位的 `api-key` 默认值是占位串 `ollama`**（Ollama 不校验密钥）。它对 localhost 之外的地址无效，
  但这一层没有被机器拦住：非本地地址必须用 `cloud` 档位，那里没有默认值。
- **`@ConfigurationProperties` 的报错由 Spring 组织**（`Property:` / `Reason:` / `Action:` 三段），
  本项目控制的是 `Reason` 那段，格式本身不改。
