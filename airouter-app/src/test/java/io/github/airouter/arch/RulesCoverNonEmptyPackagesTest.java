package io.github.airouter.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构规则点名的包必须真的有类。
 *
 * <p>规则是按包名写的。包一旦被改名或者搬空，规则不会报错，只会安静地什么都不检查 ——
 * 这正是「门禁还在，但已经不拦任何东西」的形态。这个测试把那种形态变成红色。
 */
class RulesCoverNonEmptyPackagesTest {

  private static final String GATEWAY = StructuralRules.ROOT + ".gateway";

  private static final JavaClasses PRODUCTION = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(GATEWAY);

  private static final List<String> PACKAGES_NAMED_BY_RULES = List.of(
      "faces", "providers", "transport", "routing", "governance", "config");

  @Test
  void everyPackageNamedByALayoutRuleHasClasses() {
    for (String name : PACKAGES_NAMED_BY_RULES) {
      String pattern = GATEWAY + "." + name + "..";
      assertThat(PRODUCTION.stream().anyMatch(resideInAPackage(pattern)))
          .as("规则点名的包里一个类都没有，规则正在空转：%s", pattern)
          .isTrue();
    }
  }
}
