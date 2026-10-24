# ADR-0010: CI gate —— 边界、暂缓与恢复条件

- **状态**：已接受，**暂缓自动触发**（2026-10-24 决定：功能优先，CI 后置）
- **日期**：2026-10-25（第 2 版重写）
- **相关**：[0008 验证策略](./0008-verification-and-quality-gates.md)

## 背景

来源项目没有 `.github/`，也就是**没有任何 CI**。它同时缺的还有：默认 profile 里三个 `skip=true`
和一份 186 行抑制文件 —— 两者叠加的结果是门禁在默认构建路径上全部关闭。

本项目要的 CI 不是「跑一遍测试」，而是**让每一次改动都有反馈**。四个 gate 的划分依据是
「不同类的缺陷要在不同的地方被挡住」：

```text
compile & unit    →  编译与纯逻辑
static analysis   →  风格与静态分析（不 skip、无抑制）
integration       →  跨组件行为，打真实依赖
structure         →  依赖方向与装配手法
```

暂缓的原因：功能优先。功能铺开阶段，CI 的边际收益低于它带来的往返成本，
而本地 `./mvnw clean verify` 已覆盖同样的四个 gate（[0008](./0008-verification-and-quality-gates.md) 第 11 节）。

## 决策

### 1. 四个 gate 的边界

| gate | 命令 | 判什么 |
|---|---|---|
| compile & unit | `mvn -T1C test -Dgroups=unit` | 编译与纯逻辑 |
| static analysis | checkstyle + spotbugs（**不 skip、无抑制**） | 风格与静态分析，范围含测试源码 |
| integration | `-Dgroups=integration` | 跨组件行为；依赖缺席的降级路径 |
| structure | `-Dgroups=architecture` | 依赖方向、装配手法、测试归类 |

任何一条红了，合并就被阻断。

### 2. 暂缓：不自动触发

`ci.yml` 保留，但只在手动触发时运行。它**不是被删掉的设计**，而是被冻结的：
四个 gate 的定义、分组机制与防「空过」的手段都已实现，恢复时不需要重新设计。

### 3. 「绿但什么都没跑」必须被判出来

这类假绿有两种形态，都要有对应的判据：

| 形态 | 例子 | 判据 |
|---|---|---|
| **跑了 0 个测试** | 分组过滤写错，一个测试都没进来 | `scripts/ci/count-ran-tests.py` 断言报告里的实际执行数 > 0 |
| **断言空过** | `SecondProviderTouchesNoFaceTest` 原先用 `git status --porcelain` 取差异，CI 是干净检出，结果恒为空 → 永远通过 | 改用 `git diff --name-only <baseline>..HEAD`；且 CI 必须 `fetch-depth: 0`（否则拿不到基线 tag） |

第二条是最危险的：它不报错，只是在报告里显示「通过」—— 与来源项目那 6 个被环境变量关掉的测试是同一类失效。

### 4. 暂缓期间以什么替代

- **唯一权威验证**：`./mvnw clean verify`，不带任何 skip 参数
- **分组仍然生效**：`@Tag` 是代码里的分类，暂缓 CI 不影响它；`EveryTestBelongsToAGateTest` 继续判红
- **每个阶段打一次基线**（`baseline-pre-NN`）：给「这一阶段改了什么」留一个可 diff 的起点，
  恢复 CI 时结构 gate 要用到它

### 5. 恢复条件（满足任一条即恢复自动触发）

1. 功能进入 **Phase C**（治理深化与配置回滚），改动开始相互牵连
2. 出现第二个人在同一分支上提交
3. 仓库首次对外公开 / 需要保护分支
4. 出现任何一条「与执行顺序有关」的偶发红 —— 那是本地纪律已经不够用的信号

恢复时要补的三件事：

- `fetch-depth: 0`（结构 gate 要读基线 tag）
- 服务容器提供 Redis 与 PostgreSQL（与本机同版本，测试代码不变）
- 四个 gate 全部设为 required status check

## 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 现在就启用自动触发 | 功能铺开阶段的往返成本高于边际收益；本地 verify 已覆盖同样内容 |
| 删掉 `ci.yml` 等以后重写 | 四个 gate 的定义与防假绿的手段是设计资产；删掉等于以后重新踩一遍「断言空过」 |
| 只留一个 gate（跑全部测试） | 不同类的缺陷要在不同地方被挡住；合成一个后无法判断是哪一类红了 |
| 用覆盖率当 gate | 来源项目证明了覆盖率与真实验证能力几乎不相关 |
| 集成依赖用 Testcontainers 临时起 | 本机已有常驻实例；CI 侧用服务容器即可，测试代码不变 |
| 允许某个 gate 失败时合并 | 门禁的作用就是阻断；可绕过的门禁不是门禁 |

## 后果

**正面**

- 设计与防假绿的手段被冻结保留，恢复时不需要重新踩坑
- 暂缓期间反馈路径只有一条（本地 `verify`），责任清晰

**负面**

- **分支上的「有没有真的跑过测试」在暂缓期间只能靠本地纪律**：这是本 ADR 记录的主要风险，
  不是被忽略的代价
- 恢复时如果间隔太久，`ci.yml` 与当前分组可能已经不同步，需要一次对齐工作
- 结构 gate 依赖基线 tag；暂缓期间若忘记打基线，恢复时该 gate 无从判起

## 如何验证

- [x] `ci.yml` 存在，四个 gate 定义完整，当前仅手动触发
- [x] `scripts/ci/count-ran-tests.py` 能判出「跑了 0 个测试」
- [x] 结构 gate 的差异统计用 `git diff` 而非 `git status`，且 CI 里 `fetch-depth: 0`
- [x] 每个测试类都有 `@Tag`（`EveryTestBelongsToAGateTest`）
- [ ] 恢复条件满足时，四个 gate 全部变为 required status check
- [ ] 恢复后 CI 侧的服务容器提供与本机同版本的 Redis 与 PostgreSQL
