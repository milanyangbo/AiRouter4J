# AGENTS.md

本文件给 coding agent 看。请把每条约束当成硬约束，不是建议。

## 项目是什么

**AiRouter** —— 大模型 API 网关 + AI Agent 编排平台的**全量重写版**。

- 坐标：`io.github.airouter:airouter`，根 POM 为 `<packaging>pom</packaging>`，Maven 多模块
- 旧版（上行 JAiRouter 的一份检出）已整体迁到 **`legacy/`**，作为评审与对照基准。**它不参与构建、不在 `<modules>` 里、CI 不扫它**
- 重写目标：Spring Boot 4.1 + Spring AI 2.0.1；网关侧（协议翻译 / 路由 / 熔断限流）与 Agent 能力层都要做成**能真实跑、能演示**的系统，而不是 demo 壳

**对 `legacy/` 的边界**：可以读它作为参考，可以批判它，**但不要复制它的代码到新坐标系**。它的价值是告诉你哪些坑必须绕开（见 `docs/planning/legacy-review.md`）。

## 技术决策的权威来源

**`docs/adr/` 是唯一权威。** 动手写一个模块之前，先读与之相关的 ADR。

- 如果产出与某条 ADR 冲突：**显式指出**（写明 ADR 编号 + 为什么值得重开），**不要静默覆盖**
- 术语以根 **`GLOSSARY.md`** 为准。写 issue 标题、重构提案、测试名时用术语表里的词，不要用它的同义词（术语表里标注了每项的禁用词）
- 需要的词不在术语表里，说明两种情况之一：你在发明项目里不存在的语言（先重新考虑），或者术语表有真实缺口（记录给 `domain-modeling`）

## 仓库布局（目标态）

Maven 多模块，**按端口与适配器（六边形）组织，不按技术角色分包**。

| 模块 | 职责 | 状态 |
以 **ADR-0003 为准：四个模块**。早先这里写过一份十几个模块的清单，它已被 ADR-0003 第 1 节否决
（模块数量与项目规模不匹配，隔离度用包 + 包间规则同样拿得到），不要按那份清单开模块。

|---|---|---|
| `airouter-core` | 领域模型 + 端口接口（**依赖树里不许出现任何框架**） | ✔ 已建（无源码） |
| `airouter-gateway` | faces / providers / transport / routing / governance / config / observe | ✔ 已建（仅包结构） |
| `airouter-agent` | Spring AI 2.0.1：ChatClient 装配 + Advisor 链 + Subagent | ✔ 已建（壳） |
| `airouter-app` | 启动装配 + **跨模块结构规则的唯一住所** | ✔ 已建 |

`gateway` 内部按端口与适配器分包，包职责写在各自的 `package-info.java` 里。

依赖方向的核心约束（由 `airouter-app` 的测试用 ArchUnit 判红，**跑在默认 `verify` 路径上**）：

- `core` **不依赖任何模块**；它的依赖树里出现 Spring / Jackson / Reactor 即构建失败（Maven enforcer，见 `airouter-core/pom.xml`）
- 没有模块可以依赖 `app`
- `faces` 与 `providers` 互不感知；`routing` / `governance` 不做 IO；`transport` 不认识业务包
- 禁止 `@Lazy`、字段注入 / setter 注入、按类型 `getBean`

规则一共八条，定义在 `airouter-app/src/test/java/io/github/airouter/arch/StructuralRules.java`。
**新增规则前先读那里的表格**：规则越多，越容易走到「加个例外算了」那条路上。
规则旁边必须写清它保护什么；「规则真的会红」由 `GuardsActuallyFireTest` 断言，不靠人记得试一次。

## 不可违反的工程纪律

这几条是从 `legacy/` 的血泪教训里提炼的，**违反它们就等于重蹈覆辙**：

1. **禁止用 `@Lazy` / setter 注入 / `ApplicationContextProvider.getBean()` 压住循环依赖。** 需要反向依赖就抽端口。一旦出现装配顺序隐式化，后面的单测就只能全 mock。
2. **流式响应必须在终态才结算结果。** 不得因为"响应头写出成功"就记为成功。终态由谁观测、怎么观测，见 `docs/adr/` 中关于流式终态上报的 ADR。
   落到代码上：`CanonicalStreamEvent.Terminal / Failed` 是终态的**唯一**形态，由 Provider 显式发出；
   `OutcomeObserver` 是结算的唯一收口点，**任何组件不得自己去看状态码或异常来判定成败**。
   上游没发结束哨兵就把连接收了时补发 `Failed`（`upstream_closed`）—— 那不是成功，内容是否完整无从判断。
   帧间静默超上限同样是 `Failed`（`UPSTREAM_TIMEOUT`）。
