# 08: 六步链骨架、加权展开与接线

**What to build:** 一次请求开始**真的有「选择」这一步**。现在 `UpstreamConfiguration.upstreamProvider`
取的是「默认服务的第一个启用着的实例」而且装配期就固定下来 —— 票 07 加进配置模型的
**权重 / 标签 / 启用标志因此全是有配置、无消费者的字段**。

本票落地六步链的骨架与它的接线（[spec](../spec.md) 的 Solution 一节）：

- `routing` 包负责 ①②③（调用方标识 / 目标与标签 / 可用性闸门）与拒绝原因
- `select` 包负责 ④⑥（加权展开 / 策略），纯函数
- 链的输出是 **instanceId**，不是那个能出网的对象：由装配层按 id 从注册表取回。
  于是判「选得对不对」不需要先造出一个能出网的东西
- 接线：**一个** `RoutedUpstream` 取代 `upstreamProvider` bean，它同时是两个端口，
  而**两个端口调同一个解析入口** —— 「流式绕过选择」正是要在这里被堵死，
  形状上就不给它第二条路

六步的唯一定义处在 [ADR-0011](../../../docs/adr/0011-instance-selection-and-load-balancing.md)
第 5 节。**出网（`transport`）与失败转移（ADR-0013 / 阶段 7）不在六步里。**

> 编号说明：票号是**全仓库一份序列**（`.scratch/<slug>/` 只按功能分目录，编号不回 01），
> 见 `docs/agents/issue-tracker.md`。`08` 与 2026-10-27 被回退的那两个从未有票文件的号无关。

**Blocked by:** 07

**Status:** done

## Acceptance criteria

- [x] `routing` 与 `select` 两个生产包落地，各带 `package-info.java`
      （写清它知道什么、不该知道什么；`select` 那一份要写「纯函数，不做 IO、不读健康」）
- [x] `SelectionChain` 六步落在**一处**：① 调用方标识 → ② 目标与标签 → ③ 可用性闸门 →
      ④ 加权展开 → ⑤ 粘滞（本票直通）→ ⑥ 策略
- [x] 候选是 `Candidate`（id / 权重 / 标签），**不是** `RegisteredInstance`
- [x] `SelectionContext` 含 `callerId`（可空）与 `requiredTags`；**不含「是否流式」** ——
      不给分岔留入口
- [x] ④ 是独立一步，不在策略里：权重之和是区间总长，取值一次二分查找
      （`Arrays.binarySearch` 命中上界要取 `found + 1`：区间不含上界。这一处曾经写错过）
- [x] 本票只落地两个策略：`random`（随机源**从外面注入**）与 `round-robin`（进程级共享光标）。
      装配期默认**轮询**而不是随机 —— 轮询可预测，于是权重能被严格断言
- [x] 三种拒绝各有措辞与断言：服务没有启用着的实例 / 标签无匹配 / 闸门排空。
      **三种都拒绝**：不退回不筛、不退回全部候选、不静默挑第一个
- [x] 「选不出实例」**不出网、也不结算**（ADR-0013 (e)），由 Face 在边界上收口成一个错误
- [x] `RoutedUpstream` 取代 `upstreamProvider` bean：只声明**一个** bean 而它自己是两个端口
      （容器按运行期类型匹配候选，拆成两个会让注入任何一侧都匹配到两个候选 —— 票 02 踩过），
      两个端口调**同一个**解析入口，**每次请求解析一次**
- [x] 「启用」由装配层筛，不在链里：链不知道实例有没有被关掉，那是配置的事
- [x] 选中的实例 id 进入 `Outcome.instanceId`（后面所有作用域类能力的挂点）
- [x] 结构：`routing` / `select` 登记进 `RulesCoverNonEmptyPackagesTest`；
      R4 的**生产侧**断言随 `routing` 开放接管（ADR-0003 第 2 节已写明这条交接）

## Known gaps（本票不做，记下来免得假装做了）

- 其余四个策略（`least-connections` / `ip-hash` / `consistent-hash` / `latency`）、
  粘滞包装器、按名取策略的工厂与 `airouter.routing.strategy` 配置 → 票 09 / 票 10
- **第 ③ 步本票恒开**：熔断与限流属阶段 7（ADR-0013），本票只留接口。
  它排空时「拒绝」的行为要被断言 —— 那时才有真正会排空它的东西
- **第 ① 步只取不校验**：来源 = 显式请求头 → 远端地址 → 空。认证与租户属阶段 6（ADR-0018），
  接上后来源换成认证结果，链不变。`callerId` 缺失时粘滞与两个 hash 策略的行为在票 09 被断言
- **热重载尚未落地**（ADR-0010 第 5 节，属阶段 3）：本票「每次请求解析」是为它留口，
  替换注册表不在本票
- 一致性哈希**不叠加** ④ 的区间（叠加等于双重加权）→ 票 10 处理，
  代价是「换任意策略权重行为一致」只对另外五个策略成立

## 落点

| 文件 | 内容 |
|---|---|
| `routing/package-info.java` + `SelectionChain` + `SelectionContext` + `NoUsableInstanceException` | ①②③ 编排与拒绝原因 |
| `select/package-info.java` + `Candidate` + `WeightedCandidates` + `InstanceSelector` + `RandomSelector` + `RoundRobinSelector` | ④⑥，纯函数 |
| `config/RoutedUpstream` | 两个端口调同一个解析入口，每次请求解析 |
| `config/UpstreamConfiguration` | 装配：链、默认策略（轮询）、随机源、光标 |
| `airouter-app` 契约测试 | 端到端：三个录制器当三个实例（接缝二） |
| `airouter-app` 结构测试 | `routing` / `select` 登记进「规则已覆盖」；R4 生产侧断言 |

