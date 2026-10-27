package io.github.airouter.gateway.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.transport.SseFramer;
import io.github.airouter.gateway.transport.UpstreamFrame;
import io.github.airouter.gateway.transport.UpstreamFramer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 形态自述的三件事。**形态不等于厂商**：新增一个 OpenAI 兼容厂商是配置，不是类（ADR-0006 第 1 节），
 * 而这里守的正是「形态那一层值不值得一个取值」的判断 —— 三件事里只要有一件不同，才配一个新取值。
 */
@Tag("unit")
final class UpstreamShapeTest {

  @Test
  @DisplayName("openai 形态自述三件事：地址以 /v1 结尾、需要凭证、流式走 SSE 分帧")
  void openAiDescribesItself() {
    assertThat(UpstreamShape.OPENAI.baseUrlSuffix()).isEqualTo("/v1");
    assertThat(UpstreamShape.OPENAI.requiresCredential()).isTrue();
    assertThat(UpstreamShape.OPENAI.framerFactory().apply(1024)).isInstanceOf(SseFramer.class);
    // 「什么算异常结束」的解释也由形态提供，不写在共享的错误映射里（ADR-0006 第 4 节）。
    assertThat(UpstreamShape.OPENAI.describeStreamEnding()).isNotBlank();
  }

  /**
   * 地址形态是**启动期校验**，不是约定。
   *
   * <p>少了 {@code /v1} 后缀不会在启动期报错，只会变成一次深入运行期的 404 ——
   * 一种只在「第一次真实调用发生时」才暴露、而那时候已经难以归因的失败。
   */
  @Test
  @DisplayName("地址不以形态要求的后缀结尾：启动期就红，且指名收到的那个值")
  void baseUrlWithoutRequiredSuffixIsRejectedNamingTheValue() {
    String given = "http://127.0.0.1:11434";

    assertThatThrownBy(() -> UpstreamShape.OPENAI.requireValidBaseUrl(given))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(given)
        .hasMessageContaining("/v1");
  }

  @Test
  @DisplayName("地址带尾斜杠：同样在启动期红，且指名收到的那个值")
  void baseUrlWithTrailingSlashIsRejectedNamingTheValue() {
    String given = "http://127.0.0.1:11434/v1/";

    assertThatThrownBy(() -> UpstreamShape.OPENAI.requireValidBaseUrl(given))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(given);
  }

  /**
   * 形态**不按地址猜**。
   *
   * <p>同一台本机 Ollama 上 {@code /v1}（OpenAI 兼容）与 {@code /api}（原生）两种地址都在，
   * 猜必然猜错；而猜错的代价不是报错，是一整条流一条事件都产不出来 ——
   * 用错分帧器读另一种 wire format，连「为什么没有内容」都看不出来。
   */
  @Test
  @DisplayName("形态不按地址猜：给一个地址当形态名，一律报错并列出合法取值")
  void shapeIsNeverGuessedFromAnAddress() {
    String address = "http://127.0.0.1:11434/v1";

    assertThatThrownBy(() -> UpstreamShape.of(address))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(address)
        .hasMessageContaining(UpstreamShape.OPENAI.id());
    assertThatThrownBy(() -> UpstreamShape.of(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(UpstreamShape.of("OPENAI")).isEqualTo(UpstreamShape.OPENAI);
    assertThat(UpstreamShape.of(" openai ")).isEqualTo(UpstreamShape.OPENAI);
  }

  /**
   * 分帧器**一次订阅一个**。
   *
   * <p>它带着「上一行没攒完」的状态。共享一个实例的后果不是报错，而是两次调用互相污染对方的半行 ——
   * 表现是「偶发少一段内容」，而且只在并发时发生。
   */
  @Test
  @DisplayName("形态给的分帧器每次都是新的：两次订阅不共享半行")
  void framerIsCreatedPerSubscription() {
    UpstreamFramer first = UpstreamShape.OPENAI.framerFactory().apply(1024);
    UpstreamFramer second = UpstreamShape.OPENAI.framerFactory().apply(1024);

    assertThat(first).isNotSameAs(second);

    assertThat(first.push(bytes("data: {\"a\":"))).isEmpty();
    assertThat(second.push(bytes("data: {\"b\":1}\n\n"))).hasSize(1);
    // 第二个分帧器的半行没有跑到第一个上面去：第一个补齐自己的那一行之后仍然只产出一帧。
    assertThat(first.push(bytes("1}\n\n"))).hasSize(1);
  }

  /**
   * 形态自述的第三件事里「流怎么结束」这一半是**可执行**的：它给出来的分帧器真能切出帧。
   *
   * <p>顺带钉住 ADR-0006 第 5 节：哨兵在传输层只是一份载荷。
   * 认它是 codec 的事 —— 传输层认下哨兵，等于替某一家的协议做决定。
   */
  @Test
  @DisplayName("形态给的分帧器能切出帧；[DONE] 在它眼里只是一份载荷，不是结束")
  void framerFromTheShapeCutsFramesAndDoesNotInterpretTheSentinel() {
    UpstreamFramer framer = UpstreamShape.OPENAI.framerFactory().apply(1024);

    List<UpstreamFrame> frames = framer.push(bytes("data: {\"a\":1}\n\ndata: [DONE]\n\n"));

    assertThat(frames).hasSize(2);
    assertThat(payload(frames.get(0))).isEqualTo("{\"a\":1}");
    // 它不是「结束」，只是这一帧的原文。是不是结束由形态的 codec 判断。
    assertThat(payload(frames.get(1))).isEqualTo("[DONE]");
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String payload(UpstreamFrame frame) {
    return ((UpstreamFrame.Data) frame).payload();
  }
}