3. **任何流式路径上的处理逻辑必须同时有同步路径的对应实现。** 只做一半 = 静默失效。
4. **禁止在 Reactor 事件循环线程上做阻塞 IO。**
5. **配置文件里不得出现 API 密钥**，一律走环境变量。
6. **fall-closed 优先于 fail-open。** 鉴权、脱敏、限流失败时的默认行为是拒绝而非放行；确需 fail-open 必须在 ADR 里写明理由。

## 构建与验证

- JDK 21（当前 `java -version` = 21.0.12.1）；Maven 3.9.16 在 `~/soft/apache-maven-3.9.16`
- Maven Wrapper 已入库，**统一用 `./mvnw`**，不要用系统里的 `mvn`（版本可能与门禁要求不符）
- 默认构建路径即全部门禁：`./mvnw clean verify`。**不带任何 skip 参数** —— 门禁没有接受 skip 的配置入口
- 跑起来：`java -jar airouter-app/target/airouter-app-0.1.0-SNAPSHOT.jar`，健康入口在 `/actuator/health`。
  **注意端口不一定是 yml 里写的 8080**：会话环境若带着 `SERVER__PORT` / `SERVER__HOST`，Spring 的宽松绑定会静默覆盖 `server.*`
  （实测一次是 57456）。要么显式传 `--server.port=`，要么 `env -u SERVER__PORT -u SERVER__HOST` 再跑
- 档位：`local`（默认，本机 Ollama，不需要网络）/ `cloud`（真实厂商端点，三个变量全部必填）。
  切换只改 profile 或环境变量，**不改代码**；变量名清单见 `.env.example` 与 `EndpointProperties` 的常量
- **本机端点的安装、起停与实测能力边界见 `docs/runbook/local-openai-endpoint.md`**。
  需要打真实 model 在线协议形状之前先读第 6 节台账：那里记了哪些用法能用（`include_usage` 会来一帧 `choices` 为空数组的纯用量帧），
  哪些不能用（流式工具调用在小模型上不确定，不能做 golden-file 断言）。本机地址写 `127.0.0.1` 不写 `localhost` —— Ollama 只监听 IPv4
- **`verify` 需要本机模型端点在跑**：真实端点的那两条测试不做静默跳过（一个可以被跳过的兼容性测试等于没有）。
  端点没起来时它们会红并给出指路信息；起停见上面那份 runbook
- **对外 wire format 的契约测试在 `airouter-app/src/test/resources/contract/`**：一个用例一个目录，
  放 `upstream.json` + `expected.json` 两个文件即被发现，**不需要改任何测试代码**。
  流式那一份在 **`contract-stream/`**，比的是**帧序列**且包含 `[DONE]` 哨兵本身
  （`upstream.json` 里是 `{"status":..., "sse":"..."}`）。约定写在该目录的 `README.md` 里。
  未来任何框架升级之后，最先能告诉你「序列化行为变了」的就是它们
- **回放上游能控制到字节块一级**（`RecordedUpstream.replayStream(status, chunks, between)`）：
  可以造出「一个多字节字符被切在两个 TCP 段之间」「讲完但故意不发 `[DONE]`」这类真实端点给不出的样本。
  需要精确控制帧边界的测试用它，不要靠 sleep 去碰运气
- 门禁：`checkstyle`（error 级，规则在 `config/checkstyle/checkstyle.xml`）、`spotbugs`、`surefire`（含结构规则），
  外加 `validate` 阶段的 enforcer（版本、JDK、重复的上界依赖、插件版本、禁止出現抑制文件）
- **两个静态分析的作用域都包含测试源码**。因此新建一个包（含测试里的样例包）就要补 `package-info.java`，写清这个包知道什么、不该知道什么
- 没有 jacoco：没有任何故事要求覆盖率下限，在没有生产代码的阶段摆一个阈值只会得到空数字。要加就先补故事

违约的处置路径是**改代码**。确实无法避免的情形，处置路径是写一份 ADR —— 不是加一行抑制、一个排除块或一处 include 例外（ADR-0008 第 1 节）。

## Agent skills

### Issue tracker

Issue 以本地 markdown 文件跟踪，位于 `.scratch/<feature>/issues/NN-<slug>.md`，规格写在 `.scratch/<feature>/spec.md`。See `docs/agents/issue-tracker.md`.

### Triage labels

五个 canonical triage 角色名在本站取值与角色名一致，记录在每个 issue 文件头部的 `Status:` 行。See `docs/agents/triage-labels.md`.

### Domain docs

单上下文布局：根 `GLOSSARY.md` + `docs/adr/`（Maven 多模块是分层，不是独立业务上下文）。See `docs/agents/domain.md`.
