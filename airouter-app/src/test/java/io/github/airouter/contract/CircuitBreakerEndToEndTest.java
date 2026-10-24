package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.gateway.faces.openai.OpenAiFace;
import io.github.airouter.gateway.governance.CircuitBreaker;
import io.github.airouter.gateway.governance.CircuitBreakers;
import io.github.airouter.gateway.governance.CircuitState;
import io.github.airouter.gateway.observe.AvailabilityEndpoint;
import io.github.airouter.gateway.observe.SettlementObserver;
import io.github.airouter.gateway.routing.AvailabilitySnapshot;
import io.github.airouter.gateway.routing.InstanceAvailability;
import io.github.airouter.gateway.routing.InstanceHealth;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.BaseSubscriber;
import tools.jackson.databind.JsonNode;

/**
 * 熔断闭环的端到端行为：**从入站 HTTP 打进去**，上游是可控的回放，内部不 mock 任何组件。
 *
 * <p>这一票要证明的是「连续的上游失败会让熔断器真的打开，打开之后不再出网」。
 * 只读运维面不是为了好看才有的：读熔断器的字段只能证明它**记得**自己是开的，
 * 证明不了请求没有发出去 —— 这两件事在来源项目里恰恰是分开的，状态记了，请求照发。
 * 所以每条断言都是一对：状态读值 + 上游的请求计数。
 *
 * <p>每条用例开始时都先把电路恢复到闭合（见 {@code startFromClosedCircuit}），
 * 用的是生产路径而不是测试后门：等冷却过去、打一次成功的调用。少这一步，
 * 用例之间会互相污染，而污染的表现是「偶发红」 —— 那是最难归因的一种红。
 */
