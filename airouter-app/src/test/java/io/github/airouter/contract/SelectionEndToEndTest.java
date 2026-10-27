package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.gateway.config.InstanceDefinition;
import io.github.airouter.gateway.config.RegistryReloader;
import io.github.airouter.gateway.config.ServiceDefinition;
import io.github.airouter.gateway.config.ServicesProperties;
import io.github.airouter.gateway.providers.UpstreamShape;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 选择链的端到端行为：**从入站 HTTP 打进去，看请求落在哪一个实例上**（ADR-0011）。
 *
 * <p>两个录制器各代表一个实例。「打到了哪一个」是这一类断言唯一诚实的证据：
 * 挑实例的结果只存在于那一刻的一次调用里，读任何组件自己的状态
 * 都只能证明它记得自己是什么状态，证明不了请求真的去了哪儿。
 *
 * <p>三条断言对着 ADR-0011 第 4 节与阶段 4 的验收：
 *
 * <ul>
 *   <li>**流式与非流式走同一条链**：两种调用交替进行，各自都把光标推进一步 ——
 *       流式若绕过选择（来源项目正是这样），它就会一直落在同一个实例上
 *   <li>**加权生效**：权重 3:1 的两个实例在八次调用里是六次与两次
 *   <li>**关闭的实例不参与**：把其中一个关掉之后，它一次也收不到
 * </ul>
 *
 * <p>断言都写成**与光标起点无关**的形式：轮询的光标是进程级共享状态，
 * 而这个上下文在这一整个测试类里只有一份。写成「第一次一定打给第一个」，
 * 断言就会变成一条与执行顺序有关的断言 —— 那是最难复现的那类红。
 */
