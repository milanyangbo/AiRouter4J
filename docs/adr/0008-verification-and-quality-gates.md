# ADR-0008: 验证策略与质量门禁

- **状态**：已接受
- **日期**：2026-10-23
- **相关**：全部其他 ADR —— 它们是本 ADR 要锁住的对象

## 背景

来源项目看起来测试很充分：**426 个测试文件、4,266 个 `@Test`**。
但结构决定了这些测试提供不了多少保护：

| 事实 | 含义 |
|---|---|
| `@SpringBootTest` 只有 **4 个** | 几乎没有真正跑起来的容器；绝大多数测试不经过装配，也就发现不了装配期问题 |
| 17 个命名带 `IntegrationTest` 的类全部使用 mock | 名字是集成测试，实质是单元测试 |
| 6 个真正需要 Redis / Postgres 的测试被 `@EnabledIfEnvironmentVariable` 关掉，且该变量从未设置 | 它们不会执行，也不报错，只是在报告里显示"通过" |
| `config/suppressions.xml` 186 行，对 `.*\.java` 抑制了文件长度、命名、`NeedBraces` 等规则 | checkstyle 对整个代码库事实上不生效 |
| `pom.xml` 的 `dev` / `prod` profile 里 `checkstyle.skip`、`spotbugs.skip`、`jacoco.skip` 全为 `true` | 默认构建路径下所有门禁被关闭 |
| `.github/` 目录不存在 | 没有任何 CI |

结论：**这个项目的端到端验证能力接近于零。**

这不是测试写得多不多的问题，而是**测试能否发现"编译通过但行为变了"这类变更**的问题。
而 Jackson 2 → 3 的迁移（见 [0002](./0002-tech-stack-and-version-pinning.md)）
恰恰主要产出这种静默变更 —— 这也是为什么验证策略必须先于迁移落地。

## 决策

### 1. 门禁不允许被跳过

**不提供任何 `skip` 开关，不设 `suppressions.xml`。**

来源项目的教训是具体的：一旦存在全局抑制或 profile 级跳过，
门禁就不再是门禁，而变成一个可以随手关掉的提示。

checkstyle 的规则数量保持克制，但每一条都真正生效。
规则违约的修法是改代码，不是加例外。

### 2. 测试金字塔（按实际用途分层，不按比例凑数）

| 层 | 用途 | 手段 |
|---|---|---|
| **契约测试** | 锁住对外 wire format。这是最需要的一层，也是来源项目完全没有的 | golden file：给定输入字节，断言输出的事件序列 / JSON 完全一致 |
| **集成测试** | 锁住跨组件行为，尤其是流式的终态与治理联动 | Testcontainers 起真实的 Postgres / Redis；`StepVerifier` 断言事件流 |
| **单元测试** | 锁住纯逻辑（限流算法、选择算法、翻译函数） | 无容器、无 mock 框架为主 |

关键区别：来源项目的单测大量使用 mock 来验证"我调用了我以为我要调用的东西"，
这类断言在重构时必然失效却从不报错。这里的单测优先针对**有输入输出的函数**。

### 3. 三条必须能被自动验证的核心契约

这三条直接对应本项目要修的缺陷，是验证策略的最低验收线：

| 契约 | 怎么验 | 对应 ADR |
|---|---|---|
| **流式失败必须驱动熔断** | 让上游连续流式失败 N 次，断言熔断器进入 OPEN。来源项目里这条永远不会通过 | [0006](./0006-streaming-terminal-outcome-contract.md) |
| **CHUNK_PARSE_COUNT = 1** | 计数器断言：一次流式处理中对同一份字节的 JSON 解析次数为 1 | [0005](./0005-canonical-intermediate-representation.md) |
| **新增 provider 不需要改 face** | 新增一个 provider 后，`faces` 包的改动量为零（可作为 CI 的结构检查） | [0003](./0003-module-structure-and-dependency-direction.md)、[0005](./0005-canonical-intermediate-representation.md) |

### 4. 流式必须有专门断言

`StepVerifier` 要断言的不只是"有没有数据"，而是：

- 终态事件**恰好出现一次**（无论是正常、异常还是超时路径）
- 取消路径下 `Terminal` 不出现，且熔断器状态不变
- 跨 chunk 边界的内容不会被切坏

### 5. CI 至少四个 gate

```text
compile & unit    →  mvn -T1C test
static analysis   →  checkstyle + spotbugs（不 skip）
integration       →  Testcontainers 起真实依赖，跑集成测试
structure         →  依赖方向校验（ADR-0003）
```

任何一条红了，合并就被阻断。

### 6. 补上缺失的构建基建

来源项目缺 `.mvn/` 导致 `./mvnw` 不可用（但文档里写着用它）。这里必须：
保留 Maven Wrapper 并保证其可用，让项目在只有 JDK 的机器上也能一把拉起来。

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 保留一个小的 suppressions 文件用于"遗留代码例外" | 一旦开了口子，例外会增长；且本项目是全新构建，`legacy/` 只是素材而非生产代码，因此不存在需要豁免的遗留产物 |
| 先做迁移再补测试 | Jackson 3 的静默变更在没有契约测试的情况下无法识别，等于盲改 |
| 用覆盖率作为主要门禁 | 来源项目证明了：高 Mockito 覆盖率与真实验证能力几乎没有相关性。覆盖率可以报告，但不作为准入门槛 |
| 用 mock 替代 Testcontainers | mock 出来的 Redis / Postgres 不会暴露序列化差异、连接行为、事务语义，而这些恰恰是出问题的地方 |
| 把 CI 放到最后补齐 | CI 的作用是让每一次改动都有反馈；放在最后等于整个迁移过程都处于无反馈状态 |

## 后果

**正面**

- Jackson 3 迁移期间的每一次行为变更都能被契约测试捕捉
- 依赖方向的破坏在 CI 阶段暴露，而不是在生产事故里发现
- `mvn verify` 一条命令即可获得可判断的结果

**负面**

- Testcontainers 集成测试需要 Docker，本地开发依赖 Docker 可用；构建时间显著变长
- golden file 需要人工维护：协议变更时要同步更新，这会带来一点摩擦（这是刻意的，它强制人去审视变更）
- 没有 skip 开关意味着在本地临时构建时也无法绕开门禁，需要接受稍慢的反馈

## 如何验证

本 ADR 是关于验证本身的，所以它自己的验收标准很直接：

- [ ] `grep -rn "skip" pom.xml` 中不出现任何门禁 skip 配置
- [ ] 仓库根目录不存在 checkstyle suppressions 文件
- [ ] `./mvnw clean verify` 在只有 JDK（无预先安装的 Maven）的机器上可以直接运行
- [ ] CI 配置存在，且包含上述四个 gate
- [ ] 破坏任意一条核心契约（例如让某个组件绕过 `OutcomeObserver`）会导致 CI 失败
