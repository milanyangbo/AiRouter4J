package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.gateway.providers.ChunkParseCounter;
import io.github.airouter.gateway.observe.RecordingOutcomeObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 流式路径的端到端行为：**从入站 HTTP 打进去**，内部不 mock 任何组件。
 *
 * <p>被固定下来的只有远端那个端点 —— 而且固定到字节块一级：
 * 连「一个多字节字符被切在两个 TCP 段之间」这种样本都能造出来。
 * 真实端点给不出这种样本，而它恰恰是迁移期最容易出错、又最难在日志里留下痕迹的一类。
 *
 * <p>这里断言的都是外部可见的东西：到达了几帧、帧的先后顺序、最后一帧是什么、
 * 以及结算点看到的延迟与类别。
 */
@SpringBootTest(classes = AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class StreamingEndToEndTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Duration BETWEEN_CHUNKS = Duration.ofMillis(80);

  private static final String REQUEST =
      """
      {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":true}
      """;

  private static final RecordedUpstream UPSTREAM = startUpstream();

  @Autowired
  private RecordingOutcomeObserver outcomes;

  @Autowired
  private ChunkParseCounter parseCounter;

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
    registry.add("airouter.endpoint.idle-timeout", () -> "2s");
  }

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  @DisplayName("流式请求一帧一帧地到达，而不是等上游说完了一次性收到一个整体")
  void framesArriveOneByOneWhileTheStreamIsStillOpen() {
    UPSTREAM.replayStream(200, List.of(
        bytes(deltaFrame("你")),
        bytes(deltaFrame("好")),
        bytes(stopFrame()),
        bytes("data: [DONE]\n\n")), BETWEEN_CHUNKS);

    Arrivals arrivals = exchange(REQUEST);

    assertThat(arrivals.arrivalTimes()).hasSizeGreaterThanOrEqualTo(2);
    Instant first = arrivals.arrivalTimes().get(0);
    Instant last = arrivals.arrivalTimes().get(arrivals.arrivalTimes().size() - 1);
    assertThat(Duration.between(first, last)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    assertThat(arrivals.status()).isEqualTo(200);
    assertThat(arrivals.contentType()).contains("text/event-stream");
    assertThat(arrivals.requestIdHeader()).isNotBlank();
  }

  @Test
  @DisplayName("逐帧拼接后的文本与上游产出的一致 —— 包括多字节字符被切在块边界的情形")
  void textSurvivesBeingSplitAcrossChunks() {
    byte[] frame = bytes(deltaFrame("你"));
    int cut = cutInsideMultibyteCharacter(frame);
    UPSTREAM.replayStream(200, List.of(
        head(frame, cut),
        tail(frame, cut),
        bytes(deltaFrame("好")),
        bytes(stopFrame()),
        bytes("data: [DONE]\n\n")), Duration.ZERO);

    Arrivals arrivals = exchange(REQUEST);

    assertThat(concatenatedTextOf(arrivals.payloads())).isEqualTo("你好");
  }

  @Test
  @DisplayName("一条流有且只有一个终态，[DONE] 是最后一帧且只出现一次")
  void exactlyOneTerminalFollowedByExactlyOneDone() {
    UPSTREAM.replayStream(200, List.of(
        bytes(deltaFrame("你")),
        bytes(deltaFrame("好")),
        bytes(stopFrame()),
        bytes("data: [DONE]\n\n")), Duration.ZERO);

    Arrivals arrivals = exchange(REQUEST);

    assertThat(arrivals.payloads()).last().isEqualTo("[DONE]");
    assertThat(Collections.frequency(arrivals.payloads(), "[DONE]")).isEqualTo(1);
    assertThat(finishReasonsOf(arrivals.payloads())).containsExactly("stop");
  }

  @Test
  @DisplayName("上游把话说完但没发结束哨兵就关闭连接时，补发 Failed 而不是让流静默结束")
  void upstreamClosingWithoutSentinelIsAFailureNotASilentEnd() {
    UPSTREAM.replayStream(200, List.of(
        bytes(deltaFrame("你")),
        bytes(deltaFrame("好"))), Duration.ZERO);

    Arrivals arrivals = exchange(REQUEST);

    assertThat(arrivals.payloads()).last().isEqualTo("[DONE]");
    List<String> errors = errorTypesOf(arrivals.payloads());
    assertThat(errors).containsExactly("upstream_closed");

    Outcome outcome = lastOutcome();
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
    assertThat(outcome.requestId()).isNotBlank();
  }

  @Test
  @DisplayName("上报的延迟是到收到终态为止的真实耗时，不是零")
  void latencyIsMeasuredUntilTheTerminalArrives() {
    UPSTREAM.replayStream(200, List.of(
        bytes(deltaFrame("你")),
        bytes(deltaFrame("好")),
        bytes(stopFrame()),
        bytes("data: [DONE]\n\n")), BETWEEN_CHUNKS);

    exchange(REQUEST);

    Outcome outcome = lastOutcome();
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.SUCCESS);
    assertThat(outcome.latency()).isGreaterThanOrEqualTo(Duration.ofMillis(200));
  }

  @Test
  @DisplayName("同一份上游字节在一次处理流程中只被 JSON 解析一次（CHUNK_PARSE_COUNT=1）")
  void everyUpstreamFrameIsParsedExactlyOnce() {
    long before = parseCounter.parsedFrames();
    UPSTREAM.replayStream(200, List.of(
        bytes(deltaFrame("你")),
        bytes(deltaFrame("好")),
        bytes(stopFrame()),
        bytes("data: [DONE]\n\n")), Duration.ZERO);

    exchange(REQUEST);

    // 三个数据帧各解析一次；[DONE] 不是 JSON，不需要解析。
    assertThat(parseCounter.parsedFrames() - before).isEqualTo(3L);
  }

  private Outcome lastOutcome() {
    Optional<Outcome> last = outcomes.last();
    assertThat(last).as("结算点没有收到任何结果").isPresent();
    return last.orElseThrow();
  }

  private Arrivals exchange(String requestBody) {
    FluxExchangeResult<byte[]> result = client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody)
        .exchange()
        .returnResult(byte[].class);

    List<Instant> times = new ArrayList<>();
    StringBuilder raw = new StringBuilder();
    Flux<byte[]> body = result.getResponseBody()
        .doOnNext(chunk -> times.add(Instant.now()));
    body.doOnNext(chunk -> raw.append(new String(chunk, StandardCharsets.UTF_8)))
        .blockLast(Duration.ofSeconds(10));

    return new Arrivals(
        result.getStatus().value(),
        String.valueOf(result.getResponseHeaders().getContentType()),
        result.getResponseHeaders().getFirst("X-Airouter-Request-Id"),
        times,
        dataPayloadsOf(raw.toString()));
  }

  /** 一次流式响应的观测结果。 */
  private record Arrivals(int status,
                          String contentType,
                          String requestIdHeader,
                          List<Instant> arrivalTimes,
                          List<String> payloads) {
  }

  private static String deltaFrame(String content) {
    return "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
        + "\"model\":\"recorded-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
        + content + "\"},\"finish_reason\":null}]}\n\n";
  }

  private static String stopFrame() {
    return "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
        + "\"model\":\"recorded-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"
        + "\n\n";
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** 找到 UTF-8 多字节序列的起始，切在它第一个字节之后 —— 这就是「字符被切在块边界」。 */
  private static int cutInsideMultibyteCharacter(byte[] frame) {
    for (int i = 0; i < frame.length - 1; i++) {
      if ((frame[i] & 0xE0) == 0xC0 || (frame[i] & 0xF0) == 0xE0 || (frame[i] & 0xF8) == 0xF0) {
        return i + 1;
      }
    }
    throw new IllegalStateException("样本里没有多字节字符，这条断言就失去了意义");
  }

  private static byte[] head(byte[] source, int cut) {
    byte[] head = new byte[cut];
    System.arraycopy(source, 0, head, 0, cut);
    return head;
  }

  private static byte[] tail(byte[] source, int cut) {
    byte[] tail = new byte[source.length - cut];
    System.arraycopy(source, cut, tail, 0, tail.length);
    return tail;
  }

  /** 从 SSE 原文里取出每一个 {@code data:} 载荷。 */
  private static List<String> dataPayloadsOf(String raw) {
    List<String> payloads = new ArrayList<>();
    for (String event : raw.split("\n\n")) {
      String line = event.trim();
      if (line.startsWith("data:")) {
        payloads.add(line.substring("data:".length()).trim());
      }
    }
    return payloads;
  }

  private static String concatenatedTextOf(List<String> payloads) {
    StringBuilder text = new StringBuilder();
    for (String payload : payloads) {
      if (payload.equals("[DONE]")) {
        continue;
      }
      JsonNode node = readTree(payload);
      if (node.path("error").isObject()) {
        continue;
      }
      for (JsonNode choice : node.path("choices")) {
        text.append(choice.path("delta").path("content").asString(""));
      }
    }
    return text.toString();
  }

  private static List<String> finishReasonsOf(List<String> payloads) {
    List<String> reasons = new ArrayList<>();
    for (String payload : payloads) {
      if (payload.equals("[DONE]")) {
        continue;
      }
      JsonNode node = readTree(payload);
      if (node.path("choices").get(0).hasNonNull("finish_reason")) {
        reasons.add(node.path("choices").get(0).path("finish_reason").asString());
      }
    }
    return reasons;
  }

  private static List<String> errorTypesOf(List<String> payloads) {
    List<String> types = new ArrayList<>();
    for (String payload : payloads) {
      if (payload.equals("[DONE]")) {
        continue;
      }
      JsonNode node = readTree(payload);
      if (node.path("error").isObject()) {
        types.add(node.path("error").path("type").asString());
      }
    }
    return types;
  }

  private static JsonNode readTree(String payload) {
    return MAPPER.readTree(payload);
  }
}
