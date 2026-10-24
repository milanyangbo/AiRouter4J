package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.GenerationParams;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.gateway.observe.RecordingOutcomeObserver;
import io.github.airouter.gateway.providers.ChunkParseCounter;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.transport.InFlightStreams;
import io.github.airouter.gateway.transport.OutboundWire;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.BaseSubscriber;

/**
 * 「一条流恰好结算一次」这条约束的定向验证。
 *
 * <p>它**没有走入站 HTTP**：要构造的是「终态到达的同一瞬间调用方断开」，
 * 而隔着一层 HTTP，断开到达网关的时刻由网络与线程调度决定，断言会变成竞态。
 * 项目里其余用例都从 HTTP 边界打进去（见 spec 的 seam 选择），这一条是明确的例外，
 * 理由就写在上面：它要钉住的是**结算点的行为**，而结算点在 Provider 这一层结束。
 *
 * <p>被钉住的情形不是想象出来的：客户端在收到带 {@code finish_reason} 的那一帧之后
 * 立刻关闭连接，在生产上每分钟都在发生（答案显示完整了，用户就关了页面）。
 * 那一刻同时存在两条结算路径 —— 终态事件与取消信号 —— 少一道守卫就是两条记录，
 * 表现为一次调用被计成两次，而这类错误不报错，只会让指标与熔断阈值慢慢失真。
 */
final class ExactlyOneSettlementTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final String REQUEST_ID = "settled-once";

  @Test
  @DisplayName("终态到达的同一瞬间调用方断开：只结算一次，且记的是成功而不是取消")
  void disconnectingRightAfterTheTerminalStillSettlesOnce() throws IOException {
    try (RecordedUpstream upstream = new RecordedUpstream(0, 200, "{}")) {
      upstream.replayStream(200, List.of(
          SseCall.bytes(SseCall.deltaFrame("chatcmpl-1", "recorded-model", "你")),
          SseCall.bytes(SseCall.stopFrame("chatcmpl-1", "recorded-model")),
          SseCall.bytes(SseCall.doneFrame())), Duration.ZERO);

      RecordingOutcomeObserver outcomes = new RecordingOutcomeObserver();
      OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
          new OutboundWire(upstream.baseUrl(), TIMEOUT, TIMEOUT, new InFlightStreams()),
          "golden-key",
          "recorded-model",
          upstream.baseUrl(),
          outcomes,
          new ChunkParseCounter());

      CancelOnTerminal subscriber = new CancelOnTerminal();
      JdkFlowAdapter.flowPublisherToFlux(provider.stream(request())).subscribe(subscriber);

      assertThat(subscriber.awaitCancel(Duration.ofSeconds(5))).isTrue();

      List<Outcome> settled = outcomes.forRequest(REQUEST_ID);
      assertThat(settled).as("一次调用结算了几次").hasSize(1);
      assertThat(settled.get(0).kind()).isEqualTo(OutcomeKind.SUCCESS);
      assertThat(settled.get(0).error()).isNull();
    }
  }

  private static CanonicalRequest request() {
    return new CanonicalRequest(
        REQUEST_ID,
        "chat",
        "recorded-model",
        List.of(new CanonicalMessage(CanonicalRole.USER, "hi")),
        List.of(),
        GenerationParams.EMPTY,
        true,
        Map.of());
  }

  /** 一收到终态就立刻断开的消费方。它模拟的是「答案显示完整了，用户就关了页面」。 */
  private static final class CancelOnTerminal extends BaseSubscriber<CanonicalStreamEvent> {

    private final CountDownLatch cancelled = new CountDownLatch(1);

    private Subscription subscription;

    @Override
    protected void hookOnSubscribe(Subscription upstream) {
      subscription = upstream;
      requestUnbounded();
    }

    @Override
    protected void hookOnNext(CanonicalStreamEvent event) {
      if (event instanceof CanonicalStreamEvent.Terminal) {
        subscription.cancel();
        cancelled.countDown();
      }
    }

    boolean awaitCancel(Duration timeout) {
      try {
        return cancelled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException expected) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }
}
