package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.airouter.app.AiRouterApplication;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 现成的 OpenAI 客户端**不做任何改动**就能用。
 *
 * <p>这是「兼容 OpenAI」这句话的唯一硬证据。自己写断言去证明自己的响应合规，
 * 等于自己批自己的卷子：我们断言哪些字段，取决于我们认为哪些字段重要；
 * 而第三方客户端断言哪些字段，取决于真实世界的客户端会读哪些字段。
 *
 * <p>因此这里不写一条关于响应形状的断言 —— 形状由 SDK 自己的模型类来判，
 * 少一个字段、类型不对、取值不认识，都会在 SDK 解析时炸出来。
 * 这条测试也是未来改 wire format 时最先变红的那一道。
 *
 * <p>它背后同样是 {@link RecordedUpstream}：这条测试要证明的是「协议形状对不对」，
 * 不是「本机那个模型在不在跑」。把两件事捆在一起，那条断言会在端点不在时以一种
 * 与协议无关的方式变红 —— 而那种红恰恰最容易被忽略。
 */
@SpringBootTest(classes = AiRouterApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
final class StandardClientParsesResponseTest {

  private static final RecordedUpstream UPSTREAM = startUpstream();

  private static RecordedUpstream startUpstream() {
    try {
      return new RecordedUpstream(0, 200, """
          {"id":"chatcmpl-sdk-1","object":"chat.completion","created":1792831538,
           "model":"recorded-model",
           "choices":[{"index":0,"message":{"role":"assistant","content":"收到"},
                       "finish_reason":"stop"}],
           "usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}
          """);
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
  }

  @Test
  @DisplayName("官方 OpenAI Java 客户端不改一行配置就能完成一次调用并解析响应")
  void officialClientTalksToTheGatewayWithoutChanges(@Value("${local.server.port}") int port) {
    OpenAIClient client = OpenAIOkHttpClient.builder()
        .baseUrl("http://127.0.0.1:" + port + "/v1")
        .apiKey("local-ollama-needs-none")
        .build();

    ChatCompletion completion = client.chat().completions().create(
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
}
