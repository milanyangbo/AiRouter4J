package io.github.airouter.gateway.select;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.routing.CallerIdentityMissingException;
import io.github.airouter.gateway.routing.SelectionContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 需要**额外输入**的那四个策略：两个读计数、两个读调用方标识。
 *
 * <p>它们的输入一律是**注入进来的查表动作**，不是一个 Map 快照：
 * 在途数与延迟样本是每次选择都要重新问一遍的东西，快照会让它成为构造期的常量。
 * 注入之后，「给定输入 → 结果确定」是一条能在没有容器、没有网络的地方写的断言 ——
 * 这也是本包坚持不许自己做 IO 的原因（ADR-0011 第 2 节 (a)(e)）。
 *
 * <p>断言写的是**性质**而不是某个具体数值：
 * 「最闲的那批被选中」「并列时权重仍然说话」「移除一个实例只搬动它自己的键」。
 * 把它们写成期望值会让每一次合法的实现改动都要回来改测试（testing.md 判据三）。
 *
 * <p>**不起端点、不起容器**：这四个策略要的东西只是几个数字与一个字符串。
 */
@Tag("unit")
final class InputDrivenStrategiesTest {

  private static final String HEAVY = "heavy";

  private static final String LIGHT = "light";

  private static final int VIRTUAL_NODES = 160;

  @Test
  @DisplayName("least-connections 挑当前压着最少调用的那一个")
  void leastConnectionsPrefersTheIdlestInstance() {
    InstanceSelector selector = new LeastConnectionsSelector(counts(Map.of("busy", 7, "idle", 2)));

    WeightedCandidates candidates = candidates(Candidate.of("busy", 1), Candidate.of("idle", 1));

    assertThat(selector.select(candidates, withoutCaller())).isEqualTo("idle");
    // 连着来几次也都是它：另一个实例一直比它忙。
    assertThat(selector.select(candidates, withoutCaller())).isEqualTo("idle");
  }

  /**
   * 反例：把「查不到」当成 0 的话，压着若干个请求的那个实例会显得和谁一样闲，
   * 于是这个策略退化成随机 —— 那正是来源项目「配了最少连接实际与随机无异」的形状。
   */
  @Test
  @DisplayName("在途数查不到：报错，而不是按 0 处理（按 0 会让这个策略退化成随机）")
  void unknownActiveCountIsRejectedRatherThanCountedAsZero() {
    InstanceSelector selector = new LeastConnectionsSelector(instanceId -> "unknown".equals(instanceId)
        ? null
        : 2);

    WeightedCandidates candidates = candidates(Candidate.of("unknown", 1), Candidate.of("known", 1));

    assertThatThrownBy(() -> selector.select(candidates, withoutCaller()))
        .isInstanceOf(ActiveCountMissingException.class)
        .hasMessageContaining("unknown");
  }

  @Test
  @DisplayName("least-connections 并列时由第 ④ 步的加权裁决：3:1 → 八次恰好 6:2")
  void leastConnectionsFallsBackToWeightingAmongEquals() {
    InstanceSelector selector = new LeastConnectionsSelector(counts(Map.of(HEAVY, 0, LIGHT, 0)));
    WeightedCandidates candidates = weighted();

    assertThat(eightPicks(selector, candidates)).filteredOn(HEAVY::equals).hasSize(6);
    assertThat(eightPicks(selector, candidates)).filteredOn(LIGHT::equals).hasSize(2);
  }

  /**
   * 「换任意策略，权重行为一致」这一条覆盖一致性哈希之外**全部五个**策略，分两种写法。
   *
   * <p>三个**能被严格断言**的：它们各自的输入在这条断言里都「说不出区别」
   * （都一样闲、都没有历史），于是它们该退化成同一个东西 —— 第 ④ 步的加权轮转，八次恰好 6:2。
   *
   * <p>另外两个只能断言**比例**：抽签抽到什么值、哈希算出什么值，本来就该由取值决定，
   * 把某一次落在谁身上写死是抄实现 —— 权重有没有被忽略只能由一大批取值的统计说出来。
   * 两个的区间写法不同（一个是区间映射、一个是平滑轮转），但**权重占比**是同一个数，
   * 于是这条断言抓得住「某个策略悄悄不看权重」。
   *
   * <p>一致性哈希不在这条断言里：它的加权内建在虚拟节点数上，退不出一个共同形状
   * （那个性质改由「键比例 ≈ 权重占比」承担，见下面那两条）。
   */
  @Test
  @DisplayName("输入不起作用的三个策略严格退化成按权重轮转，另两个按权重占比：五个策略的权重行为一致")
  void strategiesWithoutUsefulInputShareOneWeighting() {
    WeightedCandidates candidates = weighted();

    for (InstanceSelector selector : List.of(new RoundRobinSelector(),
        new LeastConnectionsSelector(counts(Map.of(HEAVY, 0, LIGHT, 0))),
        new LatencySelector(instanceId -> null))) {
      List<String> picks = eightPicks(selector, candidates);

      assertThat(picks)
          .as("%s 在没有信息可依的时候", selector.getClass().getSimpleName())
          .filteredOn(HEAVY::equals).hasSize(6);
      assertThat(picks).filteredOn(LIGHT::equals).hasSize(2);
    }

    assertThat(shareOfHeavy(new RandomSelector(seededDraw()))).as("random 的落点占比")
        .isBetween(0.70d, 0.80d);
    assertThat(shareOfHeavy(new IpHashSelector())).as("ip-hash 的键占比")
        .isBetween(0.70d, 0.80d);
  }

