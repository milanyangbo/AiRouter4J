package io.github.airouter.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.select.Candidate;
import io.github.airouter.gateway.select.InstanceSelector;
import io.github.airouter.gateway.select.RandomSelector;
import io.github.airouter.gateway.select.RoundRobinSelector;
import io.github.airouter.gateway.select.WeightedCandidates;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 六步链的单测：**不起容器、不起端点**。
 *
 * <p>输入是候选与上下文，输出是一个 id 或一次拒绝 —— 链是纯的，
 * 所以「挑得对不对」在这里能被严格断言，不需要知道当时网络是什么状态
 * （ADR-0011 第 2 节 (a)）。
 *
 * <p>这里刻意**不**断言「第 ② 步被调了一次」这类内部步骤：那是抄实现（testing.md 判据三）。
 * 断言的是每一步的效果确实落在了交出去的候选上。
 */
@Tag("unit")
final class SelectionChainTest {

  private static final String SERVICE = "chat";

  @Test
  @DisplayName("服务一个启用着的实例都没有：拒绝，且指名那个服务")
  void serviceWithoutEnabledInstancesIsRejected() {
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN, first());

    assertThatThrownBy(() -> chain.select(SERVICE, List.of(), SelectionContext.withoutCaller()))
        .isInstanceOf(NoUsableInstanceException.class)
        .hasMessageContaining(SERVICE)
        .extracting(failure -> ((NoUsableInstanceException) failure).rejection())
        .isEqualTo(Rejection.NO_ENABLED_INSTANCES);
  }

  @Test
  @DisplayName("标签筛完一个不剩：拒绝，而不是退回不筛")
  void unmatchedTagsAreRejectedRatherThanIgnored() {
    // 「退回不筛」的后果是把运维写的标签静默丢掉：请求照样出去，只是去了它不该去的地方。
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN, first());
    List<Candidate> candidates = List.of(new Candidate("plain", 1, Set.of()));

    assertThatThrownBy(() -> chain.select(SERVICE, candidates,
        SelectionContext.of("caller", Set.of("gpu:a100"))))
        .isInstanceOf(NoUsableInstanceException.class)
        .hasMessageContaining(SERVICE)
        .extracting(failure -> ((NoUsableInstanceException) failure).rejection())
        .isEqualTo(Rejection.NO_TAG_MATCH);
  }

  @Test
  @DisplayName("闸门把剩下的全部排空：拒绝，而不是退回全部候选")
  void exhaustedGateIsRejectedRatherThanRestored() {
    // 退回全部候选等于让熔断形同虚设：它刚刚判定这个实例不可用，转头又把流量送过去。
    SelectionChain chain = new SelectionChain(instanceId -> false, first());
    List<Candidate> candidates = List.of(new Candidate("a", 1, Set.of()));

    assertThatThrownBy(() -> chain.select(SERVICE, candidates, SelectionContext.withoutCaller()))
        .isInstanceOf(NoUsableInstanceException.class)
        .hasMessageContaining(SERVICE)
        .extracting(failure -> ((NoUsableInstanceException) failure).rejection())
        .isEqualTo(Rejection.GATE_EXHAUSTED);
  }

  @Test
  @DisplayName("三种拒绝各有各的措辞：处置不同，就不能说同一句话")
  void eachRejectionHasItsOwnWording() {
    assertThat(Rejection.NO_ENABLED_INSTANCES.describe(SERVICE))
        .isNotEqualTo(Rejection.NO_TAG_MATCH.describe(SERVICE));
    assertThat(Rejection.NO_TAG_MATCH.describe(SERVICE))
        .isNotEqualTo(Rejection.GATE_EXHAUSTED.describe(SERVICE));
    assertThat(Rejection.values()).hasSize(3);
  }

  @Test
  @DisplayName("策略拿到的是加权区间，不是被复制过的候选表：总长等于权重之和")
  void strategySeesWeightedIntervalsNotReplicatedCandidates() {
    Recorder recorder = new Recorder();
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN, recorder);
    List<Candidate> candidates = List.of(
        new Candidate("heavy", 3, Set.of()), new Candidate("light", 1, Set.of()));

    chain.select(SERVICE, candidates, SelectionContext.withoutCaller());

    assertThat(recorder.seen()).hasSize(1);
    WeightedCandidates handed = recorder.seen().get(0);
    assertThat(handed.total()).isEqualTo(4L);
    // 若按权重复制，这里会是 4 —— 权重 1000:1 时就是 1001 个元素。
    assertThat(handed.candidates()).hasSize(2);
  }

  @Test
  @DisplayName("闸门是排除不是降权：被排除的实例权重再大也一次都拿不到")
  void gateExcludesRatherThanDownweights() {
    SelectionChain chain = new SelectionChain(instanceId -> !"broken".equals(instanceId), first());
    List<Candidate> candidates = List.of(
        new Candidate("healthy", 1, Set.of()), new Candidate("broken", 9, Set.of()));

    // 它的权重大九倍，却连一次都拿不到 —— 降权只会让它继续拿到一小部分。
    assertThat(chain.select(SERVICE, candidates, SelectionContext.withoutCaller()))
        .isEqualTo("healthy");
  }

  @Test
  @DisplayName("标签筛与闸门先后作用于同一批候选：策略只看见两道都过了的那些")
  void strategySeesOnlyCandidatesThatPassedBothFilters() {
    Recorder recorder = new Recorder();
    SelectionChain chain =
        new SelectionChain(instanceId -> !"gpu-circuit-open".equals(instanceId), recorder);
    List<Candidate> candidates = List.of(
        new Candidate("gpu-ok", 1, Set.of("gpu:a100")),
        new Candidate("cpu-only", 1, Set.of()),
        new Candidate("gpu-circuit-open", 1, Set.of("gpu:a100")));

    chain.select(SERVICE, candidates, SelectionContext.of("caller", Set.of("gpu:a100")));

    assertThat(recorder.seen().get(0).candidates()).extracting(Candidate::id)
        .containsExactly("gpu-ok");
  }

  @Test
  @DisplayName("① 的产物真的递到了策略手上：粘滞与两个 hash 策略要靠它")
  void callerIdentityReachesTheStrategy() {
    Recorder recorder = new Recorder();
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN, recorder);

    chain.select(SERVICE, List.of(new Candidate("a", 1, Set.of())),
        SelectionContext.of("tenant-42"));

    assertThat(recorder.contexts().get(0).callerId()).isEqualTo("tenant-42");
  }

  @Test
  @DisplayName("轮询按权重分配：1:1 时严格交替（与光标起点无关）")
  void roundRobinAlternatesWithEqualWeights() {
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN, new RoundRobinSelector());
    List<Candidate> candidates = List.of(new Candidate("a", 1, Set.of()),
        new Candidate("b", 1, Set.of()));

    List<String> picked = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      picked.add(chain.select(SERVICE, candidates, SelectionContext.withoutCaller()));
    }

    assertThat(picked).hasSize(6);
    // 光标是进程级共享状态，起点随执行顺序而变；「相邻两次不同」与起点无关。
    for (int i = 1; i < picked.size(); i++) {
      assertThat(picked.get(i)).as("第 %d 次", i).isNotEqualTo(picked.get(i - 1));
    }
    assertThat(picked).contains("a", "b");
  }

  @Test
  @DisplayName("轮询按权重分配：3:1 取八次恰好 6:2，且序列周期为 4（与光标起点无关）")
  void roundRobinSplitsTrafficByWeight() {
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN, new RoundRobinSelector());
    List<Candidate> candidates = List.of(
        new Candidate("heavy", 3, Set.of()), new Candidate("light", 1, Set.of()));

    List<String> picked = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      picked.add(chain.select(SERVICE, candidates, SelectionContext.withoutCaller()));
    }

    // 八次 = 两整轮：无论光标从哪里起步，计数都必然是 6:2。
    assertThat(picked).filteredOn("heavy"::equals).hasSize(6);
    assertThat(picked).filteredOn("light"::equals).hasSize(2);
    // 轮转的周期性同样与起点无关：第 i 次与第 i+4 次必然是同一个实例。
    for (int i = 0; i < 4; i++) {
      assertThat(picked.get(i + 4)).as("第 %d 次与第四轮后", i).isEqualTo(picked.get(i));
    }
  }

  @Test
  @DisplayName("随机用的是注入进来的那个源：固定源 → 固定落点")
  void randomHonoursTheInjectedSource() {
    // 源恒定返回 3：权重 3:1 的区间总长是 4，落点 3 落在第二个候选里。
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN,
        new RandomSelector(bound -> 3L));
    List<Candidate> candidates = List.of(
        new Candidate("heavy", 3, Set.of()), new Candidate("light", 1, Set.of()));

    for (int i = 0; i < 5; i++) {
      assertThat(chain.select(SERVICE, candidates, SelectionContext.withoutCaller()))
          .as("第 %d 次", i)
          .isEqualTo("light");
    }
  }

  @Test
  @DisplayName("随机源被问到的是区间总长：不是候选个数")
  void randomSourceIsAskedForTheIntervalLength() {
    List<Long> bounds = new ArrayList<>();
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN,
        new RandomSelector(bound -> {
          bounds.add(bound);
          return 0L;
        }));
    List<Candidate> candidates = List.of(
        new Candidate("heavy", 3, Set.of()), new Candidate("light", 1, Set.of()));

    chain.select(SERVICE, candidates, SelectionContext.withoutCaller());

    assertThat(bounds).containsExactly(4L);
  }

  @Test
  @DisplayName("调用方标识缺失不报错：链只取不校验，选择照常进行")
  void missingCallerStillSelects() {
    SelectionChain chain = new SelectionChain(AvailabilityGate.OPEN, first());
    List<Candidate> candidates = List.of(new Candidate("a", 1, Set.of()));

    assertThat(chain.select(SERVICE, candidates, SelectionContext.withoutCaller()))
        .isEqualTo("a");
  }

  @Test
  @DisplayName("上下文里没有「是否流式」这一项：不给分岔留入口")
  void contextCarriesNoStreamFlag() {
    List<String> components = Arrays.stream(SelectionContext.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();

    assertThat(components).as("SelectionContext 的成员")
        .containsExactlyInAnyOrder("callerId", "requiredTags");
  }

  @Test
  @DisplayName("拒绝时策略一次都没被问到：不出网这件事从源头就成立")
  void rejectedSelectionNeverReachesTheStrategy() {
    Recorder recorder = new Recorder();
    SelectionChain chain = new SelectionChain(instanceId -> false, recorder);

    assertThatThrownBy(() -> chain.select(SERVICE, List.of(new Candidate("a", 1, Set.of())),
        SelectionContext.withoutCaller()))
        .isInstanceOf(NoUsableInstanceException.class);

    assertThat(recorder.seen()).as("策略被问到的次数").isEmpty();
  }

  /** 取第一个候选：让断言落在「链把它挑出来的那一个」上，而不是策略自己的算术。 */
  private static InstanceSelector first() {
    return (candidates, context) -> candidates.pick(0).id();
  }

  /** 记下策略真正收到的东西：候选、区间与上下文都在这里看得见。 */
  private static final class Recorder implements InstanceSelector {

    private final List<WeightedCandidates> seen = new ArrayList<>();

    private final List<SelectionContext> contexts = new ArrayList<>();

    @Override
    public String select(WeightedCandidates candidates, SelectionContext context) {
      seen.add(candidates);
      contexts.add(context);
      return candidates.pick(0).id();
    }

    List<WeightedCandidates> seen() {
      return seen;
    }

    List<SelectionContext> contexts() {
      return contexts;
    }
  }
}