@SpringBootTest(classes = AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
final class SelectionEndToEndTest {

  private static final String MODEL = "recorded-model";

  private static final RecordedUpstream FIRST = startUpstream();

  private static final RecordedUpstream SECOND = startUpstream();

  private WebTestClient client;

  @Autowired
  private RegistryReloader reloader;

  private static RecordedUpstream startUpstream() {
    try {
      return new RecordedUpstream(0, 200, "{}");
    } catch (IOException failure) {
      throw new IllegalStateException("回放上游起不来", failure);
    }
  }

  /**
   * 两个实例，等权重。
   *
   * <p>每一个元素的字段都在这里**写全**：Spring Boot 绑定列表元素时不跨配置来源合并，
   * 少一个就表现为「那一项是默认值」而不是「这一项没配」。
   */
  @DynamicPropertySource
  static void twoInstances(DynamicPropertyRegistry registry) {
    registry.add("airouter.default-service", () -> "chat");
    registry.add("airouter.services.chat.instances[0].id", () -> "first");
    registry.add("airouter.services.chat.instances[0].shape", () -> "openai");
    registry.add("airouter.services.chat.instances[0].base-url", FIRST::baseUrl);
    registry.add("airouter.services.chat.instances[0].api-key", () -> "not-a-real-key");
    registry.add("airouter.services.chat.instances[0].model", () -> MODEL);
    registry.add("airouter.services.chat.instances[0].request-timeout", () -> "2s");
    registry.add("airouter.services.chat.instances[0].weight", () -> "1");
    registry.add("airouter.services.chat.instances[0].enabled", () -> "true");
    registry.add("airouter.services.chat.instances[0].tags", () -> "");
    registry.add("airouter.services.chat.instances[1].id", () -> "second");
    registry.add("airouter.services.chat.instances[1].shape", () -> "openai");
    registry.add("airouter.services.chat.instances[1].base-url", SECOND::baseUrl);
    registry.add("airouter.services.chat.instances[1].api-key", () -> "not-a-real-key");
    registry.add("airouter.services.chat.instances[1].model", () -> MODEL);
    registry.add("airouter.services.chat.instances[1].request-timeout", () -> "2s");
    registry.add("airouter.services.chat.instances[1].weight", () -> "1");
    registry.add("airouter.services.chat.instances[1].enabled", () -> "true");
    registry.add("airouter.services.chat.instances[1].tags", () -> "");
    registry.add("airouter.stream.idle-timeout", () -> "600ms");
    registry.add("airouter.stream.max-content-chars", () -> "64");
    registry.add("airouter.stream.max-frame-bytes", () -> "1048576");
    registry.add("airouter.stream.inbound-buffer-bytes", () -> "262144");
    registry.add("airouter.stream.outbound-buffer-bytes", () -> "1048576");
  }

  /**
   * 每个用例都从「两个实例、等权重」那一版开始，并把两个录制器的样本与计数归零。
   *
   * <p>变更（换权重、关实例）只发生在用例内部，下一个用例不继承它 ——
   * 否则「请求打到了哪一个」会变成一条与执行顺序有关的断言。
   */
  @BeforeEach
  void resetToTwoEqualInstances(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
    reloader.reload(services(1, 1, true));
    FIRST.replay(200, completion("一"));
    SECOND.replay(200, completion("二"));
  }

  /**
   * 流式与非流式走**同一条链**。
   *
   * <p>两种调用交替四次：若流式绕过选择（比如直接取第一个实例），
   * 它就会一直落在同一个实例上，于是「一 / 二」不会交替出现。
   * 断言写成「第一次与第三次同一个、第二次与第四次同一个、两者不同」——
   * 它与光标的起点无关，但足以证明流式的那一次同样推进了光标。
   */
  @Test
  @DisplayName("流式与非流式走同一条链：两种调用交替命中两个实例")
  void streamingAndNonStreamingShareOneChain() {
    String first = nonStreamingCall();
    String second = streamingCall();
    String third = nonStreamingCall();
    String fourth = streamingCall();

    assertThat(first).isEqualTo(third);
    assertThat(second).isEqualTo(fourth);
    assertThat(first).as("两次调用落在了同一个实例上：其中一条路没有走选择链").isNotEqualTo(second);
    assertThat(FIRST.requests() + SECOND.requests()).isEqualTo(4);
    assertThat(FIRST.requests()).isEqualTo(2);
    assertThat(SECOND.requests()).isEqualTo(2);
  }

  /**
   * 加权：权重 3:1 的两个实例在八次调用里是六次与两次。
   *
   * <p>取**八次**而不是四次，是为了让结论与光标起点无关：
   * 权重之和是四，任意连续八次都恰好是两整轮。
   */
  @Test
  @DisplayName("权重 3:1：八次调用里是六次与两次，而不是各四次")
  void weightedInstancesAreChosenInProportionToTheirWeight() {
    reloader.reload(services(3, 1, true));
    FIRST.resetRequests();
    SECOND.resetRequests();

    for (int i = 0; i < 8; i++) {
      nonStreamingCall();
    }

    assertThat(FIRST.requests()).as("权重 3 的那个实例被选中的次数").isEqualTo(6);
    assertThat(SECOND.requests()).as("权重 1 的那个实例被选中的次数").isEqualTo(2);
  }

  /**
   * 关闭的实例不参与选择。
   *
   * <p>它留在注册表里（热重载可能把它打开），但一次也收不到请求 ——
   * 「关掉一个实例」是运维在故障现场最常用的那一招，它必须立刻生效。
   */
  @Test
  @DisplayName("关闭的实例一次也收不到请求")
  void aDisabledInstanceIsNeverChosen() {
    reloader.reload(services(1, 1, false));
    FIRST.resetRequests();
    SECOND.resetRequests();

    for (int i = 0; i < 4; i++) {
      assertThat(nonStreamingCall()).isEqualTo("一");
    }

    assertThat(FIRST.requests()).isEqualTo(4);
    assertThat(SECOND.requests()).as("被关掉的那个实例收到的请求次数").isZero();
  }

  /**
   * 一次非流式调用：两个上游先换回非流式样本，再把响应里的读出来。
   *
   * <p>样本每次重设而不只在开头设一次：同一个录制器只有一种回放模式，
   * 而这条用例要在流式与非流式之间来回切 ——
   * 流式那条路若拿到的是一次成型的 JSON，测的就不是流式了。
   */
  private String nonStreamingCall() {
    FIRST.replay(200, completion("一"));
    SECOND.replay(200, completion("二"));
    return content(nonStreaming());
  }

  /** 一次流式调用：两个上游先换成 SSE 样本。 */
  private String streamingCall() {
    FIRST.replayStream(chunks("一"), Duration.ZERO);
    SECOND.replayStream(chunks("二"), Duration.ZERO);
    return content(streaming());
  }

  /** 一次非流式调用，返回响应体原文。 */
  private String nonStreaming() {
    return client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody(false))
        .exchange()
        .expectStatus().isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  /** 一次流式调用，返回 SSE 帧的文本。 */
  private String streaming() {
    List<String> frames = client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody(true))
        .exchange()
        .expectStatus().isOk()
        .returnResult(String.class)
        .getResponseBody()
        .collectList()
        .block(Duration.ofSeconds(20));
    return frames == null ? "" : String.join("", frames);
  }

  /**
   * 从响应里取出内容。
   *
   * <p>它同时是「请求真的到了那一个上游」的证据：两个上游回放的是不同的内容，
   * 于是内容本身就说明了调用落在哪儿，而不必只靠计数。
   */
  private String content(String raw) {
    return raw.contains("\"content\":\"一\"") ? "一" : raw.contains("\"content\":\"二\"") ? "二" : raw;
  }

  private static String requestBody(boolean stream) {
    return "{\"model\":\"" + MODEL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
        + "\"stream\":" + stream + "}";
  }

  private static String completion(String content) {
    return "{\"id\":\"chatcmpl-selection\",\"object\":\"chat.completion\",\"model\":\"" + MODEL
        + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
        + content + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  /** 一份完整的 SSE 样本：一块内容 + 一个终态块 + 哨兵。缺了终态块就不是一次成功的流。 */
  private static List<String> chunks(String content) {
    return List.of(
        "data: {\"id\":\"chatcmpl-selection\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
            + "\"finish_reason\":null}]}\n\n",
        "data: {\"id\":\"chatcmpl-selection\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
            + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
        "data: [DONE]\n\n");
  }

  /**
   * 一版配置：两个实例，权重与「第二个是否启用」由参数给出。
   *
   * <p>实例 id **保持不变**：改权重与改启用标志都是「同一个实例的治理位变了」，
   * 换了 id 就变成「换了一个实例」，出网对象会被重建 —— 那不是这里要试的事。
   */
  private static ServicesProperties services(int firstWeight, int secondWeight,
      boolean secondEnabled) {
    InstanceDefinition first = new InstanceDefinition("first", UpstreamShape.OPENAI, null,
        FIRST.baseUrl(), "not-a-real-key", MODEL, Duration.ofSeconds(2), firstWeight, true,
        List.of());
    InstanceDefinition second = new InstanceDefinition("second", UpstreamShape.OPENAI, null,
        SECOND.baseUrl(), "not-a-real-key", MODEL, Duration.ofSeconds(2), secondWeight,
        secondEnabled, List.of());
    ServiceDefinition chat = new ServiceDefinition(List.of(first, second));
    return new ServicesProperties("chat", Map.of("chat", chat));
  }
}
