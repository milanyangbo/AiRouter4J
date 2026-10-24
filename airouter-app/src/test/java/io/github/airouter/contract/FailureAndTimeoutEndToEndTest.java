package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.gateway.observe.SettlementObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;

/**
 * 失败与超时这两类结算的端到端行为：**从入站 HTTP 打进去**，内部不 mock 任何组件。
 *
 * <p>这一票要回答的是「一次调用没能正常结束时，调用方与结算点分别看到了什么」，
 * 而答案必须逐条分开给：上游明确拒绝、上游够不着、上游不说话，三种的对外形态相似，
 * 但**归因完全不同** —— 前两种都算上游的账，第三种是网关自己判的。
 * 混在一起的结果是调用方只能看到一个「失败了」，而熔断器那边少了一整类输入。
 *
 * <p>每一条用例都要顺带断言「这一条路径恰好结算一次」：终态唯一不是流式的专属性质，
 * 非流式同样可能因为异常路径走两次（一次在 Provider，一次在 Face）。
 */
@SpringBootTest(classes = AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class FailureAndTimeoutEndToEndTest {

  private static final String STREAM_REQUEST =
      """
      {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":true}
      """;

  private static final String NON_STREAM_REQUEST =
      """
      {"model":"recorded-model","messages":[{"role":"user","content":"hi"}]}
      """;

  /** 上游 4xx 的响应体，形态与真实 OpenAI 兼容端点一致。 */
  private static final String UPSTREAM_ERROR_BODY =
      "{\"error\":{\"message\":\"model 'recorded-model' not found\","
          + "\"type\":\"invalid_request_error\",\"param\":\"model\",\"code\":\"model_not_found\"}}";

  /** 帧间静默的判定阈值。取得极小是刻意的：它让「上游不说话」不需要真的等好几秒。 */
  private static final Duration IDLE_TIMEOUT = Duration.ofMillis(400);

  private static final RecordedUpstream UPSTREAM = startUpstream();

  @Autowired
  private SettlementObserver outcomes;

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
    registry.add("airouter.endpoint.request-timeout", () -> "5s");
    registry.add("airouter.endpoint.idle-timeout", () -> "400ms");
    // 熔断的阈值在这里调到打不开：这些用例要的是「失败的归类与形状」，
    // 而上游失败在它们这里是**被测行为**，不是熔断的输入。
    // 少了这一行，同一个类里的几条失败会累计到阈值，于是后面某条用例收到的是
    // instance_unavailable —— 表现为一条与顺序有关的偶发红。
    registry.add("airouter.governance.failure-threshold", () -> "1000");
  }

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(15))
        .build();
  }

  @Test
  @DisplayName("上游 4xx：调用方在 [DONE] 之前收到错误帧，且能区分出这是「上游拒绝了」")
  void upstreamRejectionArrivesAsAnErrorFrameBeforeDone() {
    UPSTREAM.replay(404, UPSTREAM_ERROR_BODY);

    SseCall.Arrivals arrivals = SseCall.exchange(client, STREAM_REQUEST);

    assertThat(arrivals.payloads()).hasSize(2).last().isEqualTo("[DONE]");
    assertThat(SseCall.errorTypesOf(arrivals.payloads())).containsExactly("upstream_4xx");
    assertThat(SseCall.concatenatedTextOf(arrivals.payloads())).isEmpty();

    Outcome outcome = onlyOutcome(arrivals);
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
    assertThat(outcome.error().type()).isEqualTo("upstream_4xx");
    assertThat(outcome.error().httpStatus()).isEqualTo(404);
    assertThat(outcome.error().message()).contains("404", "not found");
  }

  @Test
  @DisplayName("上游接了请求但始终不说话：超时结算为 UPSTREAM_TIMEOUT，且这条流仍然有终态")
  void silenceSettlesAsUpstreamTimeoutEvenThoughNothingWasEverSaid() {
    UPSTREAM.holdOpen(200, Duration.ofSeconds(3));

    SseCall.Arrivals arrivals = SseCall.exchange(client, STREAM_REQUEST);

    assertThat(SseCall.errorTypesOf(arrivals.payloads())).containsExactly("upstream_timeout");
    assertThat(arrivals.payloads()).last().isEqualTo("[DONE]");

    Outcome outcome = onlyOutcome(arrivals);
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.UPSTREAM_TIMEOUT);
    assertThat(outcome.error().type()).isEqualTo("upstream_timeout");
    assertThat(outcome.latency()).isGreaterThanOrEqualTo(IDLE_TIMEOUT.dividedBy(2));
  }

  @Test
  @DisplayName("非流式与流式共用同一套错误结构：不存在「只有流式才有这个形状」")
  void nonStreamingFailureUsesTheSameErrorShapeAsStreaming() {
    UPSTREAM.replay(404, UPSTREAM_ERROR_BODY);
    SseCall.Arrivals streamed = SseCall.exchange(client, STREAM_REQUEST);

    UPSTREAM.replay(404, UPSTREAM_ERROR_BODY);
    EntityExchangeResult<String> plain = client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(NON_STREAM_REQUEST)
        .exchange()
        .expectBody(String.class)
        .returnResult();

    assertThat(plain.getStatus().value()).isEqualTo(404);
    assertThat(plain.getResponseHeaders().getFirst("X-Airouter-Request-Id")).isNotBlank();

    JsonNode streamedError = SseCall.tree(streamed.payloads().get(0));
    JsonNode plainError = SseCall.tree(plain.getResponseBody());
    assertThat(keysOf(streamedError)).isEqualTo(keysOf(plainError));
    assertThat(keysOf(streamedError.path("error"))).isEqualTo(keysOf(plainError.path("error")));
    assertThat(streamedError.path("error").path("type").asString())
        .isEqualTo(plainError.path("error").path("type").asString());

    String requestId = plain.getResponseHeaders().getFirst("X-Airouter-Request-Id");
    List<Outcome> settled = outcomes.forRequest(requestId);
    assertThat(settled).as("非流式一次调用结算了几次").hasSize(1);
    assertThat(settled.get(0).kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
  }

  /**
   * 这一次请求结算了什么。
   *
   * <p>它同时是「恰好结算一次」那条断言的所在：按请求标识挑而不是看总数，
   * 是因为总数会被别的用例干扰，而看最近一次又发现不了多出来的那一条。
   */
  private Outcome onlyOutcome(SseCall.Arrivals arrivals) {
    assertThat(arrivals.requestIdHeader()).isNotBlank();
    List<Outcome> settled = outcomes.forRequest(arrivals.requestIdHeader());
    assertThat(settled).as("请求 " + arrivals.requestIdHeader() + " 的结算次数").hasSize(1);
    return settled.get(0);
  }

  /** 一个对象的键集合。用它比「两边都是同一段 JSON」弱，但比「比整段文本」强：形状相同即结构相同。 */
  private static Set<String> keysOf(JsonNode node) {
    Set<String> keys = new LinkedHashSet<>();
    for (String key : node.propertyNames()) {
      keys.add(key);
    }
    return Set.copyOf(keys);
  }
}
