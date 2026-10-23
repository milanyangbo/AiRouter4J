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
|---|---|---|
| `airouter-core` | 领域模型 + 端口接口（**无框架依赖**，纯 Java） | ⧗ 待建 |
| `airouter-transport` | 唯一出站口：连接池 + codecs + SSRF 守卫 + 流式管道 | ⧗ 待建 |
| `airouter-face-openai` | 入站 Face：OpenAI 兼容协议 encode/decode | ⧗ 待建 |
| `airouter-face-anthropic` | 入站 Face：Anthropic Messages 协议 | ⧗ 待建 |
| `airouter-provider-openai` | 出站 Provider：OpenAI 兼容（含 DeepSeek / vLLM / Ollama） | ⧗ 待建 |
| `airouter-provider-anthropic` | 出站 Provider：Anthropic Messages API | ⧗ 待建 |
| `airouter-routing` | 实例选择 / 负载均衡 / 熔断 / 限流（零 IO，100% 可单测） | ⧗ 待建 |
| `airouter-security` | API Key 鉴权 / RBAC / 脱敏 / 配额 | ⧗ 待建 |
| `airouter-observability` | traceId / 结构化日志 / 指标 / 异常事件 | ⧗ 待建 |
| `airouter-persistence` | JPA + Flyway + Repository | ⧗ 待建 |
| `airouter-agent` | Spring AI 2.0.1：ChatClient 装配 + Advisor 链 + Subagent | ⧗ 待建 |
| `airouter-ops-tools` | Ops Agent 的工具集（数据源 = 本网关自己的可观测数据） | ⧗ 待建 |
| `airouter-admin-api` | WebFlux REST + SSE | ⧗ 待建 |
| `airouter-app` | 启动装配 | ⧗ 待建 |

依赖方向的核心约束（会被 CI 用 ArchUnit 钉死）：

- `core` **不依赖任何模块**，也不依赖 Spring / Reactor 之外的东西
- `routing` 的决策逻辑是纯函数，**不做 IO**
- 依赖倒置：实现模块依赖端口，端口不知道实现（由 CI 的 ArchUnit 规则校验）

## 不可违反的工程纪律

这几条是从 `legacy/` 的血泪教训里提炼的，**违反它们就等于重蹈覆辙**：

1. **禁止用 `@Lazy` / setter 注入 / `ApplicationContextProvider.getBean()` 压住循环依赖。** 需要反向依赖就抽端口。一旦出现装配顺序隐式化，后面的单测就只能全 mock。
2. **流式响应必须在终态才结算结果。** 不得因为"响应头写出成功"就记为成功。终态由谁观测、怎么观测，见 `docs/adr/` 中关于流式终态上报的 ADR。
3. **任何流式路径上的处理逻辑必须同时有同步路径的对应实现。** 只做一半 = 静默失效。
4. **禁止在 Reactor 事件循环线程上做阻塞 IO。**
5. **配置文件里不得出现 API 密钥**，一律走环境变量。
6. **fall-closed 优先于 fail-open。** 鉴权、脱敏、限流失败时的默认行为是拒绝而非放行；确需 fail-open 必须在 ADR 里写明理由。

## 构建与验证

- JDK 21（当前 `java -version` = 21.0.12.1）；Maven 3.9.16 在 `~/soft/apache-maven-3.9.16`
- Maven Wrapper：骨架阶段的 `mvnw` 尚未生成，暂用 `mvn`。生成后统一用 `./mvnw`
- 编译 + 测试：`mvn -T1C verify`
- CI 四道 gate 必须 fail-fast：checkstyle（error 级）/ spotbugs / test / jacoco

**新增模块时记得同步 CI，否则它不会被纳入构建与扫描。**

## Agent skills

### Issue tracker

Issue 以本地 markdown 文件跟踪，位于 `.scratch/<feature>/issues/NN-<slug>.md`，规格写在 `.scratch/<feature>/spec.md`。See `docs/agents/issue-tracker.md`.

### Triage labels

五个 canonical triage 角色名在本站取值与角色名一致，记录在每个 issue 文件头部的 `Status:` 行。See `docs/agents/triage-labels.md`.

### Domain docs

单上下文布局：根 `GLOSSARY.md` + `docs/adr/`（Maven 多模块是分层，不是独立业务上下文）。See `docs/agents/domain.md`.