@SpringBootTest(classes = AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
final class CircuitBreakerEndToEndTest {

  private static final String NON_STREAM_REQUEST =
      """
      {"model":"recorded-model","messages":[{"role":"user","content":"hi"}]}
      """;

  private static final String STREAM_REQUEST =
      """
      {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":true}
      """;

  private static final String OK_BODY =
      """
      {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"recorded-model",
       "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
      """;

  private static final String UPSTREAM_5XX_BODY =
      "{\"error\":{\"message\":\"upstream is on fire\",\"type\":\"server_error\"}}";

  /** 连续失败阈值，与注册进容器的那个值一致 —— 两处必须同时改，否则断言会自己骗自己。 */
  private static final int THRESHOLD = 3;

  /** 冷却时间，同样与注册进容器的值一致。取得极短是刻意的：它让「冷却结束」不需要真的等好几秒。 */
  private static final Duration COOLDOWN = Duration.ofMillis(400);

  /** 取消用例里上游有多少帧。取一个远超消费方要看的量，才能保证取消发生在流的中途而不是末尾。 */
  private static final int CANCEL_FRAMES = 60;

  private static final RecordedUpstream UPSTREAM = startUpstream();

  @Autowired
  private SettlementObserver outcomes;

  @Autowired
  private InstanceAvailability availability;

  @Autowired
  private CircuitBreakers breakers;

  @Autowired
  private InstanceHealth health;

  /** 被测实例的标识：现阶段它就是端点地址。 */
  @Value("${airouter.endpoint.base-url}")
  private String instanceId;

  private WebTestClient client;

  private static RecordedUpstream startUpstream() {
    try {
      return new RecordedUpstream(0, 200, OK_BODY);
    } catch (IOException failure) {
      throw new IllegalStateException("回放上游起不来", failure);
    }
  }

  @DynamicPropertySource
  static void upstreamEndpoint(DynamicPropertyRegistry registry) {
    registry.add("airouter.endpoint.base-url", UPSTREAM::baseUrl);
    registry.add("airouter.endpoint.api-key", () -> "not-a-real-key");
    registry.add("airouter.endpoint.model", () -> "recorded-model");
    registry.add("airouter.endpoint.request-timeout", () -> "5s");
    registry.add("airouter.endpoint.idle-timeout", () -> "5s");
    registry.add("airouter.governance.failure-threshold", () -> String.valueOf(THRESHOLD));
    registry.add("airouter.governance.cooldown", () -> COOLDOWN.toMillis() + "ms");
  }

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(15))
        .build();
    startFromClosedCircuit();
  }

  @Test
  @DisplayName("连续的上游失败让熔断器打开：此后请求在本地快速失败，一次出网都不产生")
  void consecutiveFailuresOpenTheCircuitAndLaterCallsFailLocally() {
    UPSTREAM.replay(500, UPSTREAM_5XX_BODY);
    for (int attempt = 1; attempt <= THRESHOLD; attempt++) {
      callNonStreaming();
      assertThat(stateOf()).as("第 " + attempt + " 次失败之后的状态")
          .isEqualTo(attempt < THRESHOLD ? CircuitState.CLOSED : CircuitState.OPEN);
    }
    UPSTREAM.resetRequests();

    EntityExchangeResult<String> rejected = callNonStreaming();

    assertThat(rejected.getStatus().value()).isEqualTo(503);
    assertThat(rejected.getResponseBody()).contains("instance_unavailable");
    assertThat(UPSTREAM.requests())
        .as("本地快速失败的硬证据：上游一个请求都没收到")
        .isZero();

    String requestId = rejected.getResponseHeaders().getFirst(OpenAiFace.REQUEST_ID_HEADER);
    assertThat(outcomes.forRequest(requestId))
        .as("一次本地拒绝不是一次上游调用，因此不产生结算；否则它会继续喂熔断器，"
            + "打开之后就再也等不到冷却结束的那次成功探测")
        .isEmpty();
  }

  @Test
  @DisplayName("流式路径走的是同一个熔断器：流式失败同样让它打开，打开之后流式请求也被收住")
  void streamingFailuresOpenTheSameCircuitAndAreThenRejectedLocally() {
    UPSTREAM.replay(500, UPSTREAM_5XX_BODY);
    for (int attempt = 1; attempt <= THRESHOLD; attempt++) {
      SseCall.Arrivals arrivals = SseCall.exchange(client, STREAM_REQUEST);
      assertThat(SseCall.errorTypesOf(arrivals.payloads())).containsExactly("upstream_5xx");
    }
    assertThat(stateOf()).isEqualTo(CircuitState.OPEN);
    UPSTREAM.resetRequests();

    SseCall.Arrivals rejected = SseCall.exchange(client, STREAM_REQUEST);

    assertThat(SseCall.errorTypesOf(rejected.payloads())).containsExactly("instance_unavailable");
    assertThat(rejected.payloads()).last().isEqualTo("[DONE]");
    assertThat(UPSTREAM.requests()).isZero();
  }

  @Test
  @DisplayName("同步与流式两条路径喂的是同一个熔断器：两边加起来的失败一起把它打开")
  void bothPathsFeedTheSameCircuit() {
    UPSTREAM.replay(500, UPSTREAM_5XX_BODY);

    SseCall.exchange(client, STREAM_REQUEST);
    assertThat(stateOf()).isEqualTo(CircuitState.CLOSED);
    SseCall.exchange(client, STREAM_REQUEST);
    assertThat(stateOf()).isEqualTo(CircuitState.CLOSED);

    callNonStreaming();
    assertThat(stateOf())
        .as("两条路径的失败记在同一个计数器上：分开记的话这里还是 CLOSED")
        .isEqualTo(CircuitState.OPEN);
  }

  @Test
  @DisplayName("经过配置的冷却时间之后进入半开，一次成功的调用让它闭合")
  void afterTheConfiguredCooldownOneSuccessClosesIt() {
    open();

    awaitCooldown();
    assertThat(stateOf())
        .as("冷却过去之后是半开：它是时间推出来的事实，不需要先发一次请求来触发")
        .isEqualTo(CircuitState.HALF_OPEN);

    UPSTREAM.replay(200, OK_BODY);
    UPSTREAM.resetRequests();
    callNonStreaming();

    assertThat(UPSTREAM.requests())
        .as("半开放行了这一次探测：完全不放行的话，一个已经恢复的实例永远回不来")
        .isEqualTo(1);
    assertThat(stateOf()).isEqualTo(CircuitState.CLOSED);
  }

  @Test
  @DisplayName("客户端取消不算实例故障：反复取消同一个实例不会让它打开")
  void repeatedCancellationsNeverOpenTheCircuit() {
    for (int round = 1; round <= THRESHOLD + 2; round++) {
      UPSTREAM.replayStream(200, frames(CANCEL_FRAMES), Duration.ofMillis(20));
      String requestId = cancelOneStream();

      waitUntil(() -> !outcomes.forRequest(requestId).isEmpty(), Duration.ofSeconds(5));
      assertThat(outcomes.forRequest(requestId).get(0).kind())
          .as("第 " + round + " 轮：这一条流必须真的走到了取消那一条路径，否则下面的断言没有意义")
          .isEqualTo(OutcomeKind.CLIENT_CANCELLED);
      assertThat(stateOf()).as("第 " + round + " 次取消之后的状态").isEqualTo(CircuitState.CLOSED);
    }
    assertThat(consecutiveFailures())
        .as("取消不累计：它反映的是调用方的处境，不是实例的健康")
        .isZero();
  }

  @Test
  @DisplayName("未纳入健康管理的实例不接收流量，也不出现在运维名单上")
  void unmanagedInstanceIsUnavailableAndAbsentFromTheOpsView() {
    String stranger = "http://127.0.0.1:1/v1";

    AvailabilitySnapshot snapshot = availability.describe(stranger);

    assertThat(snapshot.managed()).isFalse();
    assertThat(snapshot.available()).isFalse();
    assertThat(snapshot.circuitState()).isNull();
    assertThat(availability.accepts(stranger, "chat"))
        .as("fail-closed：没登记过的实例一律不接流量，而不是静默放行")
        .isFalse();
    assertThat(availability.all()).extracting(AvailabilitySnapshot::instanceId)
        .doesNotContain(stranger);
  }

  @Test
  @DisplayName("运维查询面能区分异常实例与正常实例")
  void opsViewSeparatesTheBrokenInstanceFromTheHealthyOne() {
    // 第二个实例只登记、不打流量：它的熔断状态是「从未失败过」，也就是闭合。
    // 它存在的意义是让查询面的两行不是同一行 —— 一次调用就能看出哪一个是坏的。
    String healthy = "http://127.0.0.1:2/v1";
    health.enroll(healthy);

    open();

    AvailabilitySnapshot broken = snapshotOf(instanceId);
    AvailabilitySnapshot fine = snapshotOf(healthy);

    assertThat(broken.circuitState()).isEqualTo(CircuitState.OPEN);
    assertThat(broken.available()).isFalse();
    assertThat(fine.circuitState()).isEqualTo(CircuitState.CLOSED);
    assertThat(fine.available()).isTrue();
  }

  @Test
  @DisplayName("只读运维查询面：熔断状态与可用状态都能从 HTTP 上读到")
  void opsEndpointReportsCircuitStateAndAvailability() {
    open();

    String body = client.get()
        .uri(AvailabilityEndpoint.PATH)
        .exchange()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode reported = SseCall.tree(body).get(0);

    assertThat(reported.path("instanceId").asString()).isEqualTo(instanceId);
    assertThat(reported.path("managed").asBoolean()).isTrue();
    assertThat(reported.path("circuitState").asString()).isEqualTo("OPEN");
    assertThat(reported.path("available").asBoolean()).isFalse();
    assertThat(reported.path("lastTransitionRequestId").asString())
        .as("迁移的请求标识也在这里：运维看到一个开着的熔断器时，下一步要查的就是那次请求")
        .isNotBlank();
  }

  @Test
  @DisplayName("状态迁移带着请求标识：能从一个打开的动作追到触发它的那次请求")
  void transitionCarriesTheRequestIdThatTriggeredIt() {
    UPSTREAM.replay(500, UPSTREAM_5XX_BODY);
    String lastRequestId = "";
    for (int attempt = 1; attempt <= THRESHOLD; attempt++) {
      lastRequestId = callNonStreaming()
          .getResponseHeaders()
          .getFirst(OpenAiFace.REQUEST_ID_HEADER);
    }

    Optional<CircuitBreaker.Transition> transition = breaker().lastTransition();

    assertThat(transition).isPresent();
    assertThat(transition.get().to()).isEqualTo(CircuitState.OPEN);
    assertThat(transition.get().from()).isEqualTo(CircuitState.CLOSED);
    assertThat(transition.get().requestId())
        .as("「熔断器什么时候开的」能回答，但排查时有用的是「是谁把它打开的」")
        .isEqualTo(lastRequestId);
  }

  /** 连续失败到打开。三条用例共用这一段，它们要的都是「电路现在是开的」这个前提。 */
  private void open() {
    UPSTREAM.replay(500, UPSTREAM_5XX_BODY);
    for (int attempt = 1; attempt <= THRESHOLD; attempt++) {
      callNonStreaming();
    }
    assertThat(stateOf()).isEqualTo(CircuitState.OPEN);
  }

  /** 把电路恢复到闭合：等冷却过去，再打一次成功的调用。 */
  private void startFromClosedCircuit() {
    UPSTREAM.replay(200, OK_BODY);
    if (stateOf() == CircuitState.OPEN) {
      awaitCooldown();
    }
    callNonStreaming();
    assertThat(stateOf()).as("每条用例都从闭合的电路开始").isEqualTo(CircuitState.CLOSED);
    UPSTREAM.resetRequests();
  }

  private EntityExchangeResult<String> callNonStreaming() {
    return client.post()
        .uri(OpenAiFace.PATH)
        .header("Content-Type", "application/json")
        .bodyValue(NON_STREAM_REQUEST)
        .exchange()
        .expectBody(String.class)
        .returnResult();
  }

  /**
   * 开一条流，收到第一帧就断开。
   *
   * @return 这一次请求的标识，供调用方等它的结算
   */
  private String cancelOneStream() {
    FluxExchangeResult<byte[]> result = client.post()
        .uri(OpenAiFace.PATH)
        .header("Content-Type", "application/json")
        .bodyValue(STREAM_REQUEST)
        .exchange()
        .returnResult(byte[].class);
    result.getResponseBody().subscribe(new OneFrameCanceller());
    return result.getResponseHeaders().getFirst(OpenAiFace.REQUEST_ID_HEADER);
  }

  private CircuitState stateOf() {
    return availability.describe(instanceId).circuitState();
  }

  private int consecutiveFailures() {
    return breaker().consecutiveFailures();
  }

  private CircuitBreaker breaker() {
    return breakers.find(instanceId).orElseThrow(() -> new AssertionError("这个实例没有熔断器"));
  }

  private AvailabilitySnapshot snapshotOf(String id) {
    return availability.all().stream()
        .filter(snapshot -> snapshot.instanceId().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("运维名单里没有 " + id));
  }

  private static List<byte[]> frames(int count) {
    List<byte[]> frames = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      frames.add(SseCall.bytes(SseCall.deltaFrame("chatcmpl-1", "recorded-model", "字")));
    }
    return frames;
  }

  private static void awaitCooldown() {
    awaitQuietly(COOLDOWN.plusMillis(250));
  }

  private static void awaitQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException expected) {
      Thread.currentThread().interrupt();
    }
  }

  private static void waitUntil(BooleanSupplier condition, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      awaitQuietly(Duration.ofMillis(20));
    }
    throw new AssertionError("条件在 " + timeout + " 之内没有成立");
  }

  /** 收到第一帧就取消：它扮演的是一个「看了第一眼就关掉页面」的调用方。 */
  private static final class OneFrameCanceller extends BaseSubscriber<byte[]> {

    @Override
    protected void hookOnNext(byte[] value) {
      cancel();
    }
  }
}
