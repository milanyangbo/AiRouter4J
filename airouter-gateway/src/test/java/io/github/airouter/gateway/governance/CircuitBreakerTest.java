package io.github.airouter.gateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.core.GatewayError;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 熔断器的状态迁移。
 *
 * <p>时间在用例里是**作为参数传进去**的，不是真的等：冷却多久才能转半开这件事，
 * 用真的等待来验证的话，这类用例要么慢得没人跑，要么因为机器忙而偶发红。
 * 这也是 {@link CircuitBreaker#record} 把时刻收在签名里而不是自己取系统时间的原因。
 */
@Tag("unit")
final class CircuitBreakerTest {

  private static final int THRESHOLD = 3;

  private static final Duration COOLDOWN = Duration.ofSeconds(30);

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  private static final String INSTANCE = "http://127.0.0.1:11434/v1";

  @Test
  @DisplayName("失败不到阈值时保持闭合，够到阈值时打开")
  void opensWhenFailuresReachTheThreshold() {
    CircuitBreaker breaker = breaker();

    for (int attempt = 1; attempt < THRESHOLD; attempt++) {
      breaker.record(failure("req-" + attempt), START.plusSeconds(attempt));
      assertThat(breaker.state(START.plusSeconds(attempt))).isEqualTo(CircuitState.CLOSED);
    }
    breaker.record(failure("req-3"), START.plusSeconds(3));

    assertThat(breaker.state(START.plusSeconds(3))).isEqualTo(CircuitState.OPEN);
    assertThat(breaker.allowsCall(START.plusSeconds(3))).isFalse();
  }

  @Test
  @DisplayName("冷却过去之后是半开而不是继续打开：不放行的话一个恢复了的实例永远回不来")
  void becomesHalfOpenAfterTheCooldown() {
    CircuitBreaker breaker = opened();

    assertThat(breaker.state(START.plus(COOLDOWN).minusSeconds(1))).isEqualTo(CircuitState.OPEN);
    assertThat(breaker.state(START.plus(COOLDOWN))).isEqualTo(CircuitState.HALF_OPEN);
    assertThat(breaker.allowsCall(START.plus(COOLDOWN))).isTrue();
  }

  @Test
  @DisplayName("半开时一次成功闭合，一次失败重新打开")
  void halfOpenResolvesInEitherDirection() {
    CircuitBreaker closed = opened();
    closed.record(success("req-ok"), START.plus(COOLDOWN));
    assertThat(closed.state(START.plus(COOLDOWN))).isEqualTo(CircuitState.CLOSED);
    assertThat(closed.consecutiveFailures()).isZero();

    CircuitBreaker reopened = opened();
    reopened.record(failure("req-bad"), START.plus(COOLDOWN));
    assertThat(reopened.state(START.plus(COOLDOWN))).isEqualTo(CircuitState.OPEN);
  }

  @Test
  @DisplayName("客户端取消不计入：反复取消不会让它打开")
  void cancellationIsNotAFailure() {
    CircuitBreaker breaker = breaker();

    for (int round = 0; round < THRESHOLD * 3; round++) {
      breaker.record(cancelled("req-" + round), START.plusSeconds(round));
    }

    assertThat(breaker.state(START.plusSeconds(9))).isEqualTo(CircuitState.CLOSED);
    assertThat(breaker.consecutiveFailures()).isZero();
    assertThat(breaker.lastTransition()).isEmpty();
  }

  @Test
  @DisplayName("一次成功把连续失败计数归零：故障后面的那次成功必须真的算数")
  void successResetsTheCounter() {
    CircuitBreaker breaker = breaker();

    breaker.record(failure("req-1"), START);
    breaker.record(failure("req-2"), START.plusSeconds(1));
    breaker.record(success("req-3"), START.plusSeconds(2));
    breaker.record(failure("req-4"), START.plusSeconds(3));

    assertThat(breaker.state(START.plusSeconds(3))).isEqualTo(CircuitState.CLOSED);
    assertThat(breaker.consecutiveFailures()).isEqualTo(1);
  }

  @Test
  @DisplayName("迁移带着请求标识：一次打开能追到触发它的那次请求")
  void transitionCarriesTheRequestId() {
    CircuitBreaker breaker = breaker();

    breaker.record(failure("req-1"), START);
    breaker.record(failure("req-2"), START.plusSeconds(1));
    breaker.record(failure("req-3-opened"), START.plusSeconds(2));

    CircuitBreaker.Transition transition = breaker.lastTransition().orElseThrow();

    assertThat(transition.from()).isEqualTo(CircuitState.CLOSED);
    assertThat(transition.to()).isEqualTo(CircuitState.OPEN);
    assertThat(transition.requestId()).isEqualTo("req-3-opened");
    assertThat(transition.at()).isEqualTo(START.plusSeconds(2));
  }

  @Test
  @DisplayName("并发的失败不会互相覆盖：够到阈值之后一定是打开的")
  void concurrentFailuresStillOpen() throws InterruptedException {
    CircuitBreaker breaker = breaker();
    int threads = 8;
    int perThread = 5;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<>();

    for (int t = 0; t < threads; t++) {
      int index = t;
      Thread worker = new Thread(() -> {
        ready.countDown();
        try {
          go.await();
        } catch (InterruptedException expected) {
          Thread.currentThread().interrupt();
          return;
        }
        for (int i = 0; i < perThread; i++) {
          breaker.record(failure("req-" + index + "-" + i), START.plusSeconds(i));
        }
      }, "breaker-" + t);
      worker.start();
      workers.add(worker);
    }

    ready.await();
    go.countDown();
    for (Thread worker : workers) {
      worker.join();
    }

    assertThat(breaker.state(START)).isEqualTo(CircuitState.OPEN);
    assertThat(breaker.consecutiveFailures())
        .as("每一次失败都被记下来了：丢一半的话这个数会小于总数")
        .isEqualTo(threads * perThread);
  }

  private static CircuitBreaker breaker() {
    return new CircuitBreaker(INSTANCE, new AvailabilityPolicy(THRESHOLD, COOLDOWN));
  }

  /** 一个已经打开的熔断器，打开的时刻是 {@link #START}。 */
  private static CircuitBreaker opened() {
    CircuitBreaker breaker = breaker();
    for (int attempt = 0; attempt < THRESHOLD; attempt++) {
      breaker.record(failure("req-" + attempt), START);
    }
    assertThat(breaker.state(START)).isEqualTo(CircuitState.OPEN);
    return breaker;
  }

  private static Outcome failure(String requestId) {
    return new Outcome(requestId, "chat", INSTANCE, OutcomeKind.UPSTREAM_FAILURE,
        Duration.ofMillis(10), null, new GatewayError("upstream_5xx", "boom", 502));
  }

  private static Outcome success(String requestId) {
    return new Outcome(requestId, "chat", INSTANCE, OutcomeKind.SUCCESS,
        Duration.ofMillis(10), null, null);
  }

  private static Outcome cancelled(String requestId) {
    return new Outcome(requestId, "chat", INSTANCE, OutcomeKind.CLIENT_CANCELLED,
        Duration.ofMillis(10), null,
        new GatewayError("client_cancelled", "调用方断开了", null));
  }
}
