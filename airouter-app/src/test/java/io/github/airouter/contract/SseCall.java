package io.github.airouter.contract;

import io.github.airouter.gateway.faces.openai.OpenAiFace;
import io.github.airouter.gateway.faces.openai.OpenAiInboundCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 打一次 SSE 调用，并从它里面读出可断言的东西。
 *
 * <p>它只做两件很机械的事：把一次 {@code POST /v1/chat/completions} 打进去并读到流结束，
 * 以及把 SSE 原文拆成 {@code data:} 载荷、错误类别、文本内容。放在一处是为了让
 * 「怎么读一条流」只有一个版本 —— 三个用例各自抄一份读法，迟早会有一份漏掉
 * {@code [DONE]} 或者把错误帧当成内容帧，而那类偏差只在断言失败时才被发现。
 *
 * <p>造帧的两三个方法也在这里：它们与读法是一对，改了其中一侧另一侧必须跟着看。
 */
final class SseCall {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 等一条流走完的上限。超过它就不是「慢」，而是这条流没有终态 —— 那本身就是缺陷。 */
  private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(15);

  private SseCall() {
  }

  /**
   * 打一次流式请求，一直读到流结束。
   *
   * <p>它**阻塞到终态为止**，因此只适用于「这条流自己会结束」的用例
   * （正常结束、上游报错、超时）。取消与背压两类用例不经过这里：它们要的是
   * 在流还没结束时保持订阅并自己决定要不要继续要，那需要直接拿 {@code Flux}。
   *
   * @param client 打进运行中的应用的客户端
   * @param requestBody 请求体原文
   * @return 这一次调用的观测结果
   */
  static Arrivals exchange(WebTestClient client, String requestBody) {
    FluxExchangeResult<byte[]> result = client.post()
        .uri(OpenAiFace.PATH)
        .header("Content-Type", "application/json")
        .bodyValue(requestBody)
        .exchange()
        .returnResult(byte[].class);

    StringBuilder raw = new StringBuilder();
    result.getResponseBody()
        .doOnNext(chunk -> raw.append(new String(chunk, StandardCharsets.UTF_8)))
        .blockLast(COMPLETION_TIMEOUT);

    return new Arrivals(
        result.getStatus().value(),
        String.valueOf(result.getResponseHeaders().getContentType()),
        result.getResponseHeaders().getFirst(OpenAiFace.REQUEST_ID_HEADER),
        dataPayloadsOf(raw.toString()));
  }

  /** 一次流式调用的观测结果。 */
  record Arrivals(int status,
                  String contentType,
                  String requestIdHeader,
                  List<String> payloads) {
  }

  /** 从 SSE 原文里取出每一个 {@code data:} 载荷，含 {@code [DONE]} 本身。 */
  static List<String> dataPayloadsOf(String raw) {
    List<String> payloads = new ArrayList<>();
    for (String event : raw.split("\n\n")) {
      String line = event.trim();
      if (line.startsWith("data:")) {
        payloads.add(line.substring("data:".length()).trim());
      }
    }
    return payloads;
  }

  /**
   * 帧序列里出现过的错误类别，按顺序。
   *
   * <p>只看带 {@code error} 对象的帧：内容帧与 {@code [DONE]} 都不是错误。
   * 「哪些帧是错误帧」是区分「上游拒绝了」与「连不上」的唯一依据，
   * 因此这里取的是 {@code type} 而不是整个消息文本 —— 文本会随上游而变，类别不会。
   */
  static List<String> errorTypesOf(List<String> payloads) {
    List<String> types = new ArrayList<>();
    for (String payload : payloads) {
      if (isSentinel(payload)) {
        continue;
      }
      JsonNode node = tree(payload);
      if (node.path("error").isObject()) {
        types.add(node.path("error").path("type").asString());
      }
    }
    return types;
  }

  /** 帧序列里出现过的停止原因，按顺序。 */
  static List<String> finishReasonsOf(List<String> payloads) {
    List<String> reasons = new ArrayList<>();
    for (String payload : payloads) {
      if (isSentinel(payload)) {
        continue;
      }
      JsonNode node = tree(payload);
      if (node.path("choices").get(0).hasNonNull("finish_reason")) {
        reasons.add(node.path("choices").get(0).path("finish_reason").asString());
      }
    }
    return reasons;
  }

  /** 把帧序列里的文本增量拼起来。错误帧与 {@code [DONE]} 不贡献文本。 */
  static String concatenatedTextOf(List<String> payloads) {
    StringBuilder text = new StringBuilder();
    for (String payload : payloads) {
      if (isSentinel(payload)) {
        continue;
      }
      JsonNode node = tree(payload);
      if (node.path("error").isObject()) {
        continue;
      }
      for (JsonNode choice : node.path("choices")) {
        text.append(choice.path("delta").path("content").asString(""));
      }
    }
    return text.toString();
  }

  /** 解析一帧的载荷。调用方需先用 {@link #isSentinel} 排除哨兵。 */
  static JsonNode tree(String payload) {
    return MAPPER.readTree(payload);
  }

  /** 是不是结束哨兵。它不是 JSON，因此所有解析都必须先把它挑出来。 */
  static boolean isSentinel(String payload) {
    return OpenAiInboundCodec.DONE_SENTINEL.equals(payload);
  }

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** 一个带文本增量的帧。 */
  static String deltaFrame(String id, String model, String content) {
    return "data: {\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
        + "\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
        + content + "\"},\"finish_reason\":null}]}\n\n";
  }

  /** 一个只带停止原因的帧（上游的最后一帧通常是这个形状）。 */
  static String stopFrame(String id, String model) {
    return "data: {\"id\":\"" + id + "\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
        + "\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"
        + "\n\n";
  }

  /** 上游的结束哨兵。 */
  static String doneFrame() {
    return "data: [DONE]\n\n";
  }
}
