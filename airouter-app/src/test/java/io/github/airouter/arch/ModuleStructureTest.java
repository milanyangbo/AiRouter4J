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
 * 那个引擎看不见类上的 {@code @Tag}，而 CI 的四个 gate 正是按 {@code @Tag} 分派 ——
 * 用它的结果是这个类的 9 条规则在结构 gate 里被静默漏掉，报告照样绿。
 * 这里改用与 {@link GuardsActuallyFireTest} 相同的写法：自己导入类，用 {@code @Test} 逐条判。
 * 「为什么不能用那个引擎」由 {@link EveryTestBelongsToAGateTest} 里的一条断言守着。
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

  @Test
  @DisplayName("routing 与 governance 不做 IO：判定不该依赖出网")
  void routingAndGovernanceDoNoIo() {
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
