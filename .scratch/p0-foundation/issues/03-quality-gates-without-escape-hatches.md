# 03: 质量门禁：风格检查与静态分析，无开关无抑制

> 来源：[spec.md](../spec.md) — 用户故事 10、11

**What to build:** 风格检查与静态分析在默认构建路径上真实生效，并且没有任何逃生舱 —— 想让它们闭嘴，唯一合法的手段是改代码。

来源项目正是在这两点上失效的：dev/prod profile 把所有 skip 设成了 true，叠加一份对全部 Java 文件生效的 186 行抑制文件，于是门禁存在但没有一个规则和实际代码相遇过。本票的目标不是规则多，是规则真实。

**Blocked by:** 02 四个模块骨架与依赖方向闸门

**Status:** ready-for-human

- [x] 两项检查都挂在默认生命周期（`verify`）上，不需要执行额外的手工 goal
- [x] 故意引入一处违规（例如未使用的导入、缺失的大括号）导致构建失败，失败信息指向具体位置
- [x] 构建文件中不存在任何为这两项检查声明的 skip 属性
- [x] 不存在抑制文件，检查配置里也不存在任何过滤块或排除块
- [x] 规则集合刻意克制，每条规则在配置附近有一行说明它为什么要存在
- [x] 对静态分析报出的告警，处置路径是修代码；若某条确实无法避免，处置路径是写一份 ADR，而不是在配置里加一行例外
- [x] 在本票结束时，`mvn clean verify` 在没有任何 skip 参数的情况下全绿

## 落地内容

### 规则集：`config/checkstyle/checkstyle.xml`

14 条，每条旁边写了它为什么存在：行长上限、禁 tab、文件末尾换行、行尾空白、包必须有 package-info、未使用/冗余/内部包导入、NeedBraces、空 catch、空块、MissingSwitchDefault、FallThrough、EqualsHashCode、OneTopLevelClass、ArrayTypeStyle / UpperEll / ModifierOrder / FinalClass / HideUtilityClassConstructor。

选规则的标准只有一个：**这条规则不成立时会带来哪一类真实缺陷**。凑数规则不进这份文件 —— 规则越多，越容易走到「加个例外算了」那条路上。

### 分析范围：含测试源码

`includeTestSourceDirectory` 与 `includeTests` 都显式打开。两者默认都只看 `src/main`，这一步不是免费的：

| 打开之后暴露出来的 | 处置 |
|---|---|
| 18 个测试包缺 package-info | 逐个补上，每个包写清它在安全网里的角色 |
| `StructuralRules` 第 24 行 140 字符 | 拆行 |
| `ModuleStructureTest` 有默认构造器且非 final | 补私有构造器并改为 final |

结构性规则依赖一批「故意违规」的样例类（票 02 的自证），这些类现在也要遵守同样的风格规则。**留一批代码天生不在门禁范围内，就是抑制文件的雏形** —— 这是这一步真正要挡的东西。

该决定已写进 [ADR-0008 第 1 节](../../../docs/adr/0008-verification-and-quality-gates.md)。

### 「不存在抑制文件」从文档变成构建断言

enforcer 的 `requireFilesDontExist` 在 `validate` 阶段判 `config/checkstyle/suppressions.xml` 与 `config/spotbugs/exclude.xml` 两个路径是否存在。写在 README 里等自觉遵守，和来源项目那份 commit 了 workflow 却根本没有 CI 的 README 是同一种落差。

## 反向验证（四个方向，全部真跑过）

| 注入的违约 | 结果 |
|---|---|
| `airouter-gateway/.../routing/TempStyleViolation.java` 放未使用导入与单行 if | 构建失败，报出文件名 + 行号 + 列号 + 规则名（`UnusedImports` / `NeedBraces`） |
| 主源码里 `String.getBytes()` | SpotBugs `DM_DEFAULT_ENCODING`，指明类、方法、行号 |
| **测试源码**里 `String.getBytes()` | 同样判红 —— 证明 `includeTests` 是真生效，不是名义上的 |
| 临时放一个 `config/checkstyle/suppressions.xml` | `validate` 阶段失败，把「改代码或写 ADR」的处置路径直接打在报错信息里 |

四项验证用的文件均已删除，之后 `clean verify` 复跑为绿（20 个测试）。

## 取舍与已知边界

- **`banDynamicVersions` 对本仓库自身 SNAPSHOT 开 `ignores`**（票 01 已有）—— 那是构建可重复性的边界，与本票的门禁主题不同源，不重复讨论。
- **jacoco 未接入**：没有任何故事要求覆盖率下限，在没有生产代码的阶段加一个覆盖率阈值是空数字。版本号也不留在配置里占位 —— 未承接的任务不进配置文件。
- **checkstyle 用的是自带/sun 检查的最小集合**，没有引入 Google/Sun 的完整规则包。完整规则包在本项目的收益是一次性的（第一天几千条报错），代价是长期要为「为什么我们不遵守这条」做解释。
