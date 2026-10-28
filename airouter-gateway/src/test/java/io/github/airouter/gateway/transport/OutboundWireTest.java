package io.github.airouter.gateway.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.UpstreamFailure;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>后半段的两个测试看的不是错误形态，而是**出站口记的那本账**（在途计数）：
 * 它是 {@code least-connections} 的输入，而「忘了减一」这种错在这一层的表现是零星的，
 * 在那一层的表现却是「某台机器永远不被选中」。
 */
@Tag("unit")
final class OutboundWireTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  /** 这一份测试里那个实例的标识。它与真实配置无关，只需要能进出在途表。 */
  private static final String PROBE = "probe-instance";

  /** 聚合读取的上限。这里只是让它显式在场：这一份测试不打算撞它。 */
  private static final int BUFFER_BYTES = 1 << 20;

  private static final int MAX_FRAME_BYTES = 1 << 20;

  @Test
  @DisplayName("上游地址不可达：判定为 UPSTREAM_FAILURE，而不是成功也不是超时")
  void unreachableUpstreamIsAnUpstreamFailure() {
    OutboundWire wire = wire(closedPort());

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
   * 流式那条路同样要得到「够不着上游」，而且要在**订阅时**就得到。
   *
   * <p>它容易被写成「流正常结束」（空流）：那样客户端收到一个 200 加一段空流，
   * 看起来像「模型什么都没说」，而实际上一次请求都没发出去。
   */
  @Test
  @DisplayName("上游地址不可达时，流式同样判定为 UPSTREAM_FAILURE，而不是一条空流")
  void unreachableUpstreamFailsTheStreamToo() {
    OutboundWire wire = wire(closedPort());

    assertThatThrownBy(() -> wire.postStream("/chat/completions", Map.of(), "{}", TIMEOUT,
        SseFramer::new).collectList().block())
        .isInstanceOf(UpstreamFailure.class)
        .satisfies(failure -> {
          UpstreamFailure upstream = (UpstreamFailure) failure;
          assertThat(upstream.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
          assertThat(upstream.error().type()).isEqualTo("upstream_unreachable");
        });
  }

  /**
   * 在途计数：**每一次订阅加一，任何结束方式都减一**。
   *
   * <p>它是 {@code least-connections} 的输入，而这个策略对数错的最好报复方式是「永远不再选某一台机器」——
   * 因此这里要同时断言两个方向：加一发生在订阅时（{@code doOnSubscribe} 里读到的是 1），
   * 而**失败也算结束**（回到 0）。漏掉后者的经典后果是：一次连不上的调用永远占着一个名额。
   */
  @Test
  @DisplayName("出站口记着自己在途几次：订阅时加一，失败与取消都回到零")
  void inFlightCountingCountsEveryExit() {
    ActiveCallMeter meter = new ActiveCallMeter();
    OutboundWire wire = wire(meter, closedPort());
    AtomicInteger duringCall = new AtomicInteger(-1);

    assertThatThrownBy(() -> wire.post("/chat/completions", Map.of(), "{}")
        .doOnSubscribe(ignored -> duringCall.set(meter.activeOf(PROBE)))
        .block())
        .isInstanceOf(UpstreamFailure.class);

    assertThat(meter.activeOf(PROBE))
        .as("登记才有值：**没有请求过不等于不知道**，它应当答 0 而不是 null")
        .isEqualTo(0);
    assertThat(duringCall.get()).as("调用还在途中的计数").isEqualTo(1);
    assertThat(meter.activeOf("没有这个实例"))
        .as("没有登记过的实例：这里要说不知道，而不是说它闲着")
        .isNull();
  }

  /**
   * 流式那一条路同样记账。
   *
   * <p>**只断言「结束之后是 0」是不够的**：登记过的实例本来就该答 0，
   * 于是「压根没有计数」这种写法也能让它绿 —— 探红抓到的正是这个。
   * 因此这里与非流式那条一样，要看到**订阅时在途是 1**。
   */
  @Test
  @DisplayName("流式那一条路同样记账：订阅时加一，失败也要把名额还回去")
  void inFlightCountingCoversStreamingToo() {
    ActiveCallMeter meter = new ActiveCallMeter();
    OutboundWire wire = wire(meter, closedPort());
    AtomicInteger duringCall = new AtomicInteger(-1);

    assertThatThrownBy(() -> wire.postStream("/chat/completions", Map.of(), "{}", TIMEOUT,
        SseFramer::new)
        .doOnSubscribe(ignored -> duringCall.set(meter.activeOf(PROBE)))
        .collectList().block())
        .isInstanceOf(UpstreamFailure.class);

    assertThat(duringCall.get()).as("流式调用还在途中的计数").isEqualTo(1);
    assertThat(meter.activeOf(PROBE)).as("流式失败之后").isEqualTo(0);
  }

  private static OutboundWire wire(int port) {
    return wire(new ActiveCallMeter(), port);
  }

  private static OutboundWire wire(ActiveCallMeter meter, int port) {
    return new OutboundWire(PROBE, meter, "http://127.0.0.1:" + port + "/v1", TIMEOUT, BUFFER_BYTES,
        MAX_FRAME_BYTES);
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
