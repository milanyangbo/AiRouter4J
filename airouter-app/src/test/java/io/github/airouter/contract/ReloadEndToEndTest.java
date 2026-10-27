package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.gateway.config.ConfigurationLedger;
import io.github.airouter.gateway.config.ConfigurationSnapshot;
import io.github.airouter.gateway.config.InstanceDefinition;
import io.github.airouter.gateway.config.RegistryReloader;
import io.github.airouter.gateway.config.ServiceDefinition;
import io.github.airouter.gateway.config.ServicesProperties;
import io.github.airouter.gateway.observe.SettlementLog;
import io.github.airouter.gateway.providers.UpstreamShape;
import io.github.airouter.gateway.registry.UpstreamRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 热重载的端到端行为：从**入站 HTTP 打进去**，换配置，看请求去了哪里（ADR-0010 第 5、6 节）。
 *
 * <p>两个录制器各占一个端口，分别代表「变更前」与「变更后」的那个端点。
 * 「请求打到了哪一个」是这一类断言唯一诚实的证据：
 * 注册表里存的是出网对象，它去了哪里从对象上看不出来 —— 只有对端的计数能回答。
 *
 * <p>三条断言各自对应 ADR-0010 第 7 节里的一条验收：
 *
 * <ul>
 *   <li>**在途的流不受影响**：变更发生在一条流讲到一半的时候，那条流照旧走完并好好结束，
 *       而且没有被切到新的端点上
 *   <li>**不重建上下文**：进程已经学到的东西（这一阶段的结算账本）在变更之后还在，
 *       而它们所在的 bean 还是同一个 —— 重建上下文会另起一本空账
 *   <li>**回滚**：回滚之后请求回到上一版那个端点
 * </ul>
 *
 * <p>变更是**直接调入口**发起的，而不是改文件再等监听：本阶段还没有配置来源那一层，
 * 而「谁触发了变更」与「变更怎么被应用」是两件事 —— 后者先落地（ADR-0010 第 5 节）。
 */
