package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.gateway.faces.openai.OpenAiInboundCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
 * 打真实的 Ollama **原生**端点。
 *
 * <p>{@link LocalModelEndToEndTest} 打的是同一台机器上的 {@code /v1}（兼容接口），
 * 这里打的是 {@code /api/chat}（原生接口）。同一台机器两种接口都有，
 * 因此这一条不只是「又接了一家」，而是把「形态」这个概念摆到了真实流量上：
 * 客户端那一侧的字节两种形态下是一样的，上游那一侧的字节完全不同。
 *
 * <p>它依赖本机端点在跑，端点没起来时它红，并给出指路信息 —— 不做静默跳过：
 * 一个可以被跳过的兼容性测试等于没有（与另一条真机测试同样的取舍）。
 *
 * <p>两条路径各一条用例，对应票面「第二个 Provider 同样具备自己的端对端覆盖」。
 */
@SpringBootTest(classes = AiRouterApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class OllamaNativeEndToEndTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String RUNBOOK = "docs/runbook/local-openai-endpoint.md";

  /** 原生接口的地址根是 {@code /api}：它与兼容接口的 {@code /v1} 是本票要区分的两件事。 */
  private static final String NATIVE_BASE_URL = "http://127.0.0.1:11434/api";

  private static final String MODEL = "qwen2.5:0.5b";

  @DynamicPropertySource
  static void nativeEndpoint(DynamicPropertyRegistry registry) {
    registry.add("airouter.endpoint.provider", () -> "ollama");
    registry.add("airouter.endpoint.base-url", () -> NATIVE_BASE_URL);
    registry.add("airouter.endpoint.model", () -> MODEL);
    registry.add("airouter.endpoint.request-timeout", () -> "60s");
    registry.add("airouter.endpoint.idle-timeout", () -> "60s");
  }

  @Test
  @DisplayName("对原生端点完成一次非流式调用：200、有内容、用量如实且没有编造合计、有请求标识")
  void completesAgainstTheNativeEndpoint(@Value("${local.server.port}") int port) {
    WebTestClient client = client(port);

    EntityExchangeResult<byte[]> result = client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue("""
            {"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"只回复两个字：收到"}],
             "stream":false,"temperature":0,"max_tokens":16}
            """)
        .exchange()
        .expectBody()
        .returnResult();

    JsonNode body = readTree(result.getResponseBody());
    if (result.getStatus().value() != 200) {
      fail("对原生端点的调用没有成功（HTTP " + result.getStatus().value() + "）。"
          + "本机模型端点是否已经起来？安装与起停见 " + RUNBOOK + "。"
          + "网关看到的错误是：" + body.path("error").path("message").asString());
    }

    assertThat(body.path("object").asString()).isEqualTo("chat.completion");
    assertThat(body.path("choices").get(0).path("message").path("content").asString()).isNotBlank();
    assertThat(body.path("choices").get(0).path("finish_reason").asString()).isNotBlank();
    assertThat(body.path("model").asString()).isNotBlank();
    assertThat(result.getResponseHeaders().getFirst("X-Airouter-Request-Id")).isNotBlank();

    // 原生接口给两个计数，不给合计；合计因此是 0 —— 它不是「总量为零」，而是「上游没有给」。
    // 断言它等于 0 的同时断言明细在场，就是在钉住「不编造」这件事：
    // 只要有人改成两个计数相加，这两个断言立刻矛盾。
    assertThat(body.path("usage").path("prompt_tokens").asLong()).isPositive();
    assertThat(body.path("usage").path("completion_tokens").asLong()).isPositive();
    assertThat(body.path("usage").path("total_tokens").asLong()).isZero();
    assertThat(body.path("usage").has("eval_duration")).isTrue();

    // 创建时间是原生接口那个 RFC 3339 字符串换算过来的秒数，不是缺席。
    assertThat(body.path("created").asLong()).isPositive();
  }

  @Test
  @DisplayName("对原生端点完成一次流式调用：逐帧有内容、终态带停止原因、[DONE] 收尾")
  void streamsAgainstTheNativeEndpoint(@Value("${local.server.port}") int port) {
    WebTestClient client = client(port);

    SseCall.Arrivals arrivals = SseCall.exchange(client, """
        {"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"只回复两个字：收到"}],
         "stream":true,"temperature":0,"max_tokens":16}
        """);

    if (arrivals.status() != 200) {
      fail("对原生端点的流式调用没有成功（HTTP " + arrivals.status() + "）。"
          + "本机模型端点是否已经起来？安装与起停见 " + RUNBOOK + "。");
    }

    List<String> payloads = arrivals.payloads();
    assertThat(SseCall.concatenatedTextOf(payloads)).isNotBlank();
    assertThat(SseCall.errorTypesOf(payloads)).isEmpty();
    // 原生接口没有 [DONE]，客户端这一个是我们确认有终态之后写的：它必须在。
    assertThat(payloads).last().isEqualTo(OpenAiInboundCodec.DONE_SENTINEL);
    assertThat(SseCall.finishReasonsOf(payloads)).contains("stop");
    assertThat(arrivals.requestIdHeader()).isNotBlank();
  }

  private static WebTestClient client(int port) {
    return WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();
  }

  private static JsonNode readTree(byte[] body) {
    try {
      return MAPPER.readTree(body == null ? new byte[0] : body);
    } catch (JacksonException failure) {
      throw new IllegalStateException("响应不是合法 JSON：" + new String(body, StandardCharsets.UTF_8), failure);
    }
  }
}
