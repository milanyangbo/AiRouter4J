# ADR-0002: 技术栈与版本锁定

- **状态**：已接受
- **日期**：2026-10-23
- **相关**：[0001 定位](./0001-project-scope-and-lineage.md)、[0003 模块结构](./0003-module-structure-and-dependency-direction.md)、[0004 Spring AI 边界](./0004-spring-ai-boundary.md)、[0008 验证策略](./0008-verification-and-quality-gates.md)

## 背景

来源项目是 Java 17 + Spring Boot 3.5.5。它在 Jackson 2 上、不依赖 Spring AI。

本项目要引入 Spring AI 2.x 来做 Agent 层，这一步会把整个技术栈的版本一起拽上去。下面这条约束是实测出来的，不是推测：

从 Maven Central 拉下 `spring-ai-starter-model-openai-2.0.1.pom`，其中写死的依赖为：

```xml
<artifactId>spring-boot-starter-webclient</artifactId>  <version>4.1.1</version>
<artifactId>spring-boot-starter-restclient</artifactId>  <version>4.1.1</version>
```

**结论：Spring AI 2.0.1 与 Spring Boot 4.1.x 是绑定关系，不是推荐搭配。**
把一个 Boot 3.5 的 classpath 与一组 Boot 4.1 的 starter 混在同一个 classpath 里，
等于同时加载两套不兼容的自动配置与基础设施 —— 这条路不存在。

于是选择变成二选一，没有中间状态：

- 用 Spring AI 2.x → 必须 Boot 4.1 → 必须 Jackson 3
- 留住 Boot 3.5 → 不能用 Spring AI 2.x

本项目选择前者。

## 决策

### 1. 版本锁定（全部实测确认可解析）

| 组件 | 版本 | 备注 |
|---|---|---|
| Spring Boot（`spring-boot-starter-parent`） | **4.1.2** | 统一放到父 POM，子模块不重复声明 |
| Spring AI BOM | **2.0.1** | `<dependencyManagement>` 里 import |
| `spring-ai-agent-utils` | 0.13.0 | 编译基线是 2.0.1 |
| `spring-ai-session-bom` | 0.10.0 | 编译基线是 2.0.1 |
| Java | **21** | `maven.compiler.release` |
| `maven-checkstyle-plugin` | 3.6.0 | 与 `checkstyle` 10.21.2 配对 |
| `spotbugs-maven-plugin` | 4.8.6.6 | 4.9.3 实测 HTTP 404，不存在 |

版本统一写在根 POM 的 `<properties>`，不在子模块里散落。任何版本调整都是单点修改。

### 2. 为什么 pin 在 Spring AI 2.0.1 而不是更新的版本

`spring-ai-agent-utils:0.13.0` 与 `spring-ai-session:0.10.0` 都编译在 **2.0.1** 上。
升到更新的 Spring AI 意味着这两个社区包处于未经组合测试验证的状态。

`spring-ai.version` 是一个属性，改它只需要动一处；但它是**协同变更**而不是日常升级 ——
升级前需要确认社区包也已跟进。

### 3. 为什么是 Java 21

**不是因为框架强制。** Spring Framework 7 与 Spring Boot 4 的基线仍是 Java 17。

选 21 是为了两件有实际收益的东西：

- **虚拟线程**：网关的瓶颈是大量并发等待上游响应，虚拟线程对这种负载是直接收益
- **JSpecify 空安全**：网关处理的是外部不可信输入，编译期的可空性标注能挡掉一类空指针

这条要写清楚，因为"某某版本强制某某 JDK"是很容易口口相传但经不起核对的说法。

### 4. Jackson 3 全量接受，不做双 classpath

Boot 4.1 内置 Jackson 3（`tools.jackson`）。来源项目有大量手写的流式 JSON 翻译代码会受影响。

**不保留 Jackson 2。** 理由：双 classpath 会让序列化行为的归属变得难以判断 ——
同一个对象走不同序列化器可能产出不同 JSON，而这类差异恰恰不会编译报错。
一次性跟随，用契约测试拦截行为漂移（见 [0008](./0008-verification-and-quality-gates.md)）。

### 5. 不使用独立 A2A 包

`spring-ai-a2a-server:0.3.0` 的 POM 写死依赖 `spring-ai-client-chat:2.0.0-RC1`，未针对 GA 重新发布。
不引入。若将来需要跨系统 Agent 互操作，走 `spring-ai-agent-utils` 自带的 A2A 支持或官方后续版本。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 网关留 Boot 3.5、只把 Spring AI 加进来 | Spring AI 2.0.1 直接依赖 Boot 4.1.1 的 starter，classpath 层面不成立 |
| Agent 层做成独立进程跑 Boot 4.1、网关留 3.5 | 技术上可行，但会让网关本体长期停留在旧栈上，而 Jackson 3 的迁移迟早要做；两个 Boot 大版本长期并存也会让构建与依赖管理复杂化。既然目标是整体前移，就把这一步一起做掉 |
| 同时保留 Jackson 2 与 Jackson 3 | 序列化行为归属不明，是最难排查的一类问题 |
| 保留来源项目自带的 160 个 tracing 类，不引入 OpenTelemetry | 手写 tracing 的维护成本与覆盖面都不划算，见 ADR-0001 删除清单 |

## 后果

**正面**

- 单一版本栈，没有 classpath 里的版本夹带
- 虚拟线程对网关这种高并发等待型负载是直接收益

**负面（必须承担的代价）**

- **Jackson 2 → 3 是本次最大的迁移风险**。来源项目里手写的 `ObjectNode` 构建、`readTree`、自定义序列化器都要重写。最危险的不是编译报错的那部分，而是**编译通过但 JSON 形态变了**的那部分
- Spring AI 2.x 自身在演进（`spring-ai-session` 计划在 2.1 被吸收进本体），后续仍有协调成本
- 一次变更里同时包含"包名迁移 + Boot 4 API 变更 + Jackson 3 变更"，单次审阅负担较大 —— 这也是 [0008](./0008-verification-and-quality-gates.md) 要求先补测试再动结构的直接原因

## 如何验证

- [ ] 根 POM 里不出现 `checkstyle.skip` / `spotbugs.skip` / `jacoco.skip` 中的任何一个（来源项目正是在这里失效的）
- [ ] enforcer 规则锁定 Maven ≥ 3.9、JDK ≥ 21
- [ ] 有一份清单记录 Jackson 2 → 3 的行为变更，且每一条都有对应的契约测试
- [ ] `mvn dependency:tree` 中不出现两个版本的 Jackson / Spring Boot / Spring AI
