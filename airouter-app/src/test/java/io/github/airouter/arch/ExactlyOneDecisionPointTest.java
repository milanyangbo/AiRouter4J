package io.github.airouter.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.airouter.gateway.governance.DecisionPoint;
import io.github.airouter.gateway.governance.RateLimitDecisionPoint;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 治理侧的决策点有且仅有一个。
 *
 * <p>本票没有实现任何限流算法，因此这条断言锁的是**结构**而不是行为：
 * 将来那个算法只能长在 {@link RateLimitDecisionPoint} 上，长在别处会让这里变红。
 * 少了这一条，「限流在过滤器里判一次、在客户端里又判一次」这种形态会在无人察觉的情况下回来 ——
 * 那正是来源项目那个口径不一致问题的起点，而它当初也没有任何一处会报错。
 */
@Tag("architecture")
final class ExactlyOneDecisionPointTest {

  private static final JavaClasses GOVERNANCE = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(StructuralRules.ROOT + ".gateway.governance");

  @Test
  @DisplayName("治理包里被标记为决策点的接口恰好一个")
  void thereIsExactlyOneDecisionPoint() {
    List<String> points = GOVERNANCE.stream()
        .filter(JavaClass::isInterface)
        .filter(type -> type.isAnnotatedWith(DecisionPoint.class))
        .map(JavaClass::getName)
        .toList();

    assertThat(points).containsExactly(RateLimitDecisionPoint.class.getName());
  }
}
