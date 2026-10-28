package io.github.airouter.gateway.select;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.routing.SelectionContext;
import io.github.airouter.gateway.routing.CallerIdentityMissingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 第 ⑤ 步：**粘滞是包装器**的几条性质。
 *
 * <p>这里钉的都是「配了粘滞，但它在一部分调用上没有兑现」那一种静默失效
 * —— 形状与票 07 那三个有配置、无消费者的字段完全相同：系统照跑、日志干净、
 * 只有一个会话被拆到了若干实例上才看得出来。
 *
 * <p>全部**不起容器、不起端点**：粘滞的输入全是参数 —— 键从上下文来、策略是纯函数、
 * 候选是两块数据 —— 于是「粘住没粘住」不需要任何进程外的东西就能判。
 */
@Tag("unit")
final class StickySelectorTest {

  private static final String HEAVY = "a";

  private static final String LIGHT = "b";

  @Test
  @DisplayName("粘滞 + 随机：同一个调用方连续六次落在同一个实例上（同一个随机源不给粘滞时会一路换）")
  void survivesARandomDelegate() {
    InstanceSelector sticky = sticky(new RandomSelector(flippingSource()), 16);

    List<String> picked = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      picked.add(sticky.select(candidates(), context("tenant-7")));
    }

    assertThat(picked).as("同一个调用方的六次选择").containsOnly(picked.get(0));
    // 反例：同一个随机源不被粘滞包住时，四次取样里两个实例都会出现。
    assertThat(picksWithoutSticky(new RandomSelector(flippingSource()))).contains(HEAVY, LIGHT);
  }

  @Test
  @DisplayName("粘滞 + 轮询：同一个调用方连续六次落在同一个实例上（轮询每两次必换一次）")
  void survivesARoundRobinDelegate() {
    InstanceSelector sticky = sticky(new RoundRobinSelector(), 16);

    List<String> picked = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      picked.add(sticky.select(candidates(), context("tenant-7")));
    }

    assertThat(picked).as("同一个调用方的六次选择").containsOnly(picked.get(0));
    assertThat(picksWithoutSticky(new RoundRobinSelector())).contains(HEAVY, LIGHT);
  }

  @Test
  @DisplayName("两个调用方各自粘住自己的那一个，且互不干扰")
  void eachCallerKeepsItsOwnBinding() {
    InstanceSelector sticky = sticky(new RoundRobinSelector(), 16);

    // 故意不让两个调用方交替出现：同一个调用方连着来两次时，
    // 轮询会把第二次送到别处 —— 而交替着来恰好能冒充粘滞（1:1 权重下它也是稳定的）。
    String firstOfFirst = sticky.select(candidates(), context("caller-1"));
    String secondOfFirst = sticky.select(candidates(), context("caller-1"));
    String firstOfSecond = sticky.select(candidates(), context("caller-2"));
    String secondOfSecond = sticky.select(candidates(), context("caller-2"));

    assertThat(secondOfFirst).as("同一个调用方连着来两次").isEqualTo(firstOfFirst);
    assertThat(firstOfSecond).as("第二个调用方落在别处").isNotEqualTo(firstOfFirst);
    assertThat(secondOfSecond).as("第二个调用方连着来两次").isEqualTo(firstOfSecond);
    assertThat(sticky.select(candidates(), context("caller-1"))).isEqualTo(firstOfFirst);
  }

  @Test
  @DisplayName("被粘住的实例不在候选里了：交给策略重挑，并把新结果记回去（不是报错）")
  void rebindsWhenTheRememberedInstanceIsGone() {
    FixedSequence delegate = new FixedSequence(HEAVY, "c");
    InstanceSelector sticky = sticky(delegate, 16);

    String first = sticky.select(candidates(), context("tenant-7"));
    // heavy 被关掉 / 被标签筛掉 / 被第 ③ 步排空：它已经不在这批候选里了。
    String second = sticky.select(pairOf(LIGHT, "c"), context("tenant-7"));
    String third = sticky.select(pairOf(LIGHT, "c"), context("tenant-7"));

    assertThat(first).isEqualTo(HEAVY);
    assertThat(second).as("重挑的结果").isEqualTo("c");
    assertThat(third).as("重挑之后仍然粘着").isEqualTo("c");
    assertThat(delegate.calls()).as("委托的次数：第三次应当命中记忆").isEqualTo(2);
  }

  @Test
  @DisplayName("认不出这次调用算谁的：拒绝，而不是退化成每次换一个实例")
  void missingKeyIsRejectedRatherThanDegraded() {
    InstanceSelector sticky = sticky(new RoundRobinSelector(), 16);

    assertThatThrownBy(() -> sticky.select(candidates(), context(null)))
        .isInstanceOf(CallerIdentityMissingException.class)
        // 退化成「每次换一个实例」的后果是：粘滞开着，一部分会话却被拆着走，且没有任何一行日志说这件事。
        .hasMessageContaining("调用方标识");
  }

  @Test
  @DisplayName("空白的调用方标识同样是缺失：它是省略，不是另一个键")
  void blankCallerIsMissingToo() {
    InstanceSelector sticky = sticky(new RoundRobinSelector(), 16);

    assertThatThrownBy(() -> sticky.select(candidates(), context("   ")))
        .isInstanceOf(CallerIdentityMissingException.class);
  }

  @Test
  @DisplayName("自定义键优先于调用方标识，取不到时才退回它")
  void customKeyWinsAndCallerIdentityIsTheFallback() {
    SessionKey keys = new SessionKey();
    InstanceSelector sticky = sticky(new RoundRobinSelector(), keys, 16);
    SelectionContext context = context("tenant-7");

    String byCaller = sticky.select(candidates(), context);
    keys.session("session-a");
    String bySessionA = sticky.select(candidates(), context);
    keys.session("session-b");
    sticky.select(candidates(), context);
    keys.session(null);

    assertThat(bySessionA).as("同一个调用方换了一个会话键，就不再是那个实例")
        .isNotEqualTo(byCaller);
    assertThat(sticky.select(candidates(), context))
        .as("自定义键取不到时退回调用方标识：粘回它原来那个")
        .isEqualTo(byCaller);
  }

  @Test
  @DisplayName("表有界：满了挤掉最久没被用过的那个键，而不是随机掉一个")
  void tableIsBoundedByCapacity() {
    FixedSequence delegate = new FixedSequence(HEAVY, LIGHT, HEAVY, LIGHT, HEAVY);
    InstanceSelector sticky = sticky(delegate, 2);

    assertThat(sticky.select(candidates(), context("k1"))).isEqualTo(HEAVY);
    assertThat(sticky.select(candidates(), context("k2"))).isEqualTo(LIGHT);
    // 命中会把 k1 刷成最近用过的那一个：下一个被挤掉的因此是 k2，而不是随便哪一个。
    assertThat(sticky.select(candidates(), context("k1"))).isEqualTo(HEAVY);
    assertThat(sticky.select(candidates(), context("k3"))).isEqualTo(HEAVY);

    assertThat(delegate.calls()).as("第三个键进来之后的委托次数").isEqualTo(3);
    // k2 已经被挤出去：它得重新挑一次（委托的计数因此前进）。
    assertThat(sticky.select(candidates(), context("k2"))).isEqualTo(LIGHT);
    assertThat(delegate.calls()).isEqualTo(4);
    // k3 还在这张表里：命中不产生委托。
    assertThat(sticky.select(candidates(), context("k3"))).isEqualTo(HEAVY);
    assertThat(delegate.calls()).isEqualTo(4);
    // k1 在第五次插入时被挤掉了：表的容量是二，三个键同时活着的时候总要掉一个。
    assertThat(sticky.select(candidates(), context("k1"))).isEqualTo(HEAVY);
    assertThat(delegate.calls()).isEqualTo(5);
  }

  @Test
  @DisplayName("容量不是正数时构造即失败：为零的表会在每一次选择上都淘汰一次")
  void nonPositiveCapacityFails() {
    assertThatThrownBy(() -> new StickySelector(new RoundRobinSelector(),
        StickyKeyResolver.callerIdentity(), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0");
  }

  private static InstanceSelector sticky(InstanceSelector delegate, int capacity) {
    return sticky(delegate, StickyKeyResolver.callerIdentity(), capacity);
  }

  private static InstanceSelector sticky(InstanceSelector delegate, StickyKeyResolver keys,
      int capacity) {
    return new StickySelector(delegate, keys, capacity);
  }

  /** 1:1 的两个候选。 */
  private static WeightedCandidates candidates() {
    return pairOf(HEAVY, LIGHT);
  }

  private static WeightedCandidates pairOf(String first, String second) {
    return WeightedCandidates.of(List.of(Candidate.of(first, 1), Candidate.of(second, 1)));
  }

  private static SelectionContext context(String callerId) {
    return SelectionContext.of(callerId);
  }

  /** 没有粘滞时同一个策略的取样：用来证明上面那几条断言不是「策略本来就稳」。 */
  private static List<String> picksWithoutSticky(InstanceSelector delegate) {
    List<String> picked = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      picked.add(delegate.select(candidates(), context("tenant-7")));
    }
    return picked;
  }

  /** 每次取值都换一个位置的随机源：没有粘滞时它会一路换实例。 */
  private static LongUnaryOperator flippingSource() {
    AtomicLong index = new AtomicLong();
    return bound -> index.getAndIncrement() % bound;
  }

  /** 依次给出固定答案并记下自己被问了几次。粘滞有没有重挑，看的就是这个计数。 */
  private static final class FixedSequence implements InstanceSelector {

    private final Iterator<String> answers;

    private int calls;

    FixedSequence(String... answers) {
      this.answers = List.of(answers).iterator();
    }

    @Override
    public String select(WeightedCandidates candidates, SelectionContext context) {
      calls++;
      return answers.next();
    }

    int calls() {
      return calls;
    }
  }

  /**
   * 自定义键优先、取不到退回调用方标识的那一份解析。
   *
   * <p>真实来源（某个请求头、会话 id）随认证（阶段 6）才有，这里由测试直接设置：
   * 键从哪来正是被替换的那件事，包装器不需要知道。
   */
  private static final class SessionKey implements StickyKeyResolver {

    private String session;

    void session(String value) {
      this.session = value;
    }

    @Override
    public Optional<String> keyOf(SelectionContext context) {
      if (session != null && !session.isBlank()) {
        return Optional.of(session);
      }
      return StickyKeyResolver.callerIdentity().keyOf(context);
    }
  }
}