  /**
   * 延迟反比**乘进第 ④ 步的权重**，不是「挑最快的那一个」。
   *
   * <p>要守的性质是**份额随延迟下降**：900 毫秒对 20 毫秒（45 倍）在 3:1 的声明权重之下
   * 约合 6.5%，而「挑最快的那一个」给出的是 **0** —— 下降与归零是两种性质，
   * 不是同一个数的两端。写成比例而不是写成某个具体落点，是因为落点取决于累积值走到哪一步，
   * 抄下来就是把实现抄进断言（testing.md 判据三）。
   */
  @Test
  @DisplayName("latency 按延迟反比加权：慢的那个份额显著下降")
  void latencyWeightsByInverseLatency() {
    assertThat(shareOfHeavy(new LatencySelector(instanceId -> null)))
        .as("都还没有样本时退化为按声明权重轮转").isBetween(0.73d, 0.77d);
    assertThat(shareOfHeavy(new LatencySelector(ewma(Map.of(HEAVY, 900d, LIGHT, 20d)))))
        .as("慢 45 倍之后的份额").isBetween(0.02d, 0.12d);
  }

  /**
   * 本票存在的理由：**注入失败样本之后，那个实例被选中的份额下降但仍然大于零**。
   *
   * <p>惩罚值 30 秒意味着它的份额从 3/4 掉到千分之二 —— 那是**下降**，不是**归零**：
   * 四千次里它还会被选中若干次。「挑最快的那一个」在这里给出的是零，
   * 于是它永远不会再被量一次，也就永远不会从惩罚里出来 ——
   * 熔断器的半开状态为的正是同一件事（ADR-0013）。
   */
  @Test
  @DisplayName("注入失败样本后：那个实例的份额下降但仍然大于零（argmin 会把它压成零）")
  void aPenalisedInstanceLosesShareButKeepsSome() {
    double before = shareOfHeavy(new LatencySelector(ewma(Map.of(HEAVY, 20d, LIGHT, 20d))));
    double after = shareOfHeavy(new LatencySelector(ewma(Map.of(HEAVY, 30_000d, LIGHT, 20d))));

    assertThat(before).as("两边一样快时份额就是权重占比").isBetween(0.73d, 0.77d);
    assertThat(after).as("惩罚之后的份额").isLessThan(before / 10d).isPositive();
  }

  /**
   * 还没有样本的实例与**已知最快的那一个同档**：既不是最好的一档，也不是最差的一档。
   *
   * <p>这一条把一个裁决压成一句可观测的话 —— 权重相等时八次里恰好各四次。
   * 当成最快（0 毫秒）的话它会吃掉几乎全部流量（八次里七次以上）；
   * 当成最差的话它一次也拿不到。前者是这一处第一次落地的形状，
   * 后者是 spec 故事 47 那句话的另一半（「不是把未知实例当成最快」）。
   * 裁决写回了 ADR-0011 第 2 节 (d)。
   */
  @Test
  @DisplayName("latency 里没有样本的实例与已知最快的那一个同档：既不是最好，也不是最差")
  void instanceWithoutHistorySharesTheBestKnownTier() {
    InstanceSelector selector = new LatencySelector(instanceId -> HEAVY.equals(instanceId) ? 500d : null);

    WeightedCandidates candidates = candidates(Candidate.of(HEAVY, 1), Candidate.of(LIGHT, 1));

    assertThat(eightPicks(selector, candidates)).filteredOn(LIGHT::equals).hasSize(4);
  }

