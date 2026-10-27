# 02: 非流式打通

**What to build:** 一条非流式请求穿过网关拿到响应，且响应能被标准 OpenAI 客户端解析。

这是阶段 0–2 的**第一条纵向切口**：入站 Face → Canonical → 出网 Provider → 响应，
一层不落。它独立可验证，不需要等流式那边。

同时它是领域模型与契约测试的落脚点：`core` 的类型在本票里第一次被真实请求使用，
契约测试的发现机制也在本票立起。

**Blocked by:** 01

**Status:** done（2026-10-27）

## Acceptance criteria

- [x] `core` 有 `CanonicalRequest` / `CanonicalResponse` / `GatewayError` /
      `UpstreamFailure` / `CompletionPort`（返回 `CompletionStage`）/ `Outcome` / `OutcomeKind`
      （外加支撑它们的 `CanonicalMessage` / `CanonicalChoice` / `CanonicalUsage` /
      `CanonicalFinishReason` / `CanonicalRole` / `CanonicalTool` / `GenerationParams`）
- [x] `core` 的依赖树里出现 Spring / Jackson / Reactor 即构建失败（R1 由 enforcer 判红，票 01 已实测）。
      连带后果：`core` 的测试依赖是 JUnit + AssertJ，**不是** spring-boot-starter-test
- [x] OpenAI 兼容 Face 与 Provider 就位，且**互不感知**（R3）。
      实测：让 `faces.openai` 调 `providers.openai` 的一个方法 → `ModuleStructureTest.facesDoNotDependOnProviders` 红
- [x] 标准 OpenAI 客户端发一次非流式请求穿过网关，拿到响应
- [x] 响应能被标准客户端解析 —— 翻译层的输出是**协议正确**的，不只是「看起来对」
      （`com.openai:openai-java` 4.91.0，测试作用域；断言落在 SDK 自己的模型类上）
- [x] 上游返回 4xx/5xx → 收到对应的错误，并收敛成 `GatewayError`
      （4xx 状态码透传 + 类型 `upstream_4xx`；5xx 收敛到 502 + 类型 `upstream_5xx`）
- [x] 上游地址不可达 → `UPSTREAM_FAILURE`
      （`OutboundWireTest`：连内核刚释放的端口，断言 kind / type `upstream_unreachable` / 502）
- [x] 契约测试发现机制就位：一个用例一个目录，放入样本文件即被发现，
      **不需要改测试代码**；`contract/` 目录与其 README 就位
- [x] 历史 golden 样本从 tag `baseline-pre-11` 取回落位（`git checkout baseline-pre-11 -- ...`）
- [x] `contract-ollama/` 的样本文件一并取回落位 ——
      但它的 runner **本阶段不建**（不为尚不存在的形态建 runner，README 顶部已写明）
- [x] `transport` 不知道业务包（R5）：`OutboundWire` 的构造参数是普通值

## 交付时实测过的「故意违反」

| 动手 | 红的那一处 |
|---|---|
| 让 `gateway.faces.openai` 调用 `providers.openai` 的一个方法 | `ModuleStructureTest.facesDoNotDependOnProviders`（R3a） |
| 给 `gateway.faces.openai` 加一个真实类却不登记 | `RulesCoverNonEmptyPackagesTest.everyPackageIsAccountedFor` |

一处**值得记下来的失败**：第一次探针写的是 `return OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH;`，
规则**没红** —— 那是一个 `static final String`，编译期就被内联进调用方，字节码里
根本没有对 `OpenAiUpstreamCodec` 的引用。改成调用它的一个方法之后才红。
「用常量做探针」会得出「规则没生效」的错误结论。

## 可执行 jar 的冒烟（测试覆盖不到的那一段）

`java -jar airouter-app/target/airouter-app-*.jar` + 指向本机 Ollama 的三个环境变量，
发一次真实请求，拿到 200 与 OpenAI 形态的响应体（含 usage 明细、created、system_fingerprint 原样带回）。

第一次冒烟**起不来**：`NoClassDefFoundError: org/springframework/http/client/reactive/ClientHttpConnector`。
原因是 `app/pom.xml` 里为 R4 的违规样例声明了 **test 作用域的 `spring-web`** ——
它已经由 webflux starter 以 compile 作用域传递进来，再声明一次并标成 test，
按「最近声明优先」把整个 spring-web 降到了 test，可执行 jar 里因此没有它。
`WebClient.builder()` 恰好是启动期就要走到的那一行，于是失败发生在装配期而不是第一次调用。

**这类缺陷没有任何测试会红**：`@SpringBootTest` 跑在测试 classpath 上，spring-web 在那里是有的。
修法是删掉那条声明（compile 作用域的那一份同样在测试 classpath 上），
并在 pom 里写下为什么不能再加回来。可执行 jar 的启动冒烟因此是每票结束时值得做一次的动作。

## Notes

- 本票只做**非流式**那一条路。流式在 03
- 本票不涉及终态与结算（`Outcome` 的类型就位，但结算纪律在 04）
- 接缝取可用的最高层：HTTP 入口。上游用录制器，不是真 Ollama
  （真 Ollama 只用于抓样本，不进常规测试）
- 录制器本票只实现了 `replay(status, body)` 这一次写完的形态。
  按块写出、`holdOpen`、块计数随 03 / 05 加进来 —— 它们各自是被某一票需要的
- `EndpointProperties` 的校验写在**构造器**里而不是 Bean Validation 注解上，
  且报错指名环境变量名；换成校验器的时机是形态进来（票 06）需要「一次报全」时
- 测试文件预算（`docs/agents/testing.md`）：`app` 8 个（5 守门 + 3 契约）、`gateway` 1 个、`core` 1 个
