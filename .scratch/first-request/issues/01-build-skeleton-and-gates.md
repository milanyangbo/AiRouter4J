# 01: 构建骨架与门禁装配

**What to build:** 从「没有任何可构建的东西」到「`./mvnw clean verify` 不带任何参数就能跑绿，
并且九条结构规则各有一条样例证明它真的会红」。

这是本阶段的前置重构（make the change easy）：后面五张票都落在这个骨架上。
它自己单独可验证 —— 验收不是「构建绿」，而是「守门真的会咬人」。

**Blocked by:** None（可立即开始）

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 根 pom（packaging=pom）与四模块 pom 就位；Maven Wrapper 入库；JDK 21
- [ ] enforcer 卡住 JDK 21、重复上界依赖、插件版本与依赖收敛，且**抑制文件被禁止**
      （来源项目那份 186 行抑制文件是「加个例外算了」那条路的终点）
- [ ] checkstyle（error 级）与 spotbugs 的作用域**包含测试源码**；surefire 按
      `unit` / `integration` / `architecture` 分组
- [ ] 四模块的业务包占位：`config` / `faces` / `providers` / `transport` / `observe`，
      各带 `package-info.java`
- [ ] 九条结构规则按 **R1–R9 编号**落地，编号**不重排**（ADR 与 AGENTS.md 里引用的是编号本身）。
      R1 由 enforcer 判红；其余用 ArchUnit 的库但**不用它的 `@AnalyzeClasses` 引擎**
      （它看不见类上的 `@Tag`，按 gate 筛选时整个类会被静默漏掉）—— 改用普通 `@Test` + `@Tag`
- [ ] **R3–R5 与 R9 以包根为参数**，使同一组规则既能作用在生产包上、也能作用在测试样例包上。
      这是「规则真的会红」这件事本身被断言过的机制，不是靠人记得去试一次
- [ ] 两套样例包就位（形状来自上一轮实现，见 tag `baseline-pre-11`）：
      合规样例包（断言规则**不**误报）与违规样例包（断言规则**会**红），
      外加装配样例包（R6 / R7 / R8）与结算样例包（R9）
- [ ] `EveryTestBelongsToAGateTest` 就位：每个测试类必须标 `@Tag`，漏标即红
- [ ] `./mvnw clean verify` 不带任何 skip 参数即绿
- [ ] 九条规则**各有一条**断言它们真的会红；误删一个 `@Tag` 也会红

## Notes

- **R4 面向的 `routing` / `governance` 生产包不在本阶段开放**（它们属阶段 3 / 7）。
  本阶段 R4 通过结算样例包守门；生产侧检查由后续阶段接管。这是「九条一次落地」与
  「空包不要提前开」之间的取舍：规则与守门在阶段 0 全部就位，生产包按需开放
- 五个业务包在本阶段只有 `package-info.java` —— 它们的存在是为了让规则有位可守。
  ADR-0003 §2 的「空包不要提前开」按此口径修订
- 本票的测试文件只有守门那几份，不占用后续票的测试文件预算
  （`docs/agents/testing.md` 的硬约束：每模块 ≤10 个，最多 20；每文件 ≤20 个方法；行覆盖率 ≥60%）
