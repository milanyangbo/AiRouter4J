# ADR-0010: CI 四个 gate 的边界与不可绕过

- **状态**：已接受
- **日期**：2026-10-24
- **相关**：[0008](./0008-verification-and-quality-gates.md)（四个 gate 的决定在本 ADR 里落地）、[0003](./0003-module-structure-and-dependency-direction.md)（第三个 gate 判的内容）

## 背景

ADR-0008 已经决定了「CI 至少四个 gate」，但没有回答三个**实现层面**的问题，
而这三个问题恰好决定门禁是不是真的门：

1. 一个测试属于哪一道 gate，这件事由谁说了算？写在测试类里、写在目录结构上、
   还是写在流水线里？
2. 一道 gate 一个测试都没跑到时，构建是绿的。**「绿但什么都没跑」比红灯危险得多。**
3. 「没有任何东西能跳过某个 gate」这句话，具体到命令行是什么？

票 10 落地时在这三条上各踩到一个坑，本 ADR 记的是坑本身与随之定下的边界。

## 决策

### 1. 归属写在测试类上：`@Tag`，取值只有三个

| gate | 判定内容 | 命令 |
|---|---|---|
| 1 编译与单元测试 | 编译全部模块 + 不启容器、不出网的测试 | `./mvnw test -Dgroups=unit` |
| 2 静态分析 | checkstyle + spotbugs（含测试源码） | `./mvnw test-compile checkstyle:check spotbugs:check` |
| 3 依赖方向与结构 | enforcer 的依赖规则 + ArchUnit 的结构规则 | `./mvnw test-compile test -Dgroups=architecture` |
| 4 集成测试 | 启容器或打真实端点的测试 | `./mvnw test -Dgroups=integration` |

划分标准是**启不启容器、出不出网**，不是目录位置：同一个包里的测试
（例如 `app` 包下的启动校验与凭证扫描）会因此落到不同的 gate。
写在目录上会逼着人为了归位而移动文件，写在类上则允许一个类自己说明自己属于哪里。

代价：新写的测试类可能忘了打标记。**这一情形由一条结构规则判红**
（`EveryTestBelongsToAGateTest`：没有标 gate 的测试类、标了不存在的 gate、
带 `@Disabled` 之类关掉测试的注解，都会让 gate 3 变红），而不是靠人记得。

### 2. 「跑过但没跑到」必须能被发现

本票实际遇到两个，都已经在流水线上钉住：

**① ArchUnit 引擎看不见 `@Tag`。** `ModuleStructureTest` 原本用
`@AnalyzeClasses` + `@ArchTest`，标记是打了，但 `-Dgroups=architecture` 筛不到它 ——
9 条结构规则一条都没跑，报告照样绿。原因是那个引擎自己管发现过程，不走
JUnit Platform 的标签过滤。处置：改成与同包另外两个测试一致的写法
（`ClassFileImporter` + `@Test`），并加一条断言禁止再次引入那个引擎。

**② 一道 gate 的测试数为 0 时，构建是绿的。** 四个 gate 里三个（1/3/4）跑完都要数一遍
`target/surefire-reports` 里记录的测试数，为 0 就判红（`scripts/ci/count-ran-tests.py`）。
gate 2 不跑测试，改为断言每个模块都留下了 checkstyle 与 spotbugs 的结果文件 ——
「工具没跑」与「工具跑了且通过」在退出码上是一样的，只能看产物。

同理，gate 3 还要在构建日志里看到 enforcer 的两个 execution id：
直接调 `enforcer:enforce` 用的是 default-cli 执行，读不到 pom 里配置的规则，
看上去在跑其实一条都没判。

### 3. 不可绕过：四种逃生舱各有各的判法

| 逃生舱 | 由谁判红 |
|---|---|
| `checkstyle.skip` / `spotbugs.skip` / `enforcer.skip` / `skipTests` / `maven.test.skip` | 四个 gate 都显式传 `-Dxxx=false`（-D 的优先级高于 pom 属性与环境变量），且 gate 3 判 pom 里不许出现这些串 |
| Maven profile | gate 3 判 pom 里不许出现 `<profile>` |
| 抑制文件 | enforcer 的 `requireFilesDontExist`（票 03 已有） |
| `@Disabled` / `@Ignore` / `@EnabledIf*` | 结构测试判红（gate 3） |

刻意没有 `paths` / `paths-ignore`：按「改了哪些文件」决定跑不跑某些 gate，
本身就是一条可以绕过门禁的路径。

**提交信息里的 `[skip ci]` 是唯一无法在本仓库内拦住的手段** —— 那是 GitHub 的行为。
它的兜底在保护分支上：四个 job 都是 required status check，一次没有报告的 check
不会变成绿，因此合并同样被阻断。

### 4. 集成测试打真实端点

仓库配置了 `AIROUTER_ENDPOINT_BASE_URL` 密钥时用它；**配了地址却没有密钥时 gate 4
明确失败并说明原因**，而不是静默跳过 —— 一个可以被跳过的兼容性测试等于没有。
没有配任何密钥时，在 runner 本机起一个（Ollama + `qwen2.5:0.5b`），
于是一个还没配过密钥的仓库里这一 gate 也有真实内容。

前三个 gate 除依赖解析外不需要外部网络；只有 gate 4 会真的往外打流量。

### 5. Wrapper

四个 gate 都用仓库自带的 `./mvnw`，runner 上不依赖预装的 Maven 版本
（`distributionType=only-script` 的 wrapper，只要求 JDK）。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 用目录结构分派 gate（src/test/java vs src/integration/java） | 为了让测试归位而移动文件，会与「按端口分包」的结构规则打架；且目录位置说不出「这个测试为什么属于这里」 |
| 用 `*IT` 后缀 + failsafe | 与上面同理，且要多引一个插件；而本项目判断依据是「启不启容器」而不是「是不是慢」—— 慢不是分类依据 |
| 一个 `verify` 跑完所有事再靠日志分辨 | 报告只有一个结论「构建失败」，看不出是哪个环节；四个并行 job 才有四个独立结论 |
| gate 4 缺密钥时跳过并标记 | 静默通过的 gate 会让人以为验证过；本仓库三个真机测试都刻意不做静默跳过（同一取舍） |

## 后果

**正面**

- 每一次推送都得到四个可分别定位的结论
- 「规则存在但没跑」这种最隐蔽的失效形态有断言守着

**负面**

- 三个 job 各自做一次 JDK 与依赖准备，机时比单 job 多；换来的是并行与可定位
- gate 4 要在 runner 上装 Ollama 并拉模型（数百 MB），是最慢的一道
- `@Tag` 与 `-Dgroups=` 的取值必须一致：改一处就得改另一处（由结构规则兜住一半）

## 如何验证

- [x] `.github/workflows/ci.yml` 存在，四个 job 各自独立报告
- [x] 本地 `./mvnw clean verify` 全绿（120 个测试），四个 gate 各自单独跑过一遍
- [x] 真实流水线上跑绿一次（run 40097339088）
- [x] 真实红灯复现一次：故意破坏其中一个 gate，确认该 gate 报红且 PR 被阻断
- [x] 保护分支上四个 job 均为 required status check
