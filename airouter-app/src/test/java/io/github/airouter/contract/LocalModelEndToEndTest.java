package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.airouter.app.AiRouterApplication;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 打真实模型端点。
 *
 * <p>这一条与 {@link NonStreamingEndToEndTest} 的分工是刻意的：那边固定字节、断点式的场景；
 * 这里完全不固定 —— 上游就是本机那个真实的 OpenAI 兼容端点（Ollama + qwen2.5:0.5b）。
 * 它买来的是「与真实世界的兼容性」：真实响应的字段、真实的 token 用量、真实的连接行为。
 *
 * <p>代价是它依赖本机端点在跑。端点没起来时它会红，并且红的时候给出一条指路的失败信息 ——
 * 不做静默跳过：一个可以被跳过的兼容性测试，等于没有。
 */
@SpringBootTest(classes = AiRouterApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class LocalModelEndToEndTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String RUNBOOK = "docs/runbook/local-openai-endpoint.md";

  @Test
  @DisplayName("对本机真实模型端点完成一次非流式调用：200、有内容、有如实返回的用量、有请求标识")
  void completesAgainstTheLocalModelEndpoint(
      @Value("${local.server.port}") int port) {
    WebTestClient client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();

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
      fail("对真实端点的调用没有成功（HTTP " + result.getStatus().value() + "）。"
          + "本机模型端点是否已经起来？安装与起停见 " + RUNBOOK + "。"
          + "网关看到的错误是：" + body.path("error").path("message").asString());
    }

    // 断言形状而不是内容：0.5B 模型的回答不保证稳定，但「回答存在」与「用量如实」必须成立。
    assertThat(body.path("object").asString()).isEqualTo("chat.completion");
    assertThat(body.path("choices").get(0).path("message").path("content").asString()).isNotBlank();
    assertThat(body.path("choices").get(0).path("finish_reason").asString()).isNotBlank();
    assertThat(body.path("usage").path("total_tokens").asLong()).isPositive();
    assertThat(body.path("model").asString()).isNotBlank();

    // 上游独有的字段应当原样回来：本机端点会带 created 与 system_fingerprint。
    assertThat(body.has("created")).isTrue();
    assertThat(result.getResponseHeaders().getFirst("X-Airouter-Request-Id")).isNotBlank();
  }

  private static JsonNode readTree(byte[] body) {
    try {
      return MAPPER.readTree(body == null ? new byte[0] : body);
    } catch (JacksonException failure) {
      throw new IllegalStateException("响应不是合法 JSON：" + new String(body, StandardCharsets.UTF_8), failure);
    }
  }
}
