package io.github.airouter.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反向验证的另一半：门禁不能宽到让普通写法也红。
 *
 * <p>一条「宁可错杀」的规则，结局一定是被加抑制。这里的样例全部是日常写法 ——
 * 构造函数注入、{@code @Value} 配置字段、{@code @Bean} 方法、{@code @ConditionalOnProperty}、
 * faces 只用 core、config 装配所有人 —— 每一条规则在它们上面都必须是绿的。
 */
class OrdinarySpringUsageIsNotFlaggedTest {

  private static final String LAYOUT_ROOT = "io.github.airouter.archtest.layout.ordinary";
  private static final String WIRING_ROOT = "io.github.airouter.archtest.wiring.ordinary";

  private static final JavaClasses LAYOUT =
      new ClassFileImporter().importPackages(LAYOUT_ROOT);
  private static final JavaClasses WIRING =
      new ClassFileImporter().importPackages(WIRING_ROOT);

  @Test
  void ordinarySpringWiringIsNotFlagged() {
    assertPasses(StructuralRules.noLazyWiring(resideInAPackage(WIRING_ROOT + "..")), WIRING);
    assertPasses(StructuralRules.noFieldOrSetterInjection(resideInAPackage(WIRING_ROOT + "..")), WIRING);
    assertPasses(StructuralRules.noBeanLookupByType(resideInAPackage(WIRING_ROOT + "..")), WIRING);
  }

  @Test
  void ordinaryGatewayLayoutIsNotFlagged() {
    assertPasses(StructuralRules.facesDoNotDependOnProviders(LAYOUT_ROOT), LAYOUT);
    assertPasses(StructuralRules.providersDoNotDependOnFaces(LAYOUT_ROOT), LAYOUT);
    assertPasses(StructuralRules.routingAndGovernanceDoNoIo(LAYOUT_ROOT), LAYOUT);
    assertPasses(StructuralRules.transportKnowsNoBusiness(LAYOUT_ROOT), LAYOUT);
    assertPasses(StructuralRules.nothingDependsOnApp(LAYOUT_ROOT), LAYOUT);
    assertPasses(StructuralRules.settlementHasOneExit(LAYOUT_ROOT), LAYOUT);
  }

  private static void assertPasses(ArchRule rule, JavaClasses classes) {
    assertThat(rule.evaluate(classes).hasViolation())
        .as("普通写法被误伤了：%s%n%s", rule.getDescription(),
            String.join("\n", rule.evaluate(classes).getFailureReport().getDetails()))
        .isFalse();
  }
}
