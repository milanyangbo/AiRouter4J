package io.github.airouter.gateway.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.core.GatewayError;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 延迟样本：**从结算点学，而不是自己去看延迟**。
 *
 * <p>它只消费 {@code Outcome}，不看状态码、不看异常形态、不认识 {@code transport}。
 * 这一点是本票最要紧的一处 —— 让 {@code select} 自己去观测延迟，
 * 会让策略带上副作用，于是「给定样本 → 结果可复现」那条验收当场不成立（ADR-0003）。
 *
 * <p>三条口径各有一条断言：**成功用实际延迟 / 失败用惩罚值 / 取消不采纳**。
 * 第三条最容易被省略，而省略它的后果是：一个爱取消的调用方会把实例永久压在最差那一档。
 *
 * <p>时钟是被注入的，于是「保留窗口过了」这件事可以被走过一遍，
 * 而不是靠真的睡上十分钟。
 */
@Tag("unit")
final class LatencySamplesTest {

  private static final String FAST = "fast";

  private static final String SLOW = "slow";

  private static final double ALPHA = 0.5d;

  private static final double PENALTY_MILLIS = 30_000d;

  private static final long RETENTION_MILLIS = 600_000L;

  /** 与保留窗口拉开足够差距，才能区分「过期了」与「还活着」。 */
  private static final long LONG_ENOUGH = 10_000L;

  private final AtomicLong clock = new AtomicLong(1_000_000L);

  private final LatencySamples samples =
      new LatencySamples(ALPHA, PENALTY_MILLIS, RETENTION_MILLIS, clock::get);

  @Test
  @DisplayName("成功的样本用实际延迟，而且第一个样本直接作为初值")
  void firstSuccessfulSampleBecomesTheValue() {
    samples.settle(outcome(FAST, OutcomeKind.SUCCESS, Duration.ofMillis(80L)));

    assertThat(samples.ewmaMillis(FAST)).isEqualTo(80d);
    assertThat(samples.instances()).containsExactly(FAST);
  }

  @Test
  @DisplayName("EWMA 是「一半新、一半旧」：第二个样本把值拉到两者之间")
  void ewmaMovesHalfwayTowardsTheNewSample() {
    samples.settle(outcome(FAST, OutcomeKind.SUCCESS, Duration.ofMillis(100L)));
    samples.settle(outcome(FAST, OutcomeKind.SUCCESS, Duration.ofMillis(200L)));

    assertThat(samples.ewmaMillis(FAST))
        .as("alpha = 0.5 时的一次更新")
        .isEqualTo(150d);
  }

  /**
   * 反例：失败没有惩罚值的话，一台正在报错的机器会因为没有延迟样本
   * 而被 {@code latency} 当成「最好的那一档」（没有历史 = 最好），
   * 于是高失败率的实例反而拿到更多流量。
   */
  @Test
  @DisplayName("失败按惩罚值记账：一次上游失败比一次慢更值得降权")
  void failuresAreRecordedAsThePenalty() {
    samples.settle(outcome(SLOW, OutcomeKind.UPSTREAM_TIMEOUT, Duration.ofMillis(5L)));

    assertThat(samples.ewmaMillis(SLOW)).isEqualTo(PENALTY_MILLIS);
  }

  @Test
  @DisplayName("取消不采纳：客户端临时不在了，那一次调用没有产生关于这个实例的观测")
  void cancellationIsNotASample() {
    samples.settle(outcome(FAST, OutcomeKind.CLIENT_CANCELLED, Duration.ofMillis(50L)));

    assertThat(samples.instances()).as("取消不该留下一条样本").isEmpty();
    assertThat(samples.ewmaMillis(FAST)).as("取消之后仍然是没有历史").isNull();
  }

  @Test
  @DisplayName("没有样本的实例返回 null：不知道与「知道它是 0」不是一回事")
  void unknownInstanceHasNoSampleRatherThanZero() {
    assertThat(samples.ewmaMillis("没有这个实例")).isNull();
  }

  /**
   * 惰性清理：一个实例不再产生结算超过保留窗口之后，它学到的东西会被丢掉。
   *
   * <p>它的红色形状是「下线的机器永远占着最差那一档」——
   * 那个值在它重新上线之后还会继续影响选择一段时间。
   */
  @Test
  @DisplayName("超过保留窗口没有新样本的实例会被清掉：下线的机器不该永远占着一档")
  void staleEntriesAreDroppedOnTheNextSettlement() {
    samples.settle(outcome(SLOW, OutcomeKind.SUCCESS, Duration.ofMillis(500L)));
    samples.settle(outcome(FAST, OutcomeKind.SUCCESS, Duration.ofMillis(20L)));

    clock.addAndGet(RETENTION_MILLIS + LONG_ENOUGH);
    // 清理发生在「又有一次结算进来」的时候：没有定时任务，于是这里要有第二次结算。
    samples.settle(outcome(FAST, OutcomeKind.SUCCESS, Duration.ofMillis(20L)));

    assertThat(samples.instances()).as("只有还在说话的那个留着").containsExactly(FAST);
    assertThat(samples.ewmaMillis(SLOW)).as("过期的条目").isNull();
  }

  /**
   * 时机被钉住的另一半：**保留窗口之内**条目活着，哪怕清理被别的结算触发了。
   *
   * <p>只有上一条的话，「每一次记账都全表清空」的实现也能绿 ——
   * 它同样满足「下线的机器最终不占档」，但把还在线的实例学到的东西也一并丢了。
   * 两条合成一对：窗口之内活着、窗口之外（下一次记账时）消失 ——
   * 这正是票 13 的裁决里「按保留窗口走、不是像粘滞那样当场除名」的那条分界线。
   */
  @Test
  @DisplayName("保留窗口之内条目不丢：清理按窗口走，不是当场除名")
  void entriesSurviveWithinTheRetentionWindow() {
    samples.settle(outcome(SLOW, OutcomeKind.SUCCESS, Duration.ofMillis(500L)));

    clock.addAndGet(RETENTION_MILLIS - LONG_ENOUGH);
    samples.settle(outcome(FAST, OutcomeKind.SUCCESS, Duration.ofMillis(20L)));

    assertThat(samples.instances()).as("窗口内的条目活着，哪怕清理被触发了")
        .containsExactlyInAnyOrder(FAST, SLOW);
    assertThat(samples.ewmaMillis(SLOW)).as("学到的值没有被动过").isEqualTo(500d);
  }

  @Test
  @DisplayName("三个参数都是显式的：非法值起不来，而不是悄悄用一个默认值")
  void illegalKnobsFailLoudly() {
    assertThatThrownBy(() -> new LatencySamples(0d, PENALTY_MILLIS, RETENTION_MILLIS))
        .as("alpha 为 0 的表永远不更新")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("(0, 1]");
    assertThatThrownBy(() -> new LatencySamples(ALPHA, 0d, RETENTION_MILLIS))
        .as("惩罚值为 0 等于把失败记成完美响应")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LatencySamples(ALPHA, PENALTY_MILLIS, 0L))
        .as("保留窗口为 0 会立刻忘掉刚学到的东西")
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Outcome outcome(String instanceId, OutcomeKind kind, Duration latency) {
    GatewayError error = kind == OutcomeKind.SUCCESS
        ? null
        : new GatewayError("upstream_problem", "上游出了问题", 502);
    return new Outcome("request-of-" + instanceId, "chat", instanceId, kind, latency, null, error);
  }
}