@SpringBootTest(classes = AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
final class ReloadEndToEndTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String MODEL = "recorded-model";

  /** 块间隔。它短于块间上限（600ms），于是这条流是「一直在说话」而不是「静默」。 */
  private static final Duration BETWEEN_CHUNKS = Duration.ofMillis(300);

  private static final RecordedUpstream FIRST = startUpstream();

  private static final RecordedUpstream SECOND = startUpstream();

  private WebTestClient client;

  @Autowired
  private UpstreamRegistry registry;

  @Autowired
  private RegistryReloader reloader;

  @Autowired
  private ConfigurationLedger ledger;

  /** 结算账本：它是「进程已经学到了什么」在本阶段唯一可查的东西。 */
  @Autowired
  private SettlementLog settlementLog;

  @Autowired
  private ApplicationContext context;

  private static RecordedUpstream startUpstream() {
    try {
      return new RecordedUpstream(0, 200, "{}");
    } catch (IOException failure) {
      throw new IllegalStateException("回放上游起不来", failure);
    }
  }

  /**
   * 端点指向第一个录制器。
   *
   * <p>第二个录制器**不出现在配置里**：它只在变更之后才被指名，
   * 于是「变更前请求绝不会打到它」是一条有内容的断言 —— 它一开始压根不在配置中。
   */
  @DynamicPropertySource
  static void upstreamEndpoint(DynamicPropertyRegistry registry) {
    registry.add("airouter.default-service", () -> "chat");
    registry.add("airouter.services.chat.instances[0].id", () -> "recorded");
    registry.add("airouter.services.chat.instances[0].shape", () -> "openai");
    registry.add("airouter.services.chat.instances[0].base-url", FIRST::baseUrl);
    registry.add("airouter.services.chat.instances[0].api-key", () -> "not-a-real-key");
    registry.add("airouter.services.chat.instances[0].model", () -> MODEL);
    registry.add("airouter.services.chat.instances[0].request-timeout", () -> "2s");
    // 这个实例的字段在这里**写全**：列表元素不跨配置来源合并，少一个就表现为「那一项是默认值」。
    registry.add("airouter.services.chat.instances[0].weight", () -> "1");
    registry.add("airouter.services.chat.instances[0].enabled", () -> "true");
    registry.add("airouter.services.chat.instances[0].tags", () -> "");
    registry.add("airouter.stream.idle-timeout", () -> "600ms");
    registry.add("airouter.stream.max-content-chars", () -> "64");
    registry.add("airouter.stream.max-frame-bytes", () -> "1048576");
    registry.add("airouter.stream.inbound-buffer-bytes", () -> "262144");
    registry.add("airouter.stream.outbound-buffer-bytes", () -> "1048576");
  }

  /**
   * 每个用例都从「变更前的那一版」开始。
   *
   * <p>它是必要的：上下文在这一整个测试类里只有一份，一个用例里换过的端点会留到下一个用例，
   * 而「请求打到了哪一个」的断言会因此变成一条**与执行顺序有关**的断言 ——
   * 那正是最难复现的那类红。
   */
  @BeforeEach
  void resetToTheFirstUpstream(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
    reloader.reload(servicesAt(FIRST.baseUrl()));
  }

  /**
   * 变更发生在一条流讲到一半的时候。
   *
   * <p>在途的那一条照旧走完（内容完整、以哨兵收尾），而且**一步都没有走到新的端点上** ——
   * 它在开始时就取走了自己要用的那一个出网对象，之后的变更与它无关。
   * 变更之后**新的**请求才去新的端点。
   */
  @Test
  @DisplayName("变更发生在一条流讲到一半时：那条流照旧走完，之后的请求才去新的端点")
  void anInFlightStreamIsUnaffectedAndTheNextCallGoesToTheNewUpstream() {
    FIRST.replayStream(List.of(chunk("旧"), chunk("一"), chunk("二"), terminal(), done()),
        BETWEEN_CHUNKS);
    SECOND.replayStream(List.of(chunk("新"), terminal(), done()), Duration.ZERO);
    SECOND.resetRequests();
    long beforeReload = ledger.current().version();

    FluxExchangeResult<String> inFlight = exchangeStreamingResult();
    awaitUntil(() -> FIRST.chunksWritten() >= 2);
    ConfigurationSnapshot snapshot = reloader.reload(servicesAt(SECOND.baseUrl()));

    List<String> frames = framesOf(inFlight);
    assertThat(concatenated(frames)).as("在途那条流读到的内容").isEqualTo("旧一二");
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
    assertThat(SECOND.requests())
        .as("在途那条流被切到新端点上的次数：切过去一次就是热重载改了在途请求的目标")
        .isZero();
    assertThat(snapshot.version()).isEqualTo(beforeReload + 1);

    // 变更之后的请求：它走的是新的端点。
    List<String> after = exchangeStreaming();
    assertThat(concatenated(after)).as("变更之后那条流读到的内容").isEqualTo("新");
    assertThat(SECOND.requests()).isEqualTo(1);
  }

  /**
   * 热重载**不重建上下文**：进程已经学到的东西还在。
   *
   * <p>结算账本在本阶段是「进程学到的东西」唯一可查的那一处：
   * 重建上下文会另起一本空账，而那正是 ADR-0010 第 5 节说的「一次变更等于一次全站状态清零」。
   * 断言分两半 —— 变更之前的记录还在，且账本还是同一个对象。
   */
  @Test
  @DisplayName("变更不重建上下文：变更之前的结算还在，账本还是同一个")
  void aReloadDoesNotRestartTheContextNorForgetWhatItLearned() {
    FIRST.replay(200, """
        {"id":"chatcmpl-before","object":"chat.completion","model":"recorded-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"好"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}
        """);
    String requestId = client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody(false))
        .exchange()
        .expectBody()
        .returnResult()
        .getResponseHeaders()
        .getFirst("X-Airouter-Request-Id");
    awaitUntil(() -> !settlementLog.ofRequest(requestId).isEmpty());

    reloader.reload(servicesAt(SECOND.baseUrl()));

    assertThat(settlementLog.ofRequest(requestId))
        .as("变更之前那一次调用的结算：清掉它就是一次状态清零")
        .hasSize(1);
    assertThat(context.getBean(SettlementLog.class))
        .as("账本这个 bean：换了一个就说明上下文被重建过")
        .isSameAs(settlementLog);
    assertThat(registry.serviceIds()).containsExactly("chat");
  }

  /**
   * 回滚：请求回到上一版那个端点。
   *
   * <p>回滚产生的是**新的一版**（版本号前进），内容取自上一版 ——
   * 所以这里同时断言版本号与去向：只看去向会漏掉「版本号回退了」那一种实现，
   * 而那种实现会让副本之间无法用版本号判断谁新（ADR-0010 第 6 节）。
   */
  @Test
  @DisplayName("回滚之后请求回到上一版那个端点，且版本号仍然向前走")
  void rollbackSendsCallsBackToThePreviousUpstream() {
    FIRST.replayStream(List.of(chunk("旧"), chunk("一"), chunk("二"), terminal(), done()),
        BETWEEN_CHUNKS);
    SECOND.replayStream(List.of(chunk("新"), terminal(), done()), Duration.ZERO);

    reloader.reload(servicesAt(SECOND.baseUrl()));
    long beforeRollback = ledger.current().version();
    ConfigurationSnapshot rolledBack = reloader.rollback();

    assertThat(rolledBack.version()).isEqualTo(beforeRollback + 1);
    assertThat(rolledBack.services().services().get("chat").instances().get(0).baseUrl())
        .isEqualTo(FIRST.baseUrl());

    FIRST.resetRequests();
    SECOND.resetRequests();

    List<String> frames = exchangeStreaming();

    assertThat(concatenated(frames)).as("回滚之后读到的内容").isEqualTo("旧一二");
    assertThat(FIRST.requests()).isEqualTo(1);
    assertThat(SECOND.requests()).as("回滚之后还打到新端点的次数").isZero();
  }

  private FluxExchangeResult<String> exchangeStreamingResult() {
    return client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody(true))
        .exchange()
        .returnResult(String.class);
  }

  private List<String> exchangeStreaming() {
    return client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody(true))
        .exchange()
        .returnResult(String.class)
        .getResponseBody()
        .collectList()
        .block(Duration.ofSeconds(20));
  }

  private static List<String> framesOf(FluxExchangeResult<String> result) {
    return result.getResponseBody().collectList().block(Duration.ofSeconds(20));
  }

  private static String requestBody(boolean stream) {
    return "{\"model\":\"" + MODEL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
        + "\"stream\":" + stream + "}";
  }

  private static String concatenated(List<String> frames) {
    StringBuilder text = new StringBuilder();
    for (String frame : frames) {
      if ("[DONE]".equals(frame)) {
        continue;
      }
      JsonNode content = MAPPER.readTree(frame).path("choices").path(0).path("delta").path("content");
      if (!content.isMissingNode()) {
        text.append(content.asString(""));
      }
    }
    return text.toString();
  }

  /**
   * 等条件成立，不睡固定时长再比数字。
   *
   * <p>「上游已经写出两块」发生在另一个线程上，而且它取决于机器的快慢：
   * 睡一个固定时长去等它，会让「变更发生在流讲到一半」这一个前提在慢机器上落空 ——
   * 那时测的其实是「变更发生在流结束之后」，一条永远绿的断言。
   */
  private static void awaitUntil(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(10L);
      } catch (InterruptedException expected) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待条件时被中断", expected);
      }
    }
    throw new AssertionError("十秒内没有等到条件成立");
  }

  /** 一版只指向给定端点的配置。实例 id 不变 —— 它是「同一个实例换了地址」，不是换了个实例。 */
  private static ServicesProperties servicesAt(String baseUrl) {
    InstanceDefinition instance = new InstanceDefinition("recorded", UpstreamShape.OPENAI, null,
        baseUrl, "not-a-real-key", MODEL, Duration.ofSeconds(2), 1, true, List.of());
    return new ServicesProperties("chat",
        Map.of("chat", new ServiceDefinition(List.of(instance))));
  }

  private static String chunk(String content) {
    return "data: {\"id\":\"chatcmpl-reload\",\"object\":\"chat.completion.chunk\","
        + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
        + "\"finish_reason\":null}]}\n\n";
  }

  private static String terminal() {
    return "data: {\"id\":\"chatcmpl-reload\",\"object\":\"chat.completion.chunk\","
        + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
  }

  private static String done() {
    return "data: [DONE]\n\n";
  }
}
