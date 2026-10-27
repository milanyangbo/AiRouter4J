package io.github.airouter.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构规则点名的包必须真的有类 —— 或者被明确记为占位。
 *
 * <p>规则是按包名写的。包一旦被改名或者搬空，规则不会报错，只会安静地什么都不检查 ——
 * 这正是「门禁还在，但已经不拦任何东西」的形态。这个测试把那种形态变成红色。
 *
 * <p>阶段 0 的两个名单因此是**手工维护**的，而它的自洽性由第一条断言守着：
 * 新出现的包必须被登记进其中一个名单，否则红。而一个包一旦有了真实类，
 * 它就不再符合「占位」的定义（第二条断言），必须从占位名单里挪走 ——
 * 挪走的那一天，正是这条规则开始真正生效的那一天（ADR-0003 第 2 节）。
 */
@Tag("architecture")
class RulesCoverNonEmptyPackagesTest {

  private static final String GATEWAY = StructuralRules.ROOT + ".gateway";

  /** 只有 `package-info.java` 的包。它们不算「规则已覆盖」。 */
  private static final List<String> PLACEHOLDER_PACKAGES =
      List.of("", "config", "faces", "providers", "transport", "observe");

  /** 已经有真实类的包。规则在它们上面不是空转 —— 阶段 0 是空的，随各阶段填充。 */
  private static final List<String> PACKAGES_WITH_CLASSES = List.of();

  private static final JavaClasses PRODUCTION = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(GATEWAY);

  @Test
  @DisplayName("两个名单的并集正好是磁盘上的包：新包不会被规则悄悄漏掉")
  void everyPackageIsAccountedFor() {
    Set<String> declared = new TreeSet<>(PLACEHOLDER_PACKAGES);
    declared.addAll(PACKAGES_WITH_CLASSES);

    assertThat(new TreeSet<>(packagesOnDisk()))
        .as("磁盘上的包与两个名单不一致。新包必须登记一次，"
            + "否则结构规则会安静地跳过它（ADR-0003 第 2 节）")
        .isEqualTo(declared);
  }

  @Test
  @DisplayName("占位包里确实没有类：有了类就必须从占位名单挪走")
  void placeholderPackagesStillHaveNoClasses() {
    for (String name : PLACEHOLDER_PACKAGES) {
      assertThat(namedClassesIn(name))
          .as("包已经有真实类了，它就不再是占位包，把它挪到 PACKAGES_WITH_CLASSES：%s", name)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("声称有类的包确实有类：规则不该对着空包宣称自己覆盖了它")
  void packagesClaimedToHaveClassesReallyDo() {
    for (String name : PACKAGES_WITH_CLASSES) {
      assertThat(namedClassesIn(name))
          .as("规则点名的包里一个类都没有，规则正在空转：%s", name)
          .isNotEmpty();
    }
  }

  private static List<String> namedClassesIn(String relativeName) {
    String pattern = GATEWAY + (relativeName.isEmpty() ? "" : "." + relativeName) + "..";
    return PRODUCTION.stream()
        .filter(resideInAPackage(pattern))
        .filter(RulesCoverNonEmptyPackagesTest::isNamedClass)
        .map(JavaClass::getSimpleName)
        .toList();
  }

  /** `package-info` 是包的说明书，不是包的内容：它不算「这个包有类」。 */
  private static boolean isNamedClass(JavaClass clazz) {
    return !"package-info".equals(clazz.getSimpleName());
  }

  private static List<String> packagesOnDisk() {
    return PRODUCTION.stream()
        .map(JavaClass::getPackageName)
        .filter(name -> name.startsWith(GATEWAY))
        .map(name -> name.substring(GATEWAY.length()).replaceFirst("^\\.", ""))
        .distinct()
        .toList();
  }
}
