# 01: 根构建文件、Wrapper 与忽略规则

> 来源：[spec.md](../spec.md) — 用户故事 9

**What to build:** 一个人在只装了 JDK 的机器上 clone 这个仓库，跑一条 Wrapper 命令就能得到一次通过的构建；全部版本号只有一处来源；构建产物与编辑器垃圾不会被提交进来。

今天是做不到的：仓库里没有任何构建文件、没有 Wrapper、没有忽略规则，所以「它能不能工作」这件事连机器都无法判断。这一步先把判断题建立起来 —— 哪怕此时 reactor 里还没有任何源码。

**Blocked by:** None (can start immediately)

**Status:** ready-for-human

- [x] 在 PATH 里不存在 `mvn` 的机器上，`./mvnw clean verify` 退出码为 0（验证方式：`env -i` 只留 JDK 与系统 PATH 后跑）
- [x] Wrapper 脚本与其版本描述文件全部入库，没有被忽略规则排除（`git check-ignore` 逐一确认未命中）
- [x] Spring Boot、Spring AI BOM、Java release level 三个版本号各只出现在根构建文件一处（见下方「边界」）
- [x] 依赖收敛检查落在默认生命周期：同一库被解析到低于其传递要求的版本时构建失败
- [x] JDK 低于 21 时构建在早期阶段失败并给出可读提示（已用本机 JDK 17 实测，`validate` 阶段即失败）
- [x] 根构建文件声明为聚合型（`pom` packaging），此时即使四个模块都还没有源码，reactor 仍能构建通过
- [x] 不存在任何 profile，因此也不存在为质量插件设置的 skip 开关
- [x] 忽略规则拦住构建输出目录、IDE 配置、系统垃圾文件、以及本地环境变量文件；一次完整构建后 `git status` 只出现本次有意变更
- [x] `legacy/` 作为参考资料仍然被跟踪，不被忽略规则排除

## 实现结果

产物：根 `pom.xml`、`.mvn/wrapper/maven-wrapper.properties` + `mvnw` / `mvnw.cmd`（`only-script` 形态，不入库二进制 jar）、`.gitignore` 增量。

根 POM 挂载了五条 enforcer 规则，全部绑定在 `validate` 阶段：

| 规则 | 它挡什么 |
|---|---|
| `requireMavenVersion [3.9.0,)` | 本机 Maven 过旧。错误信息直接指向 `./mvnw` |
| `requireJavaVersion [21,)` | JDK 版本不对，在最早期就停，不必等到编译 |
| `requireUpperBoundDeps` | 同一库被解析到低于其传递要求版本的情形（含两个模块各引一版的场景） |
| `banDynamicVersions` | `LATEST` / `RELEASE` 这类浮动版本 —— 「同一次构建今天和明天结果不同」的头号来源 |
| `requirePluginVersions` | 插件没有显式版本。这条在本次实践中立刻生效：Maven 默认生命周期带进来的 `maven-site-plugin` 没有版本，构建当场变红，补进 `pluginManagement` 后才绿 |

依赖版本统一收在根 POM 的 `dependencyManagement`（目前只有 Spring AI BOM）；插件版本统一收在 `pluginManagement`，后续模块引用时不写版本号。

**被验证过会红，而不只是聊胜于无**：JDK 17 → `validate` 失败；注入 jackson-databind 2.15.0 / jackson-core 2.12.0 的冲突 → `Rule 2` 失败并打印依赖路径；缺 `maven-site-plugin` 版本 → `Rule 4` 失败。

## 已知边界（不是缺陷，是能力止步处）

**子模块在自己的 `<properties>` 里覆盖 Boot / Spring AI / Java 版本号，目前不会让构建失败。**

原本用来拦这件事的 `requireManagedVersions` 来自 `extra-enforcer-rules`，实测 **1.8.0 / 1.9.0 / 1.10.0 / 1.12.1 四个版本 × enforcer 3.5.0 / 3.6.1 两种插件版本全部无法实例化** —— 根因是 `NoSuchMethodException: EnforceManagedDepsRule.<init>()`，即规则类没有无参构造器，而 enforcer 以反射方式实例化外部规则。这是社区包与 Maven 3.9 / enforcer 3.x 的既有不兼容，不是本项目的配置问题，短期内也没有修复迹象。

替代承担的安排：**票 02 的模块结构约束**。模块里既不允许声明依赖版本，也不允许覆盖父级 `<properties>` 中的版本 —— 这件事由结构检查承担比由 enforcer 承担更合适，因为它表达的是模块结构的约定，而不是依赖解析的结果。这一点已写进根 POM 的注释，不会随实现一起丢失。

## 备注

- Wrapper 采用 `only-script` 形态（Maven 3.9.16），比传统形态少一个需要入库的二进制 jar
- `.gitignore` 另补了 `.flattened-pom.xml`（长子 POM 中间产物）