  /** 有效权重全为零说的是「这一轮谁都不该被选中」，退回第一个候选会把它变成静默放行。 */
  @Test
  @DisplayName("有效权重全为零时报错，而不是退回第一个候选")
  void rotationRejectsAnAllZeroRound() {
    WeightedRotation rotation = new WeightedRotation();

    assertThatThrownBy(() -> rotation.next(List.of(Candidate.of(HEAVY, 3), Candidate.of(LIGHT, 1)),
        new double[] {0d, 0d}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("大于零");
  }

  @Test
  @DisplayName("两个 hash 策略都把同一个调用方粘在同一个实例上，且不会把所有调用方落成同一个")
  void hashStrategiesKeepOneCallerOnOneInstance() {
    WeightedCandidates candidates = weighted();

    for (InstanceSelector selector : hashStrategies()) {
      String first = selector.select(candidates, caller("tenant-7"));
      assertThat(selector.select(candidates, caller("tenant-7")))
          .as("同一个调用方在 %s 上", selector.getClass().getSimpleName())
          .isEqualTo(first);

      List<String> targets = new ArrayList<>();
      for (int index = 0; index < 200; index++) {
        targets.add(selector.select(candidates, caller("tenant-" + index)));
      }
      assertThat(targets)
          .as("两百个调用方在 %s 上的落点", selector.getClass().getSimpleName())
          .contains(HEAVY, LIGHT);
    }
  }

  @Test
  @DisplayName("两个 hash 策略缺了调用方标识时拒绝：退化会让同一个会话被拆到若干实例上")
  void hashStrategiesRejectMissingCallerIdentity() {
    assertThatThrownBy(() -> new IpHashSelector().select(weighted(), withoutCaller()))
        .isInstanceOf(CallerIdentityMissingException.class)
        // 出错信息要说出「是谁在要这个标识」，而不是一句通用的检查失败。
        .hasMessageContaining(IpHashSelector.STRATEGY_ID);
    assertThatThrownBy(() -> new ConsistentHashSelector(VIRTUAL_NODES).select(weighted(),
        withoutCaller()))
        .isInstanceOf(CallerIdentityMissingException.class)
        .hasMessageContaining(ConsistentHashSelector.STRATEGY_ID);
  }

  /**
   * 权重在 hash 策略里也是真的：**同一个策略下，落到重实例上的键占比约等于它的权重占比**。
   *
   * <p>写成比例而不是写成某个键落在哪里，是因为落点取决于哈希的值，
   * 而「权重没有被忽略」这件事只能由一大批键的统计结果说出来。
   */
  @Test
  @DisplayName("ip-hash 的落点服从权重：3:1 时约 3/4 的键落在权重的那一个上")
  void ipHashDistributionFollowsWeights() {
    InstanceSelector selector = new IpHashSelector();

    assertThat(shareOfHeavy(selector)).isBetween(0.70d, 0.80d);
  }

  /** 一致性哈希的同一条性质（ weights 由虚拟节点数实现，不与第 ④ 步叠加）。 */
  @Test
  @DisplayName("consistent-hash 的键比例 ≈ 权重占比：虚拟节点数承担了加权")
  void consistentHashDistributionFollowsWeights() {
    InstanceSelector selector = new ConsistentHashSelector(VIRTUAL_NODES);

    assertThat(shareOfHeavy(selector)).isBetween(0.70d, 0.80d);
  }

  /**
   * 一致性哈希之所以「一致」：**移除一个实例时，只有原本落在它上面的键会迁移**。
   *
   * <p>这是一条**不需要写一个期望数**的性质 —— 它要么成立要么不成立。
   * 它抓得住那种「每次都用全部候选重新摊一遍」的实现：那样的话所有键都会移动，
   * 而这种实现的后果要跑很久才看得出来。
   */
  @Test
  @DisplayName("移除一个实例时，只有原本落在它上面的键迁移：别的键原地不动")
  void removingAnInstanceOnlyMovesItsOwnKeys() {
    InstanceSelector selector = new ConsistentHashSelector(VIRTUAL_NODES);
    WeightedCandidates three = candidates(Candidate.of(HEAVY, 1), Candidate.of(LIGHT, 1),
        Candidate.of("spare", 1));
    WeightedCandidates withoutLight = candidates(Candidate.of(HEAVY, 1), Candidate.of("spare", 1));

    Map<String, String> before = new HashMap<>();
    Map<String, String> after = new HashMap<>();
    int movedFromLight = 0;
    for (String key : keys(3_000)) {
      String target = selector.select(three, caller(key));
      before.put(key, target);
      String now = selector.select(withoutLight, caller(key));
      after.put(key, now);
      if (LIGHT.equals(target)) {
        movedFromLight++;
      }
    }

    assertThat(movedFromLight).as("改之前落在要移除的那个实例上的键数").isPositive();
    for (Map.Entry<String, String> entry : before.entrySet()) {
      if (!LIGHT.equals(entry.getValue())) {
        assertThat(after.get(entry.getKey()))
            .as("键 %s 原本不在被移除的那个实例上", entry.getKey())
            .isEqualTo(entry.getValue());
      }
    }
    assertThat(after.values()).as("迁移之后不再有人落在被移除的那个实例上").doesNotContain(LIGHT);
  }

  /**
   * 同一件事的另一半：**新增一个实例时，只有它会接到别人的键** ——
   * 已有的实例之间不会因为多了一个竞争者就互相让出键来。
   */
  @Test
  @DisplayName("新增一个实例时，只有新增的那个会接到键：已有实例之间不互相让")
  void addingAnInstanceOnlyTakesKeysForTheNewcomer() {
    InstanceSelector selector = new ConsistentHashSelector(VIRTUAL_NODES);
    WeightedCandidates two = candidates(Candidate.of(HEAVY, 1), Candidate.of(LIGHT, 1));
    WeightedCandidates three = candidates(Candidate.of(HEAVY, 1), Candidate.of(LIGHT, 1),
        Candidate.of("spare", 1));

    int newcomers = 0;
    for (String key : keys(3_000)) {
      String before = selector.select(two, caller(key));
      String now = selector.select(three, caller(key));
      if (!before.equals(now)) {
        assertThat(now).as("键 %s 迁移后应当只落到新增的那个上", key).isEqualTo("spare");
        newcomers++;
      }
    }

    assertThat(newcomers).as("新增的实例分到的键数").isPositive();
  }

  /**
   * 一批键落在 HEAVY 上的占比。
   *
   * <p>两侧的差别在这一处并不重要：写一串比例而不是写某个具体落点，
   * 是因为「权重有没有被忽略」只能由统计说出来，
   * 而单个键落在哪里本来就该由哈希决定 —— 把它写死是抄实现。
   *
   * @return heavy 分到的键占比
   */
  private static double shareOfHeavy(InstanceSelector selector) {
    int heavy = 0;
    int total = 4_000;
    for (int index = 0; index < total; index++) {
      if (HEAVY.equals(selector.select(weighted(), caller("caller-" + index)))) {
        heavy++;
      }
    }
    return (double) heavy / total;
  }

  /**
   * 固定种子的抽签源。
   *
   * <p>比例断言要能复现：用进程级随机源的话，同一条断言这次绿、下次红，
   * 而没人看得懂那一红到底说的是「权重没生效」还是「这一轮手气不好」。
   */
  private static LongUnaryOperator seededDraw() {
    Random random = new Random(20261029L);
    return bound -> random.nextLong(bound);
  }

  private static List<InstanceSelector> hashStrategies() {
    return List.of(new IpHashSelector(), new ConsistentHashSelector(VIRTUAL_NODES));
  }

  private static List<String> keys(int count) {
    List<String> generated = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      generated.add("caller-" + index);
    }
    return generated;
  }

  private static List<String> eightPicks(InstanceSelector selector, WeightedCandidates candidates) {
    List<String> picks = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      picks.add(selector.select(candidates, withoutCaller()));
    }
    return picks;
  }

  /** 实例 → 在途数。写漂移在测试里而不是去用 {@code transport} 那个实现。 */
  private static Function<String, Integer> counts(Map<String, Integer> given) {
    return instanceId -> given.getOrDefault(instanceId, 0);
  }

  /** 实例 → EWMA（毫秒）。同理，这里不用 {@code observe} 那个实现。 */
  private static Function<String, Double> ewma(Map<String, Double> given) {
    return instanceId -> given.get(instanceId);
  }

  private static SelectionContext caller(String callerId) {
    return SelectionContext.of(callerId);
  }

  private static SelectionContext withoutCaller() {
    return SelectionContext.withoutCaller();
  }

  /** 权重 3:1 的两个候选。 */
  private static WeightedCandidates weighted() {
    return candidates(Candidate.of(HEAVY, 3), Candidate.of(LIGHT, 1));
  }

  private static WeightedCandidates candidates(Candidate... given) {
    return WeightedCandidates.of(List.of(given));
  }
}
