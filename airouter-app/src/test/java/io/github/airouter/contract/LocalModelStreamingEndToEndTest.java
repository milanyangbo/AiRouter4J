package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.gateway.observe.RecordingOutcomeObserver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 对**真实模型端点**打一次流式调用。
 *
 * <p>它与 {@link StreamingEndToEndTest} 的分工和 {@link LocalModelEndToEndTest} 那一侧相同：
 * 那边控制到字节块一级，这里完全不控制 —— 上游就是本机那个真实的 OpenAI 兼容端点，
 * 帧的节奏、分帧方式、是否带用量帧、结束哨兵长什么样，全都由它决定。
 * 它买来的是真实世界的一致性：真实端点在流式上做出的每一个选择，网关都得接得住。
 *
 * <p>代价同样是它依赖本机端点在跑，且**不做静默跳过**：端点没起来时它会红并给出指路的失败信息。
 */
@SpringBootTest(classes = AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class LocalModelStreamingEndToEndTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String RUNBOOK = "docs/runbook/local-openai-endpoint.md";

  @Autowired
  private RecordingOutcomeObserver outcomes;

  private WebTestClient client;

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();
  }

  @Test
  @DisplayName("对本机真实模型端点完成一次流式调用：多帧到达、以 [DONE] 收尾、内容非空、延迟不是零")
  void streamsAgainstTheLocalModelEndpoint() {
    FluxExchangeResult<byte[]> result = client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue("""
            {"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"请从 1 数到 8，用顿号分隔"}],
             "stream":true,"temperature":0,"max_tokens":64}
            """)
        .exchange()
        .returnResult(byte[].class);

    StringBuilder raw = new StringBuilder();
    result.getResponseBody()
        .doOnNext(chunk -> raw.append(new String(chunk, StandardCharsets.UTF_8)))
        .blockLast(Duration.ofSeconds(60));

    if (result.getStatus().value() != 200) {
      fail("对真实端点的流式调用没有成功（HTTP " + result.getStatus().value() + "）。"
          + "本机模型端点是否已经起来？安装与起停见 " + RUNBOOK + "。"
          + "网关看到的错误是：" + raw);
    }

    List<String> payloads = dataPayloadsOf(raw.toString());
    // 断言帧的节奏而不是内容：0.5B 模型的回答不保证稳定，
    // 但「分了多帧」「有 [DONE]」「拼起来不是空的」这三条必须成立。
    assertThat(payloads).hasSizeGreaterThanOrEqualTo(2);
    assertThat(payloads).last().isEqualTo("[DONE]");
    assertThat(Collections.frequency(payloads, "[DONE]")).isEqualTo(1);
    assertThat(concatenatedTextOf(payloads)).isNotBlank();
    assertThat(result.getResponseHeaders().getFirst("X-Airouter-Request-Id")).isNotBlank();

    Optional<Outcome> last = outcomes.last();
    assertThat(last).isPresent();
    Outcome outcome = last.orElseThrow();
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.SUCCESS);
    // 来源项目的缺陷正是这里恒为零：响应头写出即结算，流式样本因此不参与延迟统计。
    assertThat(outcome.latency()).isPositive();
  }

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
      JsonNode node = MAPPER.readTree(payload);
      for (JsonNode choice : node.path("choices")) {
        text.append(choice.path("delta").path("content").asString(""));
      }
    }
    return text.toString();
  }
}
