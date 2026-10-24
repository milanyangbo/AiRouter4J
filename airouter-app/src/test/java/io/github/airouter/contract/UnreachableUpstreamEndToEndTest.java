package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.gateway.observe.RecordingOutcomeObserver;
import java.io.IOException;
import java.net.ServerSocket;
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
 * 上游够不着时的端到端行为：**从入站 HTTP 打进去**，上游是一个没人监听的端口。
 *
 * <p>它与「上游返回 4xx」在调用方看来都是一次失败，但**性质相反**：
 * 前者是上游明确地拒绝了这次请求（请求本身有问题），后者是上游连话都没机会说。
 * 区分不了这两者的后果不是难排查，而是归因错：前者会被计入上游的账，
 * 而后者在实例不健康时要算到实例头上。
 *
 * <p>这一票单独开一个类而不是塞进 {@link FailureAndTimeoutEndToEndTest}，
 * 是因为「上游在哪」是容器级的属性：它不是一次请求能改的东西，
 * 想换就只能换一个上下文。这也顺带说明了一件事 —— 端点地址必须是可配置的，
 * 而不是写在代码里的常量。
 */
@SpringBootTest(classes = AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class UnreachableUpstreamEndToEndTest {

  private static final String STREAM_REQUEST =
      """
      {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":true}
      """;

  private static final String NON_STREAM_REQUEST =
      """
      {"model":"recorded-model","messages":[{"role":"user","content":"hi"}]}
      """;

  private static final int DEAD_PORT = nobodyListeningOn();

  @Autowired
  private RecordingOutcomeObserver outcomes;

  private WebTestClient client;

  /**
   * 一个确定没有人在监听的端口。
   *
   * <p>取「bind 到 0 再立刻关掉」而不是写一个看起来没人用的固定端口号：
   * 后者在有人真的用了那个端口时会变成一次成功的连接，而这次用例就会以
   * 「上游拒绝」的形式绿着过去 —— 那正是它要区分开的那件事。
   */
  private static int nobodyListeningOn() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException failure) {
      throw new IllegalStateException("找不到一个没人监听的端口", failure);
    }
  }

  @DynamicPropertySource
  static void upstreamEndpoint(DynamicPropertyRegistry registry) {
    registry.add("airouter.endpoint.base-url", () -> "http://127.0.0.1:" + DEAD_PORT + "/v1");
    registry.add("airouter.endpoint.api-key", () -> "not-a-real-key");
    registry.add("airouter.endpoint.model", () -> "recorded-model");
    registry.add("airouter.endpoint.request-timeout", () -> "5s");
    registry.add("airouter.endpoint.idle-timeout", () -> "5s");
  }

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(15))
        .build();
  }

  @Test
  @DisplayName("上游够不着：流式路径在 [DONE] 之前给出 upstream_unreachable，与 upstream_4xx 可区分")
  void streamingUnreachableIsDistinguishableFromUpstreamRejection() {
    SseCall.Arrivals arrivals = SseCall.exchange(client, STREAM_REQUEST);

    assertThat(arrivals.payloads()).last().isEqualTo("[DONE]");
    assertThat(SseCall.errorTypesOf(arrivals.payloads())).containsExactly("upstream_unreachable");

    Outcome outcome = onlyOutcome(arrivals.requestIdHeader());
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
    assertThat(outcome.error().type()).isEqualTo("upstream_unreachable");
    assertThat(outcome.error().httpStatus()).isEqualTo(502);
  }

  @Test
  @DisplayName("上游够不着：非流式路径给出的是同一套错误结构，只是状态码不同")
  void nonStreamingUnreachableUsesTheSameErrorShape() {
    SseCall.Arrivals streamed = SseCall.exchange(client, STREAM_REQUEST);
    EntityExchangeResult<String> plain = client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(NON_STREAM_REQUEST)
        .exchange()
        .expectBody(String.class)
        .returnResult();

    assertThat(plain.getStatus().value()).isEqualTo(502);
    JsonNode streamedError = SseCall.tree(streamed.payloads().get(0));
    JsonNode plainError = SseCall.tree(plain.getResponseBody());
    assertThat(keysOf(streamedError)).isEqualTo(keysOf(plainError));
    assertThat(keysOf(streamedError.path("error"))).isEqualTo(keysOf(plainError.path("error")));

    Outcome outcome = onlyOutcome(
        plain.getResponseHeaders().getFirst("X-Airouter-Request-Id"));
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
    assertThat(outcome.error().type()).isEqualTo("upstream_unreachable");
  }

  private Outcome onlyOutcome(String requestId) {
    assertThat(requestId).isNotBlank();
    List<Outcome> settled = outcomes.forRequest(requestId);
    assertThat(settled).as("请求 " + requestId + " 的结算次数").hasSize(1);
    return settled.get(0);
  }

  private static Set<String> keysOf(JsonNode node) {
    Set<String> keys = new LinkedHashSet<>();
    for (String key : node.propertyNames()) {
      keys.add(key);
    }
    return Set.copyOf(keys);
  }
}
