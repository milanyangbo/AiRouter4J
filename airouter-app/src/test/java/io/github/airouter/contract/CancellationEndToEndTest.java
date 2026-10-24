package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.gateway.faces.openai.OpenAiFace;
import io.github.airouter.gateway.observe.RecordingOutcomeObserver;
import io.github.airouter.gateway.transport.InFlightStreams;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.BaseSubscriber;

/**
 * 取消与背压这两件事的端到端行为：**从入站 HTTP 打进去**，消费方由测试自己扮演。
 *
 * <p>这一票里唯一需要自己扮演消费方的就是这两个用例，原因是它们要的都不是「流走完之后的结果」，
 * 而是「流还没走完时的中间状态」：调用方先断开，或者调用方不往下读。
 * 其余用例都交给 {@link SseCall#exchange} 读到终态即可。
 *
 * <p>被断言的三件事分别对应三条不同性质的风险：
 * <ul>
 *   <li>取消必须结算成 {@code CLIENT_CANCELLED} —— 否则它会被算成上游故障，
 *       而移动端切后台每分钟都在产生取消；
 *   <li>取消之后在途的那条流必须收回来 —— 残留不体现在任何响应里，只能靠计数看；
 *   <li>不读取的消费方不能让网关把整条流吃进内存 —— 这是无界缓冲最典型的形态。
 * </ul>
 */
