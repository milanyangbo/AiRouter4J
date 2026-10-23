# 01: 根构建文件、Wrapper 与忽略规则

> 来源：[spec.md](../spec.md) — 用户故事 9

**What to build:** 一个人在只装了 JDK 的机器上 clone 这个仓库，跑一条 Wrapper 命令就能得到一次通过的构建；全部版本号只有一处来源；构建产物与编辑器垃圾不会被提交进来。

今天是做不到的：仓库里没有任何构建文件、没有 Wrapper、没有忽略规则，所以「它能不能工作」这件事连机器都无法判断。这一步先把判断题建立起来 —— 哪怕此时 reactor 里还没有任何源码。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 在 PATH 里不存在 `mvn` 的机器上，`./mvnw clean verify` 退出码为 0（验证方式：临时把 Maven 从 PATH 移除后再跑）
- [ ] Wrapper 脚本与其配套的版本/ jar 描述文件全部入库，没有被忽略规则排除
- [ ] Spring Boot、Spring AI BOM、Java release level 三个版本号各只出现在根构建文件一处，任何模块都不得重声明（验证方式：在模块里改一个版本号应导致构建失败而非生效）
- [ ] 依赖收敛检查落在默认生命周期：两个模块引入同一库的不同版本时构建失败
- [ ] JDK 低于 21 时构建在早期阶段失败并给出可读提示
- [ ] 根构建文件声明为聚合型（`pom` packaging），此时即使四个模块都还没有源码，reactor 仍能构建通过
- [ ] 不存在任何 profile 为质量插件设置 skip 开关
- [ ] 忽略规则拦住构建输出目录、IDE 配置、系统垃圾文件、以及本地环境变量文件；一次完整构建后 `git status` 除已跟踪文档外保持干净
- [ ] `legacy/` 作为参考资料仍然被跟踪，不被忽略规则排除
