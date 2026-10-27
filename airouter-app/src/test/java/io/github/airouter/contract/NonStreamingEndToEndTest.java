package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 非流式路径的端到端行为：**从入站 HTTP 打进去**，内部不替换任何组件。
 *
 * <p>唯一被替换掉的是远端那个端点 —— 用 {@link RecordedUpstream} 回放固定的字节，
 * 这样「网关发出去什么」与「客户端收回来什么」两侧都能被断言。
 * 这不是 mock 内部组件：出方向与入方向的全部代码都在跑，被固定的只有远端那个不确定的答复。
 *
 * <p>断言全部落在外部可见的东西上：状态码、响应体、响应头，以及上游收到的那一份请求。
 */
// classes 要显式给：这个测试不在 io.github.airouter.app 包下，
// 按包名向上找 @SpringBootConfiguration 的那条路在这个位置走不通。
@SpringBootTest(classes = io.github.airouter.app.AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
final class NonStreamingEndToEndTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 端口由内核分配，不必为它准备固定端口，也就不会和别人抢。 */
  private static final RecordedUpstream UPSTREAM = startUpstream();

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
    registry.add("airouter.default-service", () -> "chat");
    registry.add("airouter.services.chat.instances[0].id", () -> "recorded");
    registry.add("airouter.services.chat.instances[0].shape", () -> "openai");
    registry.add("airouter.services.chat.instances[0].base-url", UPSTREAM::baseUrl);
    registry.add("airouter.services.chat.instances[0].api-key", () -> "not-a-real-key");
    registry.add("airouter.services.chat.instances[0].model", () -> "recorded-model");
    registry.add("airouter.services.chat.instances[0].request-timeout", () -> "5s");

    // 这个实例的字段在这里**写全**：列表元素不跨配置来源合并，
    // 少给一个（哪怕是 weight）就会让 application.yml 里那一份排不上用场，
    // 表现为那一项是默认值（weight 为 0）而不是「沿用配置文件」。
    registry.add("airouter.services.chat.instances[0].weight", () -> "1");
    registry.add("airouter.services.chat.instances[0].enabled", () -> "true");
    registry.add("airouter.services.chat.instances[0].tags", () -> "");
  }

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  @DisplayName("非流式请求返回 200，且响应体是 OpenAI 的形态")
  void nonStreamingCallReturnsOpenAiShape() {
    UPSTREAM.replay(200, """
        {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"recorded-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"好"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":11,"completion_tokens":2,"total_tokens":13}}
        """);

    EntityExchangeResult<byte[]> result = exchange("""
        {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":false}
        """);

    assertThat(result.getStatus().value()).isEqualTo(200);
    // 请求标识在成功路径上同样要在场：排查问题时拿不到标识的那一侧，恰恰是最需要它的成功侧。
    assertThat(result.getResponseHeaders().getFirst("X-Airouter-Request-Id")).isNotBlank();

    JsonNode response = readTree(result.getResponseBody());
    assertThat(response.path("object").asString()).isEqualTo("chat.completion");
    assertThat(response.path("choices").get(0).path("message").path("content").asString()).isEqualTo("好");
    assertThat(response.path("choices").get(0).path("finish_reason").asString()).isEqualTo("stop");
    assertThat(response.path("usage").path("total_tokens").asLong()).isEqualTo(13L);
    assertThat(response.path("created").asLong()).isEqualTo(1L);
  }

  @Test
  @DisplayName("请求打到上游的 /chat/completions，并带着配置里那个凭证")
  void requestReachesUpstreamWithItsCredential() {
    UPSTREAM.replay(200, """
        {"id":"chatcmpl-path","object":"chat.completion","model":"recorded-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"好"},"finish_reason":"stop"}]}
        """);
    UPSTREAM.resetRequests();

    exchange("""
        {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":false}
        """);

    assertThat(UPSTREAM.requests()).isEqualTo(1);
    assertThat(UPSTREAM.lastPath()).isEqualTo("/v1/chat/completions");
    assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer not-a-real-key");
  }

  /**
   * 越界字段的双向透传。
   *
   * <p>这是网关最容易做坏、也最容易被忽略的一件事：厂商私有字段不是多余的东西，
   * 而是调用方依赖的能力载体。丢掉它们的代价不发生在网关身上，而是发生在调用方身上，
   * 因此必须有一条断言把它们钉住 —— 而且两个方向各一条。
   */
  @Test
  @DisplayName("请求里的厂商私有字段原样转发给上游，上游响应里的独有字段原样回到客户端")
  void vendorSpecificFieldsSurviveBothDirections() {
    UPSTREAM.replay(200, """
        {"id":"chatcmpl-2","object":"chat.completion","model":"recorded-model",
         "vendor_trace":"trace-42","extra":{"nested":true},
         "choices":[{"index":0,"message":{"role":"assistant","content":"好"},
                     "finish_reason":"stop","vendor_reasoning":"kept"}],
         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """);

    JsonNode response = postAndRead("""
        {"model":"recorded-model","messages":[{"role":"user","content":"hi","vendor_hint":"abc"}],
         "stream":false,"vendor_ext":{"level":7}}
        """);

    JsonNode outbound = readTree(UPSTREAM.lastRequestBody());
    assertThat(outbound.path("vendor_ext").path("level").asInt()).isEqualTo(7);
    assertThat(outbound.path("messages").get(0).path("vendor_hint").asString()).isEqualTo("abc");

    assertThat(response.path("vendor_trace").asString()).isEqualTo("trace-42");
    assertThat(response.path("extra").path("nested").asBoolean()).isTrue();
    assertThat(response.path("choices").get(0).path("vendor_reasoning").asString()).isEqualTo("kept");
  }

  @Test
  @DisplayName("上游没有给 usage 时，响应里就不出现 usage —— 不补零，不编造")
  void usageIsOmittedRatherThanInvented() {
    UPSTREAM.replay(200, """
        {"id":"chatcmpl-3","object":"chat.completion","model":"recorded-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"好"},"finish_reason":"stop"}]}
        """);

    JsonNode response = postAndRead("""
        {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":false}
        """);

    assertThat(response.has("usage")).isFalse();
  }

  @Test
  @DisplayName("上游 4xx：状态码原样透传，错误体同样是 OpenAI 信封，请求标识仍然在场")
  void upstream4xxIsPassedThroughWithRequestId() {
    UPSTREAM.replay(404, """
        {"error":{"message":"model 'nope' not found","type":"not_found_error","param":null,"code":null}}
        """);

    EntityExchangeResult<byte[]> result = exchange("""
        {"model":"nope","messages":[{"role":"user","content":"hi"}],"stream":false}
        """);

    assertThat(result.getStatus().value()).isEqualTo(404);
    assertThat(result.getResponseHeaders().getFirst("X-Airouter-Request-Id")).isNotBlank();
    JsonNode error = readTree(result.getResponseBody());
    assertThat(error.path("error").path("message").asString()).contains("model 'nope' not found");
    assertThat(error.path("error").path("type").asString()).isEqualTo("upstream_4xx");
  }

  @Test
  @DisplayName("上游 5xx 收敛成 502：客户端不需要知道上游具体出了什么事")
  void upstream5xxBecomesBadGateway() {
    UPSTREAM.replay(503, """
        {"error":{"message":"overloaded","type":"server_error","param":null,"code":null}}
        """);

    EntityExchangeResult<byte[]> result = exchange("""
        {"model":"recorded-model","messages":[{"role":"user","content":"hi"}],"stream":false}
        """);

    assertThat(result.getStatus().value()).isEqualTo(502);
    JsonNode error = readTree(result.getResponseBody());
    assertThat(error.path("error").path("type").asString()).isEqualTo("upstream_5xx");
  }

  @Test
  @DisplayName("请求体不是合法 JSON 时是 400，而不是 500")
  void malformedRequestBodyIsBadRequest() {
    UPSTREAM.resetRequests();
    EntityExchangeResult<byte[]> result = exchange("{not json");

    assertThat(result.getStatus().value()).isEqualTo(400);
    // 请求没被解析出来就不该出网：坏请求在边界上结束，不是转发给上游再等它拒绝。
    assertThat(UPSTREAM.requests()).isZero();
  }

  /**
   * 现成的 OpenAI 客户端**不做任何改动**就能用。
   *
   * <p>它是「兼容 OpenAI」这句话唯一的硬证据。自己写断言去证明自己的响应合规，
   * 等于自己批自己的卷子：我们断言哪些字段，取决于我们认为哪些字段重要；
   * 而第三方客户端断言哪些字段，取决于真实世界的客户端会读哪些字段。
   *
   * <p>因此这里不写一条关于响应形状的断言 —— 形状由 SDK 自己的模型类来判，
   * 少一个字段、类型不对、取值不认识，都会在 SDK 解析时炸出来。
   * 这条也是未来改 wire format 时最先变红的那一道。
   *
   * <p>它背后同样是 {@link RecordedUpstream}：要证明的是「协议形状对不对」，
   * 不是「本机那个模型在不在跑」。把两件事捆在一起，这条断言会在端点不在时
   * 以一种与协议无关的方式变红 —— 而那种红恰恰最容易被忽略。
   */
  @Test
  @DisplayName("官方 OpenAI Java 客户端不改一行配置就能完成一次调用并解析响应")
  void officialClientTalksToTheGatewayWithoutChanges(@Value("${local.server.port}") int port) {
    UPSTREAM.replay(200, """
        {"id":"chatcmpl-sdk-1","object":"chat.completion","created":1792831538,
         "model":"recorded-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"收到"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}
        """);

    OpenAIClient sdk = OpenAIOkHttpClient.builder()
        .baseUrl("http://127.0.0.1:" + port + "/v1")
        .apiKey("local-ollama-needs-none")
        .build();

    ChatCompletion completion = sdk.chat().completions().create(
        ChatCompletionCreateParams.builder()
            .model("recorded-model")
            .addUserMessage("只回复两个字：收到")
            .temperature(0.0)
            .build());

    assertThat(completion.id()).isEqualTo("chatcmpl-sdk-1");
    assertThat(completion.model()).isEqualTo("recorded-model");
    assertThat(completion.choices()).isNotEmpty();
    assertThat(completion.choices().get(0).message().content()).hasValue("收到");
    assertThat(completion.usage()).isPresent();
    assertThat(completion.usage().get().totalTokens()).isEqualTo(11L);
  }

  private JsonNode postAndRead(String requestBody) {
    return readTree(exchange(requestBody).getResponseBody());
  }

  private EntityExchangeResult<byte[]> exchange(String requestBody) {
    return client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody)
        .exchange()
        .expectBody()
        .returnResult();
  }

  private static JsonNode readTree(byte[] body) {
    try {
      return MAPPER.readTree(body == null ? new byte[0] : body);
    } catch (JacksonException failure) {
      throw new IllegalStateException("响应不是合法 JSON：" + new String(body, StandardCharsets.UTF_8), failure);
    }
  }

  private static JsonNode readTree(String body) {
    return readTree(body.getBytes(StandardCharsets.UTF_8));
  }
}
