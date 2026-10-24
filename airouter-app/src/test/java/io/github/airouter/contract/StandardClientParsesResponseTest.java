package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.airouter.app.AiRouterApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

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
 * <p>它打的是本机真实端点（local 档位），因此与 {@link LocalModelEndToEndTest} 一样依赖端点在跑。
 */
@SpringBootTest(classes = AiRouterApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
final class StandardClientParsesResponseTest {

  @Test
  @DisplayName("官方 OpenAI Java 客户端不改一行配置就能完成一次调用并解析响应")
  void officialClientTalksToTheGatewayWithoutChanges(@Value("${local.server.port}") int port) {
    OpenAIClient client = OpenAIOkHttpClient.builder()
        .baseUrl("http://127.0.0.1:" + port + "/v1")
        .apiKey("local-ollama-needs-none")
        .build();

    ChatCompletion completion = client.chat().completions().create(
        ChatCompletionCreateParams.builder()
            .model("qwen2.5:0.5b")
            .addUserMessage("只回复两个字：收到")
            .temperature(0.0)
            .maxTokens(16)
            .build());

    assertThat(completion.id()).isNotBlank();
    assertThat(completion.model()).isNotBlank();
    assertThat(completion.choices()).isNotEmpty();
    assertThat(completion.choices().get(0).message().content()).isPresent();
    assertThat(completion.usage()).isPresent();
    assertThat(completion.usage().get().totalTokens()).isPositive();
  }
}
