# 02: 四个模块骨架与依赖方向闸门

> 来源：[spec.md](../spec.md) — 用户故事 12、13、14

**What to build:** 四个模块在同一次构建里编译通过，并且结构性约束由机器判定而不是靠自觉 —— 有人让 `core` 依赖了某个框架、或者有人写了 `@Lazy`、字段注入、按类型 `getBean()`，构建会红。

这一步是把 ADR-0003 与 ADR-0006 里最容易被一句「先加个例外」稀释掉的两条约束，从文档搬进默认构建路径。刻意少写规则：规则越多，越容易走到「算了加个抑制」那条路上，来源项目那份 186 行抑制文件就是那条路的终点。

**Blocked by:** 01 根构建文件、Wrapper 与忽略规则

**Status:** ready-for-human

- [x] reactor 包含 `core`、`gateway`、`agent`、`app` 四个模块，`mvn clean verify` 在无源码内容的情况下也能编译全部模块
- [x] `core` 模块的依赖树中不出现 Spring、Jackson、Reactor 或任何其他框架制品 —— 由一项依赖分析断言，出现即失败
- [x] `agent` 模块是壳：足够让 `app` 把它装配起来，但不包含任何编排逻辑（本票不实现 Agent 行为）
- [x] 没有模块可以依赖 `app`，由结构检查断言
- [x] 源码中出现 `@Lazy`、字段注入、或按类型查找 bean 这三种写法时构建失败
- [x] `gateway` 内部各包之间允许的引用方向被显式编码并强制执行；新增一处非法引用导致构建失败
- [x] 结构检查运行在默认 `verify` 路径上，不在 profile 里、不需要手工执行某个 goal
- [x] 反向验证存在：`gateway` 与 `app` 中正常使用框架注解不会被误伤，即门禁不能宽到让普通 Spring 写法也红
- [x] 每条被编码的规则旁边有一行说明它保护什么，规则总数保持在个位数

## 落地形态

| 位置 | 内容 |
|---|---|
| `airouter-core/pom.xml` | R1：enforcer `bannedDependencies`，`searchTransitive=true`，禁 Spring / Jackson / Reactor |
| `airouter-gateway/**/package-info.java` | 六个包（faces / providers / transport / routing / governance / config）的职责说明 |
| `airouter-agent` | `AgentConfiguration`：一个空壳装配点，无编排逻辑 |
| `airouter-app` | `AiRouterApplication` + 结构规则测试（它是唯一在 classpath 上能看到全部四个模块的模块） |
| `airouter-app/.../arch/StructuralRules.java` | R2 到 R8 的定义，每条旁边写清它保护什么 |
| `airouter-app/.../arch/ModuleStructureTest.java` | 真实模块上的断言，跑在 surefire 的 test 阶段 |
| `airouter-app/.../arch/GuardsActuallyFireTest.java` | 违规样例上断言「规则真的判红」 |
| `airouter-app/.../arch/OrdinarySpringUsageIsNotFlaggedTest.java` | 普通写法上断言「规则不误伤」 |
| `airouter-app/.../arch/RulesCoverNonEmptyPackagesTest.java` | 规则点名的包必须有类，防止规则因改名而静默空转 |

八条规则：R1 core 纯净 / R2 无人依赖 app / R3 faces 与 providers 互不感知 / R4 routing 与 governance 不碰协议与 IO /
R5 transport 不认识业务包 / R6 禁 @Lazy / R7 禁字段与 setter 注入 / R8 禁按类型 getBean。

## 验收记录

- `./mvnw clean verify` 退出码 0，20 个测试全绿（含 8 条结构规则在真实模块上的断言、8 条「规则会红」的断言、2 条「不误伤」的断言、1 条装配链路断言、1 条包非空断言）
- **R1 反向验证**：给 `airouter-core` 临时加上 `spring-context`，`validate` 阶段即失败，报出 `org.springframework:spring-context:7.0.10 <--- banned`
- **R3/R6/R7/R8 反向验证**：在 `gateway/faces` 临时加一个引用 `providers` 的类，在 `agent` 临时加一个带 `@Lazy` + `@Autowired` 字段 + `getBean(Class)` 的类，四条规则同时判红，报出具体类名；删除后恢复绿色
- **R2 说明**：模块层面的反向引用由 Maven 自己先挡一层（循环依赖无法构建），第二层由 R2 覆盖非 Java 依赖路径；会红的证据由 `archtest.layout` 样例断言
- **空转防线**：ArchUnit 1.5 默认「无类可查即失败」，与本票「无源码内容也要能构建」冲突，因此在 `archunit.properties` 里关掉该默认，改由「规则会红」与「包非空」两个测试承担，两者都不依赖源码规模
- **不误伤**：构造函数注入、`@Value` 字段、`@Bean` 方法、`@ConditionalOnProperty`、faces 只用 core、config 装配所有人 —— 全部保持绿色

## 遗留与本票刻意不做的事

- `gateway` 尚未引入 HTTP 运行时（WebFlux 等）：先放进去的依赖后面没人会再检查它还该不该在，等做到入站路径的那一票再决定
- `app` 没有配 `spring-boot-maven-plugin` repackage：打包与环境配置属于票 04
- 模块版本的单点来源：根 POM 的 `dependencyManagement` 里刻意**没有** `airouter-app`，与 R2 保持一致
- `banDynamicVersions` 对本仓库自身模块的 `0.1.0-SNAPSHOT` 开了 `ignores`（同一 reactor 内解析，不存在「明天拉到别的东西」的问题）；外部快照仍然会被判红
