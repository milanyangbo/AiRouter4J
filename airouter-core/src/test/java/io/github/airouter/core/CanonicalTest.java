package io.github.airouter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 领域模型的形状。
 *
 * <p>这里断言的多半是**缺席**的那一条路：上游与客户端都不保证字段齐全，
 * 而「缺一个字段」在网关里的表现通常是 NPE 之后被某个 {@code catch} 吞掉。
 * 与其在每一处消费点各自判 null，不如在类型上收敛一次，并把这个收敛断言下来。
 */
@Tag("unit")
final class CanonicalTest {

  @Nested
  @DisplayName("缺字段")
  final class AbsentFields {

    @Test
    @DisplayName("请求缺字段时收敛成空列表与空参数，而不是 null")
    void requestNormalizesAbsentFields() {
      CanonicalRequest request = new CanonicalRequest(
          "r-1", "chat", "m", null, null, null, false, null);

      assertThat(request.messages()).isEmpty();
      assertThat(request.tools()).isEmpty();
      assertThat(request.params()).isEqualTo(GenerationParams.EMPTY);
      assertThat(request.passthrough()).isEmpty();
    }

    @Test
    @DisplayName("响应缺 choices 时是空列表；usage 仍然保持 null —— 空列表不等于「上游没给」")
    void responseKeepsUsageAbsent() {
      CanonicalResponse response = new CanonicalResponse("id", "m", null, null, null);

      assertThat(response.choices()).isEmpty();
      assertThat(response.usage()).isNull();
    }

    @Test
    @DisplayName("消息缺正文与工具缺描述时收敛成空串，不是 null")
    void textFieldsFallBackToEmptyText() {
      assertThat(new CanonicalMessage(CanonicalRole.USER, null).content()).isEmpty();
      assertThat(new CanonicalTool("tool", null, null).description()).isEmpty();
      assertThat(new CanonicalTool("tool", null, null).jsonSchema()).isEqualTo("{}");
    }
  }

  @Nested
  @DisplayName("停止原因")
  final class FinishReasons {

    @Test
    @DisplayName("已知取值复用同一个实例")
    void knownValuesAreShared() {
      assertThat(CanonicalFinishReason.of("stop")).isSameAs(CanonicalFinishReason.STOP);
    }

    @Test
    @DisplayName("未知取值原样保留：网关不替上游决定它是什么意思")
    void unknownValuesSurvive() {
      assertThat(CanonicalFinishReason.of("vendor_specific").raw())
          .isEqualTo("vendor_specific");
    }

    @Test
    @DisplayName("null 与已知取值都不炸：上游不给这个字段是常态")
    void nullDoesNotThrow() {
      assertThat(CanonicalFinishReason.of(null).raw()).isEqualTo("");
    }
  }

  @Nested
  @DisplayName("结算结果的形状")
  final class Outcomes {

    @Test
    @DisplayName("成功不需要错误形态")
    void successNeedsNoError() {
      Outcome outcome = new Outcome(
          "r-1", "chat", "http://127.0.0.1:11434/v1", OutcomeKind.SUCCESS,
          Duration.ofMillis(5), null, null);

      assertThat(outcome.kind()).isEqualTo(OutcomeKind.SUCCESS);
      assertThat(outcome.error()).isNull();
    }

    @Test
    @DisplayName("非成功必须有错误形态：缺了它，下游要么当作成功，要么各自补一个默认值")
    void nonSuccessMustCarryAnError() {
      assertThatThrownBy(() -> new Outcome(
          "r-1", "chat", "i-1", OutcomeKind.UPSTREAM_FAILURE, Duration.ZERO, null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("UPSTREAM_FAILURE");
    }

    @Test
    @DisplayName("失败携带的类别不从错误字符串反推：抛出点说得清，就在这里带上")
    void failureCarriesItsKind() {
      GatewayError error = new GatewayError("upstream_timeout", "上游没说完", 504);
      UpstreamFailure timeout = new UpstreamFailure(OutcomeKind.UPSTREAM_TIMEOUT, error, null);
      UpstreamFailure plain = new UpstreamFailure(error, null);

      assertThat(timeout.kind()).isEqualTo(OutcomeKind.UPSTREAM_TIMEOUT);
      assertThat(plain.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
      assertThat(plain.error()).isEqualTo(error);
    }
  }

  @Test
  @DisplayName("passthrough 对外是不可变的：它会被交给多个消费者，谁都不该改得动它")
  void passthroughIsImmutable() {
    CanonicalRequest request = new CanonicalRequest(
        "r-1", "chat", "m", List.of(), List.of(), GenerationParams.EMPTY, false,
        Map.of("vendor_ext", "1"));

    assertThatThrownBy(() -> request.passthrough().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
