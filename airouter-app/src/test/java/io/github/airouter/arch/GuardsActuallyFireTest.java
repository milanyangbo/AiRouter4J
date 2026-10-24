package io.github.airouter.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反向验证：每一条结构规则都必须真的会红。
 *
 * <p>只写规则不验证它会红，等于把门禁交给运气 —— 包改名、谓词写错、作用域写宽，
 * 都会让一条规则在不知不觉中变成永远通过。这里的样例违反了每一条规则，
 * 断言的是「规则确实判出来了」，而不是「规则写在那里」。
 */
class GuardsActuallyFireTest {

  private static final String LAYOUT_ROOT = "io.github.airouter.archtest.layout.violations";
  private static final String WIRING_ROOT = "io.github.airouter.archtest.wiring.violations";
  private static final String SETTLEMENT_ROOT = "io.github.airouter.archtest.settlement.violations";

  private static final JavaClasses LAYOUT =
      new ClassFileImporter().importPackages(LAYOUT_ROOT);
  private static final JavaClasses WIRING =
      new ClassFileImporter().importPackages(WIRING_ROOT);
  private static final JavaClasses SETTLEMENT =
      new ClassFileImporter().importPackages(SETTLEMENT_ROOT);

  @Test
  void facesDependingOnProvidersIsRejected() {
    assertFires(StructuralRules.facesDoNotDependOnProviders(LAYOUT_ROOT), LAYOUT);
  }

  @Test
  void providersDependingOnFacesIsRejected() {
    assertFires(StructuralRules.providersDoNotDependOnFaces(LAYOUT_ROOT), LAYOUT);
  }

  @Test
  void governanceDoingIoIsRejected() {
    assertFires(StructuralRules.routingAndGovernanceDoNoIo(LAYOUT_ROOT), LAYOUT);
  }

  @Test
  void transportDoingRoutingIsRejected() {
    assertFires(StructuralRules.transportKnowsNoBusiness(LAYOUT_ROOT), LAYOUT);
  }

  @Test
  void coreDependingOnAppIsRejected() {
    assertFires(StructuralRules.nothingDependsOnApp(LAYOUT_ROOT), LAYOUT);
  }

  @Test
  void lazyWiringIsRejected() {
    assertFires(StructuralRules.noLazyWiring(resideInAPackage(WIRING_ROOT + "..")), WIRING);
  }

  @Test
  void fieldInjectionIsRejected() {
    assertFires(StructuralRules.noFieldOrSetterInjection(resideInAPackage(WIRING_ROOT + "..")), WIRING);
  }

  @Test
  void beanLookupByTypeIsRejected() {
    assertFires(StructuralRules.noBeanLookupByType(resideInAPackage(WIRING_ROOT + "..")), WIRING);
  }

  @Test
  void governanceReadingHttpStatusToJudgeSuccessIsRejected() {
    assertFires(StructuralRules.routingAndGovernanceDoNoIo(SETTLEMENT_ROOT), SETTLEMENT);
  }

  @Test
  void outcomeBuiltOutsideTheTerminalProducerIsRejected() {
    assertFires(StructuralRules.settlementHasOneExit(SETTLEMENT_ROOT), SETTLEMENT);
  }

  private static void assertFires(ArchRule rule, JavaClasses classes) {
    assertThat(rule.evaluate(classes).hasViolation())
        .as("规则应当判红，但没有：%s", rule.getDescription())
        .isTrue();
  }
}
