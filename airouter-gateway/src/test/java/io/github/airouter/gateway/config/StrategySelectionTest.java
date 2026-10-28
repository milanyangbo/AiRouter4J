package io.github.airouter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.routing.SelectionContext;
import io.github.airouter.gateway.select.Candidate;
import io.github.airouter.gateway.select.InstanceSelector;
import io.github.airouter.gateway.select.SelectionStrategy;
import io.github.airouter.gateway.select.SelectionStrategyFactory;
import io.github.airouter.gateway.select.WeightedCandidates;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 「用哪个策略」从**配置里的一个名字**进来这件事。
 *
 * <p>它有两个入口，守的是同一件事：**写错名字要起不来，并且告诉运维当时登记了哪些名字**。
 * 一个是绑定（{@link RoutingProperties} 在构造时问一次），另一个是工厂（创建时问一次，
 * 防的是有人绕过绑定直接用工厂）。两处用的是同一份清单 —— 名字只写在
 * {@link SelectionStrategy} 里，不存在两处各 maintain 一份的可能。
 *
 * <p>为什么报错而不是「给一个默认值继续跑」：后者正是票 07 那三个字段的形状 ——
 * 配了的开关没生效，系统照跑、日志干净，只有流量走向不对才看得出来。
 */
@Tag("unit")
final class StrategySelectionTest {

  private static final String ROUND_ROBIN = "round-robin";

  private static final String RANDOM = "random";

  @Test
  @DisplayName("不认识的策略名：起不来，并列出当时登记的名字")
  void unknownStrategyNameFailsListingRegisteredOnes() {
    assertThatThrownBy(() -> SelectionStrategy.of("least-connections"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("least-connections")
        .hasMessageContaining(RANDOM)
        .hasMessageContaining(ROUND_ROBIN);
  }

  @Test
  @DisplayName("空的策略名是缺失，不是一个合法取值：同样起不来")
  void blankStrategyNameIsMissingNotAValue() {
    assertThatThrownBy(() -> SelectionStrategy.of("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(RANDOM);
  }

  @Test
  @DisplayName("配置里的未知策略名同样起不来：出错信息指名环境变量，运维改的是环境")
  void routingPropertiesRejectsUnknownNameNamingTheEnvironment() {
    assertThatThrownBy(() -> properties("least-connections", false, 8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(RoutingProperties.STRATEGY_ENV)
        .hasMessageContaining("least-connections")
        .hasMessageContaining(RANDOM);
  }

  @Test
  @DisplayName("配置里缺了策略名：起不来")
  void routingPropertiesRejectsMissingName() {
    assertThatThrownBy(() -> properties("", false, 8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(RoutingProperties.STRATEGY_ENV);
  }

  @Test
  @DisplayName("粘滞表容量不是正整数：起不来，而不是「零容量」这种每一次都淘汰的表")
  void nonPositiveStickyCapacityFails() {
    assertThatThrownBy(() -> properties(ROUND_ROBIN, true, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(RoutingProperties.STICKY_MAX_ENTRIES_ENV);
  }

  @Test
  @DisplayName("已登记的每一个名字都造得出一个真能挑出实例的策略")
  void everyRegisteredNameYieldsAWorkingStrategy() {
    SelectionStrategyFactory factory = new SelectionStrategyFactory();

    assertThat(Arrays.stream(SelectionStrategy.values())).isNotEmpty();
    for (SelectionStrategy strategy : SelectionStrategy.values()) {
      InstanceSelector selector = factory.create(strategy);

      assertThat(selector.select(candidates(), SelectionContext.withoutCaller()))
          .as("策略 %s 挑出的实例", strategy.id())
          .isIn("heavy", "light");
      assertThat(factory.describeSupportedStrategies()).contains(strategy.id());
    }
  }

  @Test
  @DisplayName("round-robin 这个名字给的就是按权重轮转的那一个（不认这种行为的形状不算登记）")
  void roundRobinNameGivesRotation() {
    InstanceSelector selector = new SelectionStrategyFactory()
        .create(SelectionStrategy.of(ROUND_ROBIN));

    List<String> picked = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      picked.add(selector.select(candidates(), SelectionContext.withoutCaller()));
    }

    // 1:1 权重下相邻两次必然不同：这条与光标起点无关。
    for (int i = 1; i < picked.size(); i++) {
      assertThat(picked.get(i)).as("第 %d 次", i).isNotEqualTo(picked.get(i - 1));
    }
    assertThat(picked).contains("heavy", "light");
  }

  @Test
  @DisplayName("出错信息列的是**当时**登记的名字：这个清单随登记一起长")
  void listedNamesTrackRegistration() {
    assertThat(new SelectionStrategyFactory().describeSupportedStrategies())
        .as("factory 报出来的清单")
        .isEqualTo(SelectionStrategy.describeAll())
        .as("factory 与名字清单是同一份")
        .contains(RANDOM, ROUND_ROBIN);

    assertThat(SelectionStrategy.describeAll())
        .as("本阶段登记的两个；票 10 补四个之后这一条会跟着变")
        .isEqualTo("random / round-robin");
  }

  private static RoutingProperties properties(String strategy, boolean stickyEnabled,
      int stickyMaxEntries) {
    return new RoutingProperties(strategy, stickyEnabled, stickyMaxEntries);
  }

  /** 1:1 的两个候选。 */
  private static WeightedCandidates candidates() {
    return WeightedCandidates.of(List.of(Candidate.of("heavy", 1), Candidate.of("light", 1)));
  }
}
