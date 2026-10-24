# 10: 持续集成四 gate

> 来源：[spec.md](../spec.md) — 用户故事 25、26、27、28

**What to build:** 每次推送的变更都会经历四个各自独立判定的 gate —— 编译与单元测试、静态分析、依赖方向与结构、集成测试；任一变红就阻断合并；没有任何一个可以被属性、标签、profile 或提交信息绕过。

放到最后落的原因：前三个 gate 在流水线刚建立时就有真实内容，但第四个（集成测试）要等前面那些票真的产出可跑的端到端测试才有意义。在此之前本地 `mvn verify` 已经是同一套判定，只是没有远端见证。

本票同时解决一个来源项目的缺口：那个仓库的 README 与依赖清单都引用了 CI 工作流，而它根本不存在 —— 声称有门禁比没有门禁更糟。

**Blocked by:** 05 非流式端到端打通、06 流式 SSE 路径、07 终态契约、08 OutcomeObserver 与熔断闭环、09 第二个 Provider

**Status:** done（基线 tag `baseline-pre-10`）—— 但**暂不自动触发**

> **暂缓说明：** 当前阶段以本机单元测试与本地冒烟为准，`.github/workflows/ci.yml` 的 `on:`
> 只剩 `workflow_dispatch`，push / PR 不再触发。四个 gate 的**分组机制**已经落在代码里
> （每个测试类的 `@Tag` + `-Dgroups=` 挑选、`EveryTestBelongsToAGateTest` 守门），
> 后期要开启只需把 `push` 与 `pull_request` 两行加回 `on:`，其余不用动。
>
> 暂缓不影响已经落地的两件事：① 分组标记本身；② 流水线上抓到并修掉的三个真问题
> （`fetch-depth: 0`、两条依赖固定 sleep 的用例、差异断言读工作区而在干净检出上空过）——
> 这三处修的是**代码**，不是流水线，本地一样受益。

- [x] 每次推送触发：编译与单元测试、静态分析、依赖方向与结构、集成测试，四个 gate 分别独立报告结果
      —— `.github/workflows/ci.yml`，四个 job 并行，无相互依赖
- [x] 任一 gate 变红阻断合并（在保护分支上生效）—— `rewrite/p0` 上四个 job 均为 required status check
- [x] 没有任何属性、标签、profile 或提交信息可以跳过某个 gate
      —— 四个 gate 显式传 `-Dxxx=false`（skipTests / checkstyle.skip / spotbugs.skip / enforcer.skip /
      maven.test.skip）；pom 里不许出现 profile 与 skip（gate 3 判）；测试里不许出现 `@Disabled`
      这类注解；抑制文件由 enforcer 判；`[skip ci]` 由「required check 不报告即为阻断」兜住
- [x] 流水线使用仓库内置的 Wrapper 构建，不依赖预装的 Maven 版本 —— 四个 job 都是 `./mvnw`
- [x] 前三个 gate 除依赖解析外不需要外部网络 —— 只有 gate 4 会往外打流量
- [x] 需要真实模型端点的集成测试，其凭证只来自流水线密钥；密钥缺失时该 gate 明确失败并说明原因，
      而不是静默通过 —— 配了地址没配密钥即 `exit 1` 并给出指路信息；没配任何密钥时在 runner 本机
      起一个（Ollama + qwen2.5:0.5b），于是一个还没配过密钥的仓库里这一 gate 也有真实内容
- [x] 流水线总耗时可接受：把最慢的 gate 与其他 gate 并行，不必串行等待 —— 四个 job 并行，
      最快的一道约 1 分钟，最慢的一道（含装 Ollama 与拉模型）约 4 分钟
- [x] 一次真实的红灯被复现过：故意破坏其中一个 gate 确认流水线确实报红且阻断

## 实现记录

**分派规则**：每个测试类用 `@Tag` 标出自己属于哪一道 gate（unit / integration / architecture），
由 `-Dgroups=` 挑选。划分标准是**启不启容器、出不出网**，不是目录位置。

**两处「绿但什么都没跑」，都是本票实际踩到的：**

1. ArchUnit 自带的引擎（`@AnalyzeClasses` + `@ArchTest`）看不见类上的 `@Tag` ——
   `ModuleStructureTest` 打了标记却在按 gate 筛选时被整类漏掉，9 条结构规则一条没跑，报告照样绿。
   改成与同包另外两个测试一致的写法（自己导入类、`@Test` 里逐条 check），
   并由 `EveryTestBelongsToAGateTest` 禁止再次引入那个引擎。
2. 一道 gate 的测试数为 0 时构建仍是绿的。每个 gate 跑完都数一遍实际执行过的测试数
   （`scripts/ci/count-ran-tests.py`），为 0 判红；gate 2 不跑测试，改为断言每个模块都留下了
   checkstyle 与 spotbugs 的结果文件。

**两个「命令看着在跑其实没判」的细节：**

- gate 3 不写 `enforcer:enforce`：直接调插件目标用的是 default-cli 执行，读不到 pom 里
  两个 execution 配置的规则，于是在日志里断言两个 execution id 都出现过。
- gate 2 把工具目标与生命周期阶段写在同一条命令里：单独跑 `spotbugs:check` 会在没有 reactor
  的会话里解析 SNAPSHOT，以「依赖解析失败」告终 —— 那是假红。

**第一次在流水线上跑出的三个问题（本地全绿）：**

- checkout 默认只取一层提交，tag 不在工作区，结构规则里那条差异统计因此拿不到
  `baseline-pre-09`（改成 `fetch-depth: 0`）。
- `CancellationEndToEndTest` 两条依赖固定 sleep 的用例不成立：停在第几块取决于内核 socket
  缓冲区大小，本机与 runner 上差了一倍。改成等数字自己停，并把断言换成「样本有没有写到头」。
- 取消之后的在途收回可能要等到「上游被取消」或「帧间静默超时」，5 秒不够，等到 20 秒
  （断言仍是「必须回到零」）。

细节与取舍见 [ADR-0010](../../docs/adr/0010-ci-four-gates.md)。
