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

**Status:** pending

## Acceptance criteria

- [ ] `config/EndpointConfiguration` 改成与它注册的东西相符的名字；
      `UpstreamConfiguration` 等引用处一并改（**只改名，不改装配行为**）
- [ ] `StreamProperties` 那处 `{@link EndpointProperties}` 改成它现在真正指向的类型
      （或改成不引用的说法）；**全仓再搜一遍**同类悬空引用
- [ ] `SelectionStrategy` 里重复的 javadoc 段只剩一处
- [ ] 两个 `STRATEGY_ID` 字面量消失，改从 `SelectionStrategy` 取；
      **报错措辞不许变** —— `hashStrategiesRejectMissingCallerIdentity` 断言着
      `hasMessageContaining(IpHashSelector.STRATEGY_ID)`，改完必须仍然绿
- [ ] gateway 测试文件回到 **10 个以内**（硬上限 20 本来就满足，这里要的是软上限）；
      合并目标优先选「一个类一个文件」的那几个。app 那 11 个顺带一起看，
      但 app 多出来的那一个是 `arch/TicketReferencesExistTest`（守的是票号引用），
      它**不该**被并进去 —— 若因此回不到 10，在本票里写明这一条并止步
- [ ] 改完 `./mvnw clean verify` 全绿（含 checkstyle / spotbugs / 结构规则 / 覆盖率）

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
