# 01: 构建骨架与门禁装配

**What to build:** 从「没有任何可构建的东西」到「`./mvnw clean verify` 不带任何参数就能跑绿，
并且九条结构规则各有一条样例证明它真的会红」。

这是本阶段的前置重构（make the change easy）：后面五张票都落在这个骨架上。
它自己单独可验证 —— 验收不是「构建绿」，而是「守门真的会咬人」。

**Blocked by:** None

**Status:** done（2026-10-27）

## Acceptance criteria

- [x] 根 pom（packaging=pom）与四模块 pom 就位；Maven Wrapper 入库；JDK 21
- [x] enforcer 卡住 JDK 21、重复上界依赖、插件版本与依赖收敛，且**抑制文件被禁止**
      （实测：写入 `config/checkstyle/suppressions.xml` → `validate` 立刻红）
- [x] checkstyle（error 级）与 spotbugs 的作用域**包含测试源码**；surefire 按
      `unit` / `integration` / `architecture` 分组
      （实测：`-Dgroups=architecture` 跑 28 个、`-Dgroups=unit` 跑 0 个）
- [x] 四模块的业务包占位：`config` / `faces` / `providers` / `transport` / `observe`，
      各带 `package-info.java`
- [x] 九条结构规则按 **R1–R9 编号**落地，编号**不重排**（ADR 与 AGENTS.md 里引用的是编号本身）。
      R1 由 enforcer 判红；其余用 ArchUnit 的库但**不用它的 `@AnalyzeClasses` 引擎**
      —— 改用普通 `@Test` + `@Tag`
- [x] **R2–R5 与 R9 以包根为参数**，使同一组规则既能作用在生产包上、也能作用在测试样例包上。
      （R6 / R7 / R8 以「类谓词」为参数，同理可作用在两侧）
- [x] 两套样例包就位：合规样例包（断言规则**不**误报）与违规样例包（断言规则**会**红），
      外加装配样例包（R6 / R7 / R8）与结算样例包（R9 / R4 的状态码那一半）
- [x] `EveryTestBelongsToAGateTest` 就位：每个测试类必须标 `@Tag`，漏标即红
      （实测：删掉一个 `@Tag` → 该测试类不再进入任何 gate 且本条断言红）
- [x] `./mvnw clean verify` 不带任何 skip 参数即绿（28 个测试）
- [x] 九条规则**各有一条**断言它们真的会红；误删一个 `@Tag` 也会红

## 交付时实测过的「故意违反」

| 动手 | 红的那一处 |
|---|---|
| 写入 `config/checkstyle/suppressions.xml` | enforcer `enforce-gates-have-no-escape-hatch` |
| 给 `core` 加一个 `spring-core` 依赖 | enforcer `core-stays-framework-free`（R1） |
| 让 `gateway.faces` 引用 `gateway.providers` 的一个类 | `ModuleStructureTest.facesDoNotDependOnProviders`（R3a） |
| 删掉一个测试类上的 `@Tag` | `EveryTestBelongsToAGateTest.everyTestClassDeclaresAGate` |
| 制造一行行尾空白 | checkstyle `RegexpSingleline` |

另有一处是**顺带**被验证的：给占位包加一个真实类之后，
`RulesCoverNonEmptyPackagesTest.placeholderPackagesStillHaveNoClasses` 会红，
提示「它已经不是占位包，把它挪进 PACKAGES_WITH_CLASSES」——
这正是 ADR-0003 §2 那个口径的执行者。

## Notes

- **R4 面向的 `routing` / `governance` 生产包不在本阶段开放**（它们属阶段 3 / 7）。
  本阶段 R4 通过结算样例包守门；生产侧检查随包开放接管（在 `ModuleStructureTest` 里留了说明）。
  这是「九条一次落地」与「空包不要提前开」之间的取舍
- 五个业务包在本阶段只有 `package-info.java` —— 它们的存在是为了让规则有位可守
- 本票的测试文件只有守门那五份，不占用后续票的测试文件预算
  （`docs/agents/testing.md` 的硬约束：每模块 ≤10 个，最多 20；每文件 ≤20 个方法；行覆盖率 ≥60%）
- R6 / R7 写成**类级条件**（一个条件里查类型、字段、方法三处）而不是三条子规则，
  原因写在 `StructuralRules` 的注释里：阶段 0 没有字段与方法，拆开会有子规则空转，
  而 ArchUnit 对空转是报错的
- R9 按类型名 `Outcome` 认目标；core 的 `Outcome`（阶段 1）落地后，
  把样例里的 `io.github.airouter.archtest.Outcome` 换成真实类型即可，规则不用改
