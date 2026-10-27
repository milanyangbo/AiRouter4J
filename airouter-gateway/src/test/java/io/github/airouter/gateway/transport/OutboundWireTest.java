package io.github.airouter.gateway.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.UpstreamFailure;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 出站口在**对端缺席**时的行为。
 *
 * <p>「上游地址不可达」必须在类型上就与「上游拒绝了」分开：前者是网关与上游之间什么都没发生，
 * 后者是上游明确地说了不。把两者都收成一个 502，看日志的人会去查一个根本没参与的请求。
 *
 * <p>这里用内核刚释放的端口代替「挑一个没人用的端口」：后者在别人占用的那一刻会变成
 * 一条偶发绿（连上了却没断言任何东西）。
 */
@Tag("unit")
final class OutboundWireTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  @DisplayName("上游地址不可达：判定为 UPSTREAM_FAILURE，而不是成功也不是超时")
  void unreachableUpstreamIsAnUpstreamFailure() {
    OutboundWire wire = new OutboundWire("http://127.0.0.1:" + closedPort() + "/v1", TIMEOUT);

    assertThatThrownBy(() -> wire.post("/chat/completions", Map.of(), "{}").block())
        .isInstanceOf(UpstreamFailure.class)
        .satisfies(failure -> {
          UpstreamFailure upstream = (UpstreamFailure) failure;
          assertThat(upstream.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
          assertThat(upstream.error().type()).isEqualTo("upstream_unreachable");
          assertThat(upstream.error().httpStatus()).isEqualTo(502);
        });
  }

  /**
   * 一个确定没有在监听的端口。
   *
   * <p>先让内核分配再立刻关掉，得到的是「刚刚还在用、现在没人监听」的端口：
   * 连接它必定被拒，而占用它的可能性比随手挑一个端口号小得多。
   */
  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException failure) {
      throw new IllegalStateException("拿不到一个空闲端口", failure);
    }
  }
}
