package io.github.airouter.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 真实模块上的结构约束。
 *
 * <p>跑在默认 {@code verify} 路径上（surefire 的 test 阶段），不在 profile 里，不需要手工执行任何 goal。
 * 这里只声明规则，规则的定义与理由在 {@link StructuralRules}。
 */
@AnalyzeClasses(packages = StructuralRules.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
final class ModuleStructureTest {

  private static final String ROOT = StructuralRules.ROOT;
  private static final String GATEWAY = ROOT + ".gateway";

  private ModuleStructureTest() {
  }

  @ArchTest
  static final ArchRule nothing_depends_on_app = StructuralRules.nothingDependsOnApp(ROOT);

  @ArchTest
  static final ArchRule faces_do_not_depend_on_providers =
      StructuralRules.facesDoNotDependOnProviders(GATEWAY);

  @ArchTest
  static final ArchRule providers_do_not_depend_on_faces =
      StructuralRules.providersDoNotDependOnFaces(GATEWAY);

  @ArchTest
  static final ArchRule routing_and_governance_do_no_io =
      StructuralRules.routingAndGovernanceDoNoIo(GATEWAY);

  @ArchTest
  static final ArchRule transport_knows_no_business =
      StructuralRules.transportKnowsNoBusiness(GATEWAY);

  @ArchTest
  static final ArchRule no_lazy_wiring =
      StructuralRules.noLazyWiring(StructuralRules.productionClasses());

  @ArchTest
  static final ArchRule no_field_or_setter_injection =
      StructuralRules.noFieldOrSetterInjection(StructuralRules.productionClasses());

  @ArchTest
  static final ArchRule no_bean_lookup_by_type =
      StructuralRules.noBeanLookupByType(StructuralRules.productionClasses());
}
