package io.github.airouter.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 六步选择链与它的前两步策略（ADR-0011 第 4 节）。
 *
 * <p>这里**没有容器、没有端点、没有网络**：策略是纯函数，所以「策略对不对」是一件
 * 不需要任何环境就能被判定的事。它同时也是「策略不许做 IO」那条约束的兑现 ——
 * 约束若是只写在文档里，它会在某个赶工的下午被破掉，而这类破法在代码审查看起来很合理。
 *
 * <p>每一条断言对着 ADR 第 4 节表里的一行：加权独立于策略、随机可复现、
 * 标签筛完为空是拒绝而不是忽略、闸门能排空候选。
 */
@Tag("unit")
final class SelectionChainTest {

  private static final String SERVICE = "chat";

  private static final SelectionContext ANONYMOUS = SelectionContext.ANONYMOUS;

  /**
   * 权重决定被选中的频率，而且**是严格可数的**。
   *
   * <p>轮询在这里的价值是它的确定性：权重 3:1 表现为「三步前者、一步后者」，
   * 四次调用恰好是 3 与 1。用随机策略就只能断言比例，
   * 而比例对的那一种实现也可能根本没看权重 —— 那正是 legacy `CachedWeightedSelector`
   * 出事的方式（只有轮询引用了它）。
   */
  @Test
  @DisplayName("权重决定被选中的次数：3:1 的两个实例在八次调用里是六次与两次")
  void weightDecidesHowOftenAnInstanceIsChosen() {
    SelectionChain chain = new SelectionChain(candidate -> true, new RoundRobinSelector());

    List<String> chosen = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      chosen.add(chain.select(SERVICE, List.of(new Candidate("heavy", 3, Set.of()),
          new Candidate("light", 1, Set.of())), ANONYMOUS).id());
    }