实际落地的文件（与表里的差别见「实测」第 1、5 条）：

- `routing/`：新增 `RoutedPort`（表外，因 callerId 无处安放）
- `config/RoutedUpstream` 存的是 `Function<String, ServiceView>`，不是 `UpstreamRegistry`

删掉：`UpstreamConfiguration.upstreamProvider` bean 与它的 `defaultProvider` 私有方法。

## 测试（两条接缝，已与用户确认）

1. **链 / 策略入口**（`airouter-gateway` 单测，`@Tag("unit")`）—— 主接缝。
   构造候选 + `SelectionContext` + 注入的随机源 → 断言选中的 id。**不起容器、不起端点。**
   覆盖：六步顺序、加权（含二分上界）、两个策略、三种拒绝
2. **HTTP 入口**（`airouter-app` 集成，`@Tag("integration")`）—— 两个**录制器**当两个实例。
   **只验「链真接到进程上了」**：流式与非流式走同一条链、关掉的实例不被打到、
   权重在真实请求上生效（3:1 → 八次里 6:2）。**不把单测重抄一遍**（testing.md 六·3）

轮询的断言必须写成**与光标起点无关**：光标是进程级共享状态，
「第一次一定打给第一个实例」是一条与执行顺序有关的断言。1:1 看交替，3:1 取八次 = 两整轮。

## 实现时要守的

- 每条新断言**故意破坏一次、确认会红**，再恢复（ADR-0008）
- **探红恢复不用 `git checkout`** —— 它会把未提交的改动一起退回 HEAD。先 `cp` 备份到 `/tmp`，跑完 `cp` 回来
- 预算（`docs/agents/testing.md`）：每模块 ≤10 个测试文件（最多 20）、每文件 ≤20 个方法、
  行覆盖率 ≥60%（全项目一个数）。`airouter-app` 现在 **11 个**（已顶破软上限一个），
  新增端到端那一份**先找合并目标**
- 上一轮被回退的实现（`9f9048f` / tag `baseline-8`）留下可直接复用的实测结论，
  见 `.workbuddy/memory/MEMORY.md`「已回退的两块」：加权二分的上界、轮询光标是进程级共享状态、
  `RoutedUpstream` 存注册表会撞 SpotBugs `EI_EXPOSE_REP2`（收窄成 JDK 的
  `Function<String, ServiceView>` 即可）、两个录制器的写法

---

## 实测出来的几件事（2026-10-28，实现票 08 时记）

**1. callerId 交不出去 —— 这是本票唯一一处偏离既有约定。**

`AGENTS.md` 写着「Face 侧只认识 core 的两个端口接口」，而 core 那两个端口的签名是
`complete(CanonicalRequest)`。第 ① 步的产物没有合法去处。剩下的选项只有线程局部量，
而流式路径上线程是会换的 —— 写进去的那一次与读出来的那一次未必在同一个线程上，
于是粘滞会静默失效。

取法是新增 `routing.RoutedPort`：**继承** core 两个端口，外加带 `SelectionContext` 的两个入口。
Face 注入它，两个端口字段取自**同一个对象**，于是「非流式走这个、流式走那个」在形状上写不出来。
偏离已就地写进 `AGENTS.md`（「实例维度与治理顺序」一节）与 `RoutedPort` 的 javadoc。

**2. 权重溢出在 int 权重下不可达。**

第一版在 `WeightedCandidates.of` 里用 `Math.addExact` 兜溢出，并写了一条断言。
探红时那条**不红** —— 因为权重是 `int`（上限约 21 亿），前缀和是 `long`，
要约 43 亿个实例才可能溢出，那个数量级下先出问题的一定不是这一行。
于是删掉 catch，把「这条性质是怎么被满足的」写进 javadoc，断言改成可达的
「两个 int 上限相加仍然是准的那个数」。

**3. 轮询的断言必须写成与光标起点无关。**

光标是 bean 的字段，也就是进程级共享状态，起点随执行顺序而变。
1:1 看相邻两次不同，3:1 取八次（= 两整轮）看计数恰好 6:2 且序列周期为 4。
「第一次一定打给第一个实例」换个执行顺序就红。

**4. SpotBugs 的 `EI_EXPOSE_REP` 与 `EI_EXPOSE_REP2` 是两件事。**

前者针对 **accessor 返回**可变类型（record 的 `requiredTags()` 也照样报），
后者针对**构造器把可变参数存进字段**。后者只要在 compact constructor 里 `copyOf` 就不报，
前者要在 accessor 里再 `copyOf` 一次。按 ADR-0008 第 7 节一律改代码，没有加抑制。

**5. 测试文件预算：先腾名额，再新增。**

app 原本 11 个（已顶破软上限一个）。新增端到端那份会到 12，
于是先把 arch 侧成对的两份门禁测试合到一处（「规则真的会红」与「普通写法不被误伤」看的是同一组规则，
分开写会让「规则改了、只改了一边」没人发现）。合并后 10，加回端到端那份是 11。

**6. 端到端那条断言真的抓得住「流式绕过选择」。**

故意让 `stream(...)` 直接取第一个启用着的实例（不走链），八次交替请求里 heavy 的占比偏离 6/8。
单测看不见这种错 —— 它判的是链，不是谁在调链。这一条是它存在的理由。
