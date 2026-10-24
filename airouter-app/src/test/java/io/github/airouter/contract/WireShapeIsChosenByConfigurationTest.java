package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.GenerationParams;
import io.github.airouter.gateway.observe.SettlementObserver;
import io.github.airouter.gateway.providers.ChunkParseCounter;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.providers.ollama.OllamaProvider;
import io.github.airouter.gateway.transport.InFlightStreams;
import io.github.airouter.gateway.transport.OutboundWire;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 走哪一种 wire 形态由配置决定，而两种形态**真的不一样**。
 *
 * <p>这一条不经过 Spring 容器：它比的是两个 Provider 各自治同一次请求时发出去的字节，
 * 比的对象是出站那一侧。容器按配置选哪一个由 {@link OllamaNativeEndToEndTest} 覆盖 ——
 * 那边把 {@code provider} 配成 {@code ollama} 之后打真实端点，选错了它不会红得这么明确。
 *
 * <p>断言落在四个地方，它们都是形态差异而非命名偏好：路径、生成参数的位置、凭证、流式分帧方式。
 * 前三个在这里，第四个由 {@code LineFrameReader} 与 {@link SecondProviderContractTest} 覆盖 ——
 * 逐行 JSON 的字节用 SSE 的读法一条事件都读不出来，那边每一条流式用例都是它的反证。
 */
final class WireShapeIsChosenByConfigurationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final String MODEL = "recorded-model";

  @Test
  @DisplayName("同一个 Canonical 请求，两种形态发出去的路径、参数位置与凭证都不相同")
  void twoShapesProduceDifferentOutboundTraffic() throws IOException {
    try (RecordedUpstream recorded = new RecordedUpstream(0, 200, "{}")) {
      Outbound openAi = sendThroughOpenAi(recorded);
      Outbound ollama = sendThroughOllama(recorded);

      assertThat(openAi.path()).isEqualTo("/v1/chat/completions");
      assertThat(ollama.path()).isEqualTo("/api/chat");

      // 生成参数：一个在顶层，一个在 options 里，且生成上限换了名字。
      assertThat(openAi.body().path("temperature").asDouble()).isEqualTo(0.0);
      assertThat(openAi.body().has("options")).isFalse();
      assertThat(ollama.body().path("options").path("temperature").asDouble()).isEqualTo(0.0);
      assertThat(ollama.body().path("options").path("num_predict").asInt()).isEqualTo(16);
      assertThat(ollama.body().has("temperature")).isFalse();

      // 凭证：一个是协议的一部分，一个不是。
      assertThat(openAi.authorization()).isEqualTo("Bearer recorded-key");
      assertThat(ollama.authorization()).isNull();
    }
  }

  /**
   * 两种形态读的是同一份入站 Canonical，因此差异不来自入站。
   *
   * <p>这一条看着像自证，它防的是另一件事：将来有人为了省事让某一个 Provider 直接吃
   * 入站 JSON 原文（例如「反正字段差不多」），那时差异会消失，而这条断言会红。
   */
  @Test
  @DisplayName("两个 Provider 收到的是同一个 Canonical 请求，差异不来自入站")
  void bothShapesReceiveTheSameCanonicalRequest() throws IOException {
    try (RecordedUpstream recorded = new RecordedUpstream(0, 200, "{}")) {
      sendThroughOpenAi(recorded);
      JsonNode openAiMessages = MAPPER.readTree(recorded.lastRequestBody()).path("messages");
      sendThroughOllama(recorded);
      JsonNode ollamaMessages = MAPPER.readTree(recorded.lastRequestBody()).path("messages");

      assertThat(ollamaMessages).isEqualTo(openAiMessages);
    }
  }

  private static Outbound sendThroughOpenAi(RecordedUpstream recorded) {
    recorded.replay(200, """
        {"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"好"},
         "finish_reason":"stop"}]}
        """);
    OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
        new OutboundWire(recorded.baseUrl(), TIMEOUT, TIMEOUT, new InFlightStreams()),
        "recorded-key",
        MODEL,
        recorded.baseUrl(),
        new SettlementObserver(),
        new ChunkParseCounter());
    complete(provider.complete(request()));
    return new Outbound(recorded.lastPath(), MAPPER.readTree(recorded.lastRequestBody()),
        recorded.lastAuthorization());
  }

  private static Outbound sendThroughOllama(RecordedUpstream recorded) {
    recorded.replay(200, """
        {"model":"recorded-model","message":{"role":"assistant","content":"好"},"done":true,
         "done_reason":"stop","prompt_eval_count":1,"eval_count":1}
        """);
    String instance = recorded.root() + "/api";
    OllamaProvider provider = new OllamaProvider(
        new OutboundWire(instance, TIMEOUT, TIMEOUT, new InFlightStreams()),
        MODEL,
        instance,
        new SettlementObserver(),
        new ChunkParseCounter());
    complete(provider.complete(request()));
    return new Outbound(recorded.lastPath(), MAPPER.readTree(recorded.lastRequestBody()),
        recorded.lastAuthorization());
  }

  private static void complete(java.util.concurrent.CompletionStage<CanonicalResponse> stage) {
    stage.toCompletableFuture().join();
  }

  private static CanonicalRequest request() {
    return new CanonicalRequest(
        "shape-1",
        "chat",
        MODEL,
        List.of(new CanonicalMessage(CanonicalRole.USER, "hi")),
        List.of(),
        new GenerationParams(0.0, null, 16, List.of()),
        false,
        Map.of());
  }

  /** 一次出站的三个可断言之处：打在哪个路径、发了什么、带了什么凭证。 */
  private record Outbound(String path, JsonNode body, String authorization) {
  }
}