@SpringBootTest(classes = AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class CancellationEndToEndTest {

  private static final String STREAM_REQUEST =
      """
      {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":true}
      """;

  /** 慢消费用例里上游有多少帧。取一个远超任何缓冲区大小的量，才能让「卡住」是可判别的。 */
  private static final int SLOW_CONSUMER_FRAMES = 60000;

  private static final RecordedUpstream UPSTREAM = startUpstream();

  @Autowired
  private RecordingOutcomeObserver outcomes;

  @Autowired
  private InFlightStreams inFlight;

  private WebTestClient client;

  private static RecordedUpstream startUpstream() {
    try {
      return new RecordedUpstream(0, 200, "{}");
    } catch (IOException failure) {
      throw new IllegalStateException("回放上游起不来", failure);
    }
  }

  @DynamicPropertySource
  static void upstreamEndpoint(DynamicPropertyRegistry registry) {
    registry.add("airouter.endpoint.base-url", UPSTREAM::baseUrl);
    registry.add("airouter.endpoint.api-key", () -> "not-a-real-key");
    registry.add("airouter.endpoint.model", () -> "recorded-model");
    registry.add("airouter.endpoint.request-timeout", () -> "10s");
    registry.add("airouter.endpoint.idle-timeout", () -> "10s");
  }

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(20))
        .build();
  }

  @AfterAll
  static void stopUpstream() {
    UPSTREAM.close();
  }

  @Test
  @DisplayName("调用方中途断开：结算为 Cancellation，网关不再从上游取数据，在途的那条流收回来")
  void clientDisconnectSettlesAsCancellationAndStopsConsumingUpstream() {
    UPSTREAM.replayStream(200, frames(200), Duration.ofMillis(30));
    UPSTREAM.resetWrittenChunks();

    long openedBefore = inFlight.opened();
    FluxExchangeResult<byte[]> result = openStream();
    String requestId = result.getResponseHeaders().getFirst(OpenAiFace.REQUEST_ID_HEADER);
    OneFrameSubscriber subscriber = new OneFrameSubscriber();
    result.getResponseBody().subscribe(subscriber);
    assertThat(subscriber.awaitFirstFrame(Duration.ofSeconds(10))).isTrue();

    // 开出发生在订阅那一刻，因此基线要在发起请求之前取；收回发生在取消之后，基线在取消之前取。
    long closedBefore = inFlight.closed();
    int beforeCancel = UPSTREAM.writtenChunks();
    sleep(Duration.ofMillis(250));
    assertThat(UPSTREAM.writtenChunks())
        .as("断开之前，网关还在往下推：这说明上游确实还有话要说")
        .isGreaterThan(beforeCancel);

    subscriber.cancel();

    waitUntil(() -> inFlight.inFlight() == 0, Duration.ofSeconds(5));
    assertThat(inFlight.opened() - openedBefore).isEqualTo(1);
    assertThat(inFlight.closed() - closedBefore).isEqualTo(1);

    sleep(Duration.ofMillis(400));
    int afterCancel = UPSTREAM.writtenChunks();
    sleep(Duration.ofMillis(800));
    assertThat(UPSTREAM.writtenChunks())
        .as("断开之后回放停在原处：网关不再从上游取任何东西")
        .isEqualTo(afterCancel);
    assertThat(subscriber.frames()).isEqualTo(1);

    waitUntil(() -> !outcomes.forRequest(requestId).isEmpty(), Duration.ofSeconds(5));
    List<Outcome> settled = outcomes.forRequest(requestId);
    assertThat(settled).as("一次取消结算了几次").hasSize(1);
    assertThat(settled.get(0).kind()).isEqualTo(OutcomeKind.CLIENT_CANCELLED);
    assertThat(settled.get(0).error().type()).isEqualTo("client_cancelled");
    assertThat(settled.get(0).error().httpStatus())
        .as("取消没有一个该给客户端的状态码：接收方已经不在了")
        .isNull();
  }

  @Test
  @DisplayName("重复取消若干次：在途条数每次都回到零，不随次数增长")
  void repeatedCancellationsDoNotLeaveResidue() {
    long openedBefore = inFlight.opened();
    long closedBefore = inFlight.closed();

    for (int round = 1; round <= 5; round++) {
      UPSTREAM.replayStream(200, frames(60), Duration.ofMillis(20));
      FluxExchangeResult<byte[]> result = openStream();
      OneFrameSubscriber subscriber = new OneFrameSubscriber();
      result.getResponseBody().subscribe(subscriber);
      assertThat(subscriber.awaitFirstFrame(Duration.ofSeconds(10))).isTrue();
      subscriber.cancel();

      waitUntil(() -> inFlight.inFlight() == 0, Duration.ofSeconds(5));
      assertThat(inFlight.inFlight()).as("第 " + round + " 次取消之后的在途条数").isEqualTo(0);
    }

    assertThat(inFlight.opened() - openedBefore).isEqualTo(5);
    assertThat(inFlight.closed() - closedBefore).isEqualTo(5);
    assertThat(inFlight.inFlight()).isZero();
  }

  @Test
  @DisplayName("不往下读的消费方：网关侧积压有上界，不会把整条流吃进内存")
  void slowConsumerDoesNotCreateUnboundedBacklog() {
    UPSTREAM.replayStream(200, frames(SLOW_CONSUMER_FRAMES), Duration.ZERO);
    UPSTREAM.resetWrittenChunks();

    FluxExchangeResult<byte[]> result = openStream();
    OneFrameSubscriber subscriber = new OneFrameSubscriber();
    result.getResponseBody().subscribe(subscriber);
    assertThat(subscriber.awaitFirstFrame(Duration.ofSeconds(10))).isTrue();

    sleep(Duration.ofMillis(600));
    int first = UPSTREAM.writtenChunks();
    sleep(Duration.ofMillis(1000));
    int second = UPSTREAM.writtenChunks();

    assertThat(second)
        .as("消费方不再要数据之后，回放停住了 —— 缓冲区有上界的直接证据")
        .isEqualTo(first);
    assertThat(first)
        .as("停住的位置远在整条流结束之前：网关没有把上游全部吃进来")
        .isLessThan(SLOW_CONSUMER_FRAMES / 4);
    assertThat(subscriber.frames()).isEqualTo(1);

    subscriber.cancel();
  }

  /**
   * 发起一次流式请求，只拿到响应头与响应体的 {@code Flux}。
   *
   * <p>与 {@link SseCall#exchange} 的区别是**不读到底**：流还开着的时候正是这两个用例要看的状态。
   */
  private FluxExchangeResult<byte[]> openStream() {
    return client.post()
        .uri(OpenAiFace.PATH)
        .header("Content-Type", "application/json")
        .bodyValue(STREAM_REQUEST)
        .exchange()
        .returnResult(byte[].class);
  }

  private static List<byte[]> frames(int count) {
    List<byte[]> frames = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      frames.add(SseCall.bytes(SseCall.deltaFrame("chatcmpl-1", "recorded-model", "字")));
    }
    return frames;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException expected) {
      Thread.currentThread().interrupt();
    }
  }

  /** 等到条件成立。没有等待库的依赖，而等待必须发生在断言之前，否则断言的是竞态的结果。 */
  private static void waitUntil(BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep(Duration.ofMillis(50));
    }
  }

  /**
   * 一个只要一帧、然后再也不往下要的消费方。
   *
   * <p>它是「慢消费方」最容易被忽略的一种形态：它没有说不想要，也没有断开，
   * 它只是没有再要。服务端对这两种的处置必须是一样的 —— 生产环境里这就是
   * 消费端 GC 停顿、下游限流、用户切后台时的样子。
   */
  private static final class OneFrameSubscriber extends BaseSubscriber<byte[]> {

    private final CountDownLatch firstFrame = new CountDownLatch(1);

    private final AtomicInteger frames = new AtomicInteger();

    @Override
    protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
      request(1);
    }

    @Override
    protected void hookOnNext(byte[] value) {
      frames.incrementAndGet();
      firstFrame.countDown();
    }

    boolean awaitFirstFrame(Duration timeout) {
      try {
        return firstFrame.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException expected) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    int frames() {
      return frames.get();
    }
  }
}
