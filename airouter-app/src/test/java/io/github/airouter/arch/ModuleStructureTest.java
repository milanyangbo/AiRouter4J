package io.github.airouter.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 真实模块上的结构约束。
 *
 * <p>跑在默认 {@code verify} 路径上（surefire 的 test 阶段），不在 profile 里，不需要手工执行任何 goal。
 * 这里只声明规则，规则的定义与理由在 {@link StructuralRules}。
 *
 * <p>刻意**不用** ArchUnit 自带的引擎（{@code @AnalyzeClasses} + {@code @ArchTest}）：
 * 那个引擎看不见类上的 {@code @Tag}，而三道 gate 正是按 {@code @Tag} 分派 ——
 * 用它的结果是这个类的九条规则在结构 gate 里被静默漏掉，报告照样绿。
 * 这里改用与 {@link GuardsActuallyFireTest} 相同的写法：自己导入类，用 {@code @Test} 逐条判。
 * 「为什么不能用那个引擎」由 {@link EveryTestBelongsToAGateTest} 里的一条断言守着。
 *
 * <p>阶段 0 的生产包大多是占位包（只有 package-info.java），因此这几条在这里判的是很薄的一层 ——
 * 它们「真的会红」由 {@link GuardsActuallyFireTest} 在样例包上断言，
 * 生产侧的内容随各阶段开放（ADR-0003 第 2 节）。
 *
 * <p>R4 面向的 `routing`（阶段 4）与 `governance`（阶段 7）两个包曾长期不存在，
 * 而 ArchUnit 对「一条规则没有判定对象」是报错而不是放过，
 * 所以那时没有为它留一个空跑的方法。`routing` 包一开放，生产侧断言就补在了
 * {@link #routingDoesNoIo()} —— 在那之前它由 GuardsActuallyFireTest 的两条
 * （做 IO、读状态码）守着。`governance` 开放时按同样的写法再补一条。
 */
@Tag("architecture")
final class ModuleStructureTest {

  private static final String ROOT = StructuralRules.ROOT;

  private static final String GATEWAY = ROOT + ".gateway";

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(ROOT);

  private ModuleStructureTest() {
  }

  @Test
  @DisplayName("没有任何模块依赖 app：它是装配终点")
  void nothingDependsOnApp() {
    StructuralRules.nothingDependsOnApp(ROOT).check(CLASSES);
  }

  @Test
  @DisplayName("faces 不依赖 providers：入站翻译不该认识具体上游")
  void facesDoNotDependOnProviders() {
    StructuralRules.facesDoNotDependOnProviders(GATEWAY).check(CLASSES);
  }

  @Test
  @DisplayName("providers 不依赖 faces：出方向不该认识入站协议")
  void providersDoNotDependOnFaces() {
    StructuralRules.providersDoNotDependOnFaces(GATEWAY).check(CLASSES);
  }

  /**
   * R4 —— 选择链是纯函数：它不碰协议、不做 IO，也**看不到 HTTP 状态码**。
   *
   * <p>这条断言随 `routing` 包开放（阶段 4）而生效。它守的是 ADR-0011 第 4 节那一行：
   * 「策略可确定性断言（不起端点）」。策略一旦能看见传输层或状态码，
   * 它就会开始自己判断成败，而那时「熔断说失败、指标说成功」只是时间问题。
   */
  @Test
  @DisplayName("routing 不做 IO 也不读状态码：选择只操作候选与上下文")
  void routingDoesNoIo() {
    StructuralRules.routingAndGovernanceDoNoIo(GATEWAY).check(CLASSES);
  }

  @Test
  @DisplayName("transport 不认识业务：分帧只认字节")
  void transportKnowsNoBusiness() {
    StructuralRules.transportKnowsNoBusiness(GATEWAY).check(CLASSES);
  }

  @Test
  @DisplayName("没有惰性装配：@Lazy 会让装配期问题推迟到运行期")
  void noLazyWiring() {
    StructuralRules.noLazyWiring(StructuralRules.productionClasses()).check(CLASSES);
  }

  @Test
  @DisplayName("没有字段注入与 setter 注入：依赖必须构造时给定")
  void noFieldOrSetterInjection() {
    StructuralRules.noFieldOrSetterInjection(StructuralRules.productionClasses()).check(CLASSES);
  }

  @Test
  @DisplayName("没有按类型查 bean：那是在运行期重新做装配决策")
  void noBeanLookupByType() {
    StructuralRules.noBeanLookupByType(StructuralRules.productionClasses()).check(CLASSES);
  }

  @Test
  @DisplayName("终态只有一个出口：只有产生终态的那一处能构造 Outcome")
  void settlementHasOneExit() {
    StructuralRules.settlementHasOneExit(GATEWAY).check(CLASSES);
  }
}