    assertThat(chosen).containsExactly("heavy", "heavy", "heavy", "light",
        "heavy", "heavy", "heavy", "light");
  }

  /**
   * **加权独立于策略**：换一个策略，权重仍然是同一个行为（ADR-0011 第 2 节 (b)）。
   *
   * <p>两条路各取四千次：轮询给的是**精确**的 3:1，随机给的是**趋近** 3:1。
   * 断言两边都成立，守的是「加权不在策略里」——
   * 若有人把加权挪进某一个策略，另一条路的比例会立刻塌回一半。
   */
  @Test
  @DisplayName("换任意一个策略，权重展开的行为都一致：加权不在策略里")
  void weightingBehavesTheSameUnderEitherStrategy() {
    List<Candidate> candidates =
        List.of(new Candidate("heavy", 3, Set.of()), new Candidate("light", 1, Set.of()));
    SelectionChain roundRobin = new SelectionChain(candidate -> true, new RoundRobinSelector());
    SelectionChain random =
        new SelectionChain(candidate -> true, new RandomSelector(new Random(20261027L)));

    assertThat(shareOfHeavy(roundRobin, candidates, 4000))
        .as("轮询下的 heavy 占比").isEqualTo(0.75);
    assertThat(shareOfHeavy(random, candidates, 4000))
        .as("随机下的 heavy 占比：它应当趋近权重占比，而不是一半")
        .isCloseTo(0.75, Offset.offset(0.03));
  }

  /**
   * 给定固定随机源，结果**可复现**（ADR-0011 第 4 节第一行）。
   *
   * <p>这条断言是「策略是纯函数」的证据：同一个种子给出同一串序列。
   * 策略自己 new 一个随机源的话，它写不出来 ——
   * 而写不出这一条，就只能靠「跑很多次看比例」，
   * 那种断言分不出「看了权重」与「碰巧一半」。
   */
  @Test
  @DisplayName("同一个随机源给出同一串序列：随机策略是可复现的")
  void aFixedRandomSourceGivesTheSameSequence() {
    List<Candidate> candidates = List.of(new Candidate("a", 1, Set.of()),
        new Candidate("b", 1, Set.of()), new Candidate("c", 2, Set.of()));
    WeightedCandidates weighted = WeightedCandidates.of(candidates);
    InstanceSelector first = new RandomSelector(new Random(42L));
    InstanceSelector second = new RandomSelector(new Random(42L));

    List<String> one = new ArrayList<>();
    List<String> two = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      one.add(first.select(weighted, ANONYMOUS).id());
      two.add(second.select(weighted, ANONYMOUS).id());
    }

    assertThat(one).isEqualTo(two).hasSize(50);
    assertThat(one).as("五十次里三个候选都该出现过，否则「随机」存疑").contains("a", "b", "c");
  }

  /** 轮询的序列就是候选顺序本身：它是最容易在多实例下被发现配错的那一个策略。 */
  @Test
  @DisplayName("等权重下轮询按候选顺序轮转")
  void roundRobinWalksThroughCandidatesInOrder() {
    SelectionChain chain = new SelectionChain(candidate -> true, new RoundRobinSelector());
    List<Candidate> candidates =
        List.of(new Candidate("first", 1, Set.of()), new Candidate("second", 1, Set.of()));

    List<String> chosen = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      chosen.add(chain.select(SERVICE, candidates, ANONYMOUS).id());
    }

    assertThat(chosen).containsExactly("first", "second", "first", "second");
  }

  /**
   * 第 ② 步：标签筛完只剩带齐的那些。
   *
   * <p>「带齐」而不是「带任一个」：规则说「要 GPU 且要同机房」时，
   * 一个只有 GPU 的实例不该被当成合格目标 —— 那会让规则悄悄降级成提示。
   */
  @Test
  @DisplayName("标签筛选只留下带齐全部标签的实例")
  void tagsKeepOnlyTheInstancesCarryingAllOfThem() {
    SelectionChain chain = new SelectionChain(candidate -> true, new RoundRobinSelector());
    List<Candidate> candidates = List.of(
        new Candidate("both", 1, Set.of("gpu", "near")),
        new Candidate("partial", 9, Set.of("gpu")),
        new Candidate("none", 9, Set.of()));
    SelectionContext wants = new SelectionContext("", Set.of("gpu", "near"));

    List<String> chosen = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      chosen.add(chain.select(SERVICE, candidates, wants).id());
    }

    assertThat(chosen).as("权重更大但只带一半标签的实例不该被挑中").containsExactly("both", "both", "both");
  }

  /**
   * 第 ② 步：**没有带齐标签的实例是拒绝，不是「退回不筛」**（ADR-0012）。
   *
   * <p>退回不筛的那一种实现看起来更宽容，而它的代价是规则失效得无声无息：
   * 请求照旧成功、日志干净，只有「它本该只走那些实例」这一条再也没人知道。
   */
  @Test
  @DisplayName("没有实例带齐标签时是拒绝，而不是忽略标签继续挑")
  void noInstanceCarryingTheTagsIsARejectionRatherThanAnIgnoredFilter() {
    SelectionChain chain = new SelectionChain(candidate -> true, new RoundRobinSelector());
    List<Candidate> candidates = List.of(new Candidate("plain", 1, Set.of()));
    SelectionContext wants = new SelectionContext("", Set.of("gpu"));

    assertThatThrownBy(() -> chain.select(SERVICE, candidates, wants))
        .isInstanceOf(NoUsableInstanceException.class)
        .hasMessageContaining("gpu")
        .hasMessageContaining(SERVICE);
  }

  /**
   * 第 ③ 步：闸门能把候选排空，而**排空是拒绝**而不是「退回全部候选」。
   *
   * <p>这是熔断最要紧的那一半：闸门全关时若退回全部候选，
   * 熔断就等于没接上，而系统看起来还是好的 —— 请求照旧出去，只是打给了那个坏实例。
   */
  @Test
  @DisplayName("闸门排空全部候选时是拒绝：熔断不能退化成没接上")
  void aGateThatExcludesEverythingIsARejection() {
    SelectionChain chain = new SelectionChain(candidate -> false, new RoundRobinSelector());
    List<Candidate> candidates = List.of(new Candidate("broken", 1, Set.of()));

    assertThatThrownBy(() -> chain.select(SERVICE, candidates, ANONYMOUS))
        .isInstanceOf(NoUsableInstanceException.class)
        .hasMessageContaining("可用性闸门")
        .hasMessageContaining(SERVICE);
  }

  /**
   * 一个启用着的实例都没有时，**指名服务**地拒绝。
   *
   * <p>服务名必须出现在信息里：一个网关通常不止一个服务，
   * 「没有可用实例」少了它是半句话，运维要再去猜是哪个服务。
   */
  @Test
  @DisplayName("服务没有任何启用着的实例时拒绝，并且指名那个服务")
  void anEmptyPoolIsRejectedAndNamesTheService() {
    SelectionChain chain = new SelectionChain(candidate -> true, new RoundRobinSelector());

    assertThatThrownBy(() -> chain.select(SERVICE, List.of(), ANONYMOUS))
        .isInstanceOf(NoUsableInstanceException.class)
        .hasMessageContaining(SERVICE)
        .hasMessageContaining("启用");
  }

  /**
   * 第 ① 步的归一：没有认证来源时是**空串**而不是 null。
   *
   * <p>它守的是下游那两个要按标识取哈希的策略：null 会让它们各写一遍判空，
   * 而其中迟早会有一处写成「当作空串」另一处写成「当作不筛」——
   * 同一次调用于是有两种身份。
   */
  @Test
  @DisplayName("匿名的上下文带的是空串而不是 null")
  void anAnonymousContextCarriesAnEmptyClientId() {
    assertThat(new SelectionContext(null, null).clientId()).isEmpty();
    assertThat(new SelectionContext(null, null).requiredTags()).isEmpty();
    assertThat(SelectionContext.ANONYMOUS.clientId()).isEmpty();
  }

  /** 区间之外的位置是**报错**而不是绕回：绕回会把「候选变了」这件事藏成一次选错。 */
  @Test
  @DisplayName("区间之外的位置报错，并报出区间长度")
  void aPositionOutsideTheIntervalIsRejectedAndNamesItsLength() {
    WeightedCandidates weighted = WeightedCandidates.of(
        List.of(new Candidate("a", 2, Set.of()), new Candidate("b", 1, Set.of())));

    assertThat(weighted.total()).isEqualTo(3L);
    assertThat(weighted.at(2L).id()).isEqualTo("b");
    assertThatThrownBy(() -> weighted.at(3L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("3");
  }

  /** 权重为 0 或负数的候选不该存在：它会在加权那一步表现为「永远选不到」，与「坏了」无法区分。 */
  @Test
  @DisplayName("权重不是正数的候选在构造时就被挡下")
  void aCandidateMustCarryAPositiveWeight() {
    assertThatThrownBy(() -> new Candidate("zero", 0, Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero");
    assertThatThrownBy(() -> WeightedCandidates.of(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 给定策略取若干次，返回 heavy 被选中的比例。 */
  private static double shareOfHeavy(SelectionChain chain, List<Candidate> candidates, int times) {
    int heavy = 0;
    for (int i = 0; i < times; i++) {
      if ("heavy".equals(chain.select(SERVICE, candidates, ANONYMOUS).id())) {
        heavy++;
      }
    }
    return (double) heavy / times;
  }
}
