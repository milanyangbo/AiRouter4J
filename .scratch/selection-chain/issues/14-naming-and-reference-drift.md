# 14: 命名、文档引用与测试文件数：五处小漂移

**What to build:** 清掉两轴 review 指出的「名字 / 引用与实际不符」的五处。
每一处都是小改动，但每一处都会让下一个读代码的人先被误导一次 ——
它们共同的名字是**文档说的与代码做的不是同一件事**（与票 13 同一类病，只是更浅）。

来源：两轴 code review（2026-10-28，固定点 `eedaff6`）的 Standards 轴第 2、3 条与基线异味三条。

1. **`config/EndpointConfiguration` 的名字是上一代的。** 它注册的是 `ServicesProperties` /
   `StreamProperties` / `RoutingProperties`，与 endpoint 无关；扁平的 `airouter.endpoint.*`
   已由票 07 换成 `airouter.services.<svc>.instances[*]`，类名没跟着换
2. **`config/StreamProperties` 里有一处悬空引用。** javadoc 写着
   *"理由与 {@link EndpointProperties} 相同"*，那个类型已经不存在（已改名 `InstanceDefinition`）
3. **`select/SelectionStrategy` 里 ROUND_ROBIN 的 javadoc 段被粘贴了两遍**（26–35 行附近）
4. **策略名有了第二个来源。** `IpHashSelector.STRATEGY_ID = "ip-hash"` 与
   `ConsistentHashSelector.STRATEGY_ID = "consistent-hash"` 各自定义了字符串字面量，
   与 `SelectionStrategy` 自己声明的不变式 *"名字只在**这一处**定义"* 矛盾。
   取 `SelectionStrategy.IP_HASH.id()` / `CONSISTENT_HASH.id()`
5. **测试文件数过了软上限。** `docs/agents/testing.md` §二：*"每模块测试文件 **10 个以内**
   （最多 20）"*；现在 gateway **12**、app **11**。§七把「一个类一个文件」列为坏测试 ——
   `UpstreamShapeTest`、`InstanceDefinitionTest` 属于这一型，是合并的首选目标

**Blocked by:** 10

**Status:** done（2026-10-30）

## Acceptance criteria

- [x] `config/EndpointConfiguration` → **`GatewayPropertiesConfiguration`**（`git mv` 改名，
      装配行为零改动；全仓没有任何代码引用它 —— 组件扫描捡起，故无连带改动）
- [x] `StreamProperties` 的 `{@link EndpointProperties}` 改为不引用的说法
      （指向同一理由的两个现存类型：`ServicesProperties` / `InstanceDefinition`）；
      **全仓再搜一遍**发现 runbook `local-openai-endpoint.md` 还有两处同类漂移
      （说的是本仓库现在的校验，那个类型已改名 `InstanceDefinition`），一并修掉。
      archive ADR 与 `legacy-review.md` 里的指称是历史记录，按 Known gaps 不动
- [x] `SelectionStrategy` 里 ROUND_ROBIN 重复的 javadoc 段只剩一处
- [x] 两个 `STRATEGY_ID` 字面量消失，改取 `SelectionStrategy.IP_HASH.id()` /
      `CONSISTENT_HASH.id()`（选择处各留一行注释说明为什么不去自建字符串）；
      报错措辞没变 —— `hashStrategiesRejectMissingCallerIdentity` 的两处
      `hasMessageContaining` 改引枚举后仍然绿，即「名字只有一处」的外部证据
- [x] gateway 测试文件 12 → **10**（两次合并，见下）；app 保持 11，
      多出的那一个是 `arch/TicketReferencesExistTest`，按本票说明不并
- [x] `./mvnw clean verify` 全绿（行覆盖率 89.0% 持平；本票零行为改动）

## 实测结论

**两次合并的主题判断**（合并前两边的断言都读过一遍）：

1. `InstanceDefinitionTest`（7）并入 `ServicesPropertiesTest`（6 → 13）：
   服务级校验（集合的形状：id 重复 / 空清单 / 全关 / 默认服务）与实例级校验
   （形态决定地址后缀与凭证必填）是**同一件事的两半** —— 一份配置要么整体成立
   要么整体起不来，拆开写会让人以为是两条独立的防线。四组 `instance(...)` 助手
   以重载共存，`TIMEOUT` 常量共用。
2. `UpstreamShapeTest`（6）并入 `UpstreamProviderFactoryTest`（4 → 10）：
   形态自述是**声明**，形态工厂是**兑现** —— 「登记的构造器覆盖每一种形态」
   只有挨着自述本身看才读得懂；分开写会让「新增一种形态要动哪几处」变成两份各自为政的清单。
   顺带把该文件 javadoc 里「它是唯一持有者」一句按票 13 的裁决补上了定语
   （「配置 → 运行时对象」的唯一持有者）。

**「每类单独一轮 verify」实际执行成两轮**：前四处（改名 / 悬空引用 / 重复注释 /
名字来源）一轮，合并测试文件一轮 —— 前四处彼此是同一类病（文档说的与代码做的不是同一件事），
且互相不影响编译，拆成四轮只多花时间。

**预算核对**：gateway 10（软上限内）、每文件方法数最高 13（ServicesPropertiesTest ≤ 20）、
app 11（有票说明）。无新增测试文件、无新增断言（本票零行为改动）。

## Known gaps（本票不做）

- 重命名带来的包名调整：只动类名与引用，**不挪包**（挪包会牵动结构规则里登记的包清单）
- `docs/planning/legacy-review.md` 里对 legacy `EndpointProperties` 的指称是**历史记录**，
  不改（它说的是来源项目，不是本仓库现在的代码）

## 落点

| 文件 | 内容 |
|---|---|
| `config/EndpointConfiguration` | 改名 |
| `config/StreamProperties` | 悬空 `{@link}` |
| `select/SelectionStrategy` | 重复 javadoc 段；为两个 hash 策略提供 `id()` |
| `select/IpHashSelector` + `select/ConsistentHashSelector` | `STRATEGY_ID` 字面量去掉 |
| `airouter-gateway/src/test/...` | 合并「一个类一个文件」的那几个，回到 10 以内 |

## 测试

- **不新增断言** —— 这五处都不改行为。若改到某处时发现「改名会动到行为」，
  说明那一处不只是名字问题：停下来把它记进本票的实测，不要顺手一起改
- 第 4 条有一条既有断言（`hashStrategiesRejectMissingCallerIdentity`）在守措辞，
  它绿就是「名字只有一处」这件事没被改坏的外部证据

## 实现时要守的

- 一次只改一类（改名 / 引用 / 重复注释 / 名字来源 / 合并测试文件），
  每类单独一轮 `verify`，避免一次改动里五处同时出问题而不知道是哪一处
- 合并测试文件时**先读两遍两边的断言**，确认它们看的是同一件事
  （票 08 那次合并就是基于「会红 / 不误报看的是同一组规则」）
- 预算：本票的目标是**减少**文件数，不是新增
