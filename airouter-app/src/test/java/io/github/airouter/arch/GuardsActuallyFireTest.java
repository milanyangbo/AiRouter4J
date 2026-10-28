package io.github.airouter.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反向验证的两个方向，放在**一处**：门禁既要真会红，也不能宽到误伤普通写法。
 *
 * <p>方向一（会红）：只写规则不验证它会红，等于把门禁交给运气 ——
 * 包改名、谓词写错、作用域写宽，都会让一条规则在不知不觉中变成永远通过。
 * 违规样例违反了每一条规则，断言的是「规则确实判出来了」，而不是「规则写在那里」。
 *
 * <p>方向二（不误报）：一条「宁可错杀」的规则，结局一定是被加抑制。
 * 合规样例全部是日常写法 —— 构造函数注入、{@code @Value} 配置字段、{@code @Bean} 方法、
 * {@code @ConditionalOnProperty}、faces 只用 core、config 装配所有人 ——
 * 每一条规则在它们上面都必须是绿的。
 *
 * <p>两个方向合在一个文件里，是因为它们看的是**同一组规则**：
 * 分开写会让「规则改了、只改了一边」变成一件没人会发现的事。
 */
@Tag("architecture")
class GuardsActuallyFireTest {

  private static final String LAYOUT_ROOT = "io.github.airouter.archtest.layout.violations";
  private static final String WIRING_ROOT = "io.github.airouter.archtest.wiring.violations";
  private static final String SETTLEMENT_ROOT = "io.github.airouter.archtest.settlement.violations";

  private static final String ORDINARY_LAYOUT_ROOT =
      "io.github.airouter.archtest.layout.ordinary";
  private static final String ORDINARY_WIRING_ROOT =
      "io.github.airouter.archtest.wiring.ordinary";

  private static final JavaClasses LAYOUT =
      new ClassFileImporter().importPackages(LAYOUT_ROOT);
  private static final JavaClasses WIRING =
      new ClassFileImporter().importPackages(WIRING_ROOT);
  private static final JavaClasses SETTLEMENT =
      new ClassFileImporter().importPackages(SETTLEMENT_ROOT);
  private static final JavaClasses ORDINARY_LAYOUT =
      new ClassFileImporter().importPackages(ORDINARY_LAYOUT_ROOT);
  private static final JavaClasses ORDINARY_WIRING =
      new ClassFileImporter().importPackages(ORDINARY_WIRING_ROOT);

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

  /**
   * R1 是九条里唯一由 Maven enforcer 判红的：它的判据是依赖树，而依赖树只在构建里存在，
   * 单测里复现一次真实构建既慢又不诚实。这里断言的是那条禁令**确实写在 core 的 pom 里** ——
   * 少了 `searchTransitive` 或漏掉 Jackson 3 的新坐标，R1 失效的方式都是静默的：
   * 什么都不报，而 core 从此可以带一个框架而不被任何人发现。
   */
  @Test
  @DisplayName("R1 的禁令写在 core 的 pom 里：查传递依赖，且三个坐标族都在")
  void coreFrameworkBanIsDeclaredInItsPom() throws IOException {
    String pom = Files.readString(repositoryRoot().resolve("airouter-core/pom.xml"));

    assertThat(pom).contains("bannedDependencies");
    assertThat(pom).contains("<searchTransitive>true</searchTransitive>");
    assertThat(pom).contains("org.springframework*");
    assertThat(pom).contains("com.fasterxml.jackson*");
    assertThat(pom).contains("tools.jackson*");
    assertThat(pom).contains("io.projectreactor*");
  }

  /**
   * 普通写法**不**被判红。
   *
   * <p>这一半与上面那一条同样重要：一条会误伤日常写法的规则，
   * 第一次误报之后就会被加抑制，而抑制一旦开了头，真正的违规也就一起被放过去了。
   */
  @Test
  @DisplayName("普通的 Spring 装配方式不被任何一条规则误伤")
  void ordinarySpringWiringIsNotFlagged() {
    assertPasses(StructuralRules.noLazyWiring(resideInAPackage(ORDINARY_WIRING_ROOT + "..")),
        ORDINARY_WIRING);
    assertPasses(
        StructuralRules.noFieldOrSetterInjection(resideInAPackage(ORDINARY_WIRING_ROOT + "..")),
        ORDINARY_WIRING);
    assertPasses(StructuralRules.noBeanLookupByType(resideInAPackage(ORDINARY_WIRING_ROOT + "..")),
        ORDINARY_WIRING);
  }

  /** 合规的包布局不被规则误伤：faces 只用 core、config 装配所有人，都是正常的事。 */
  @Test
  @DisplayName("合规的包布局不被任何一条规则误伤")
  void ordinaryGatewayLayoutIsNotFlagged() {
    assertPasses(StructuralRules.facesDoNotDependOnProviders(ORDINARY_LAYOUT_ROOT), ORDINARY_LAYOUT);
    assertPasses(StructuralRules.providersDoNotDependOnFaces(ORDINARY_LAYOUT_ROOT), ORDINARY_LAYOUT);
    assertPasses(StructuralRules.routingAndGovernanceDoNoIo(ORDINARY_LAYOUT_ROOT), ORDINARY_LAYOUT);
    assertPasses(StructuralRules.transportKnowsNoBusiness(ORDINARY_LAYOUT_ROOT), ORDINARY_LAYOUT);
    assertPasses(StructuralRules.nothingDependsOnApp(ORDINARY_LAYOUT_ROOT), ORDINARY_LAYOUT);
    assertPasses(StructuralRules.settlementHasOneExit(ORDINARY_LAYOUT_ROOT), ORDINARY_LAYOUT);
  }

  private static void assertFires(ArchRule rule, JavaClasses classes) {
    assertThat(rule.evaluate(classes).hasViolation())
        .as("规则应当判红，但没有：%s", rule.getDescription())
        .isTrue();
  }

  private static void assertPasses(ArchRule rule, JavaClasses classes) {
    assertThat(rule.evaluate(classes).hasViolation())
        .as("普通写法被误伤了：%s%n%s", rule.getDescription(),
            String.join("\n", rule.evaluate(classes).getFailureReport().getDetails()))
        .isFalse();
  }

  /** 从当前工作目录向上找，直到某个父目录里有 {@code .git} —— 那才是仓库根。 */
  private static Path repositoryRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve(".git"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("应当能从工作目录向上找到仓库根（含 .git 的那一层）").isNotNull();
    return dir;
  }
}
