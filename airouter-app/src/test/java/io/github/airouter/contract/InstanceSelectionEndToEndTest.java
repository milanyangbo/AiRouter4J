package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.core.Outcome;
import io.github.airouter.gateway.faces.openai.OpenAiFace;
import io.github.airouter.gateway.observe.SettlementLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
 * 选择链**接到进程上**的端到端断言：三个录制器当三个实例，从入站 HTTP 打进去。
 *
 * <p>这里只验接线，不重抄单测：策略的算术、加权的边界、拒绝的原因都在
 * {@code gateway} 的单测里判过，它们不需要容器也能判。
 * 端到端这一份回答的是另一个问题 —— **链真的挂在请求路径上了吗**：
 *
 * <ol>
 *   <li>流式与非流式走的是**同一条**序列（不是各有一条）
 *   <li>关闭着的实例一次都拿不到请求
 *   <li>权重在真实请求上生效：3:1 → 八次里恰好 6:2
 *   <li>选中的实例 id 进了结算记录
 * </ol>
 *
 * <p>**粘滞在这个上下文里是开着的**（它是配置，不是一个默认行为），
 * 于是前面那几条每次都要**换一个调用方**：同一个调用方会让它们全部落在同一个实例上，
 * 那时「权重有没有生效」反而看不出来了。第五条断言反过来看着同一件事的另一面 ——
 * 同一个调用方一路粘住不换。
 *
 * <p>权重生效那条断言写成「取八次 = 两整轮」，是为了与光标起点无关：
 * 轮询光标是进程级共享状态，它的起点随执行顺序而变，
 * 「第一次一定打给第一个实例」是一条换个执行顺序就红的断言。
 */
@SpringBootTest(classes = io.github.airouter.app.AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
final class InstanceSelectionEndToEndTest {

  private static final String MODEL = "recorded-model";

  private static final String HEAVY = "heavy";

  private static final String LIGHT = "light";

  private static final String OFF = "off";

  /** 粘滞那一条用的调用方标识。它在多条请求之间保持不变，粘滞才有东西可粘。 */
  private static final String TENANT = "tenant-sticky";

  private static final RecordedUpstream HEAVY_UPSTREAM = start();

  private static final RecordedUpstream LIGHT_UPSTREAM = start();

  private static final RecordedUpstream OFF_UPSTREAM = start();

  private WebTestClient client;

  /** 结算账本：断言「选中的那一个」是被记下来的那一个。 */
  @Autowired
  private SettlementLog settlementLog;

  private static RecordedUpstream start() {
    try {
      return new RecordedUpstream(0, 200, "{}");
    } catch (IOException failure) {
      throw new IllegalStateException("回放上游起不来", failure);
    }
  }

  /**
   * 三个实例：**权重 3 : 1，其中一个关着**。
   *
   * <p>每个实例的字段在这里**写全**：列表元素不跨配置来源合并，
   * 少给一个（哪怕是 enabled）就会让 application.yml 里那一份排不上用场，
   * 表现为那一项是默认值而不是「沿用配置文件」。
   */
  @DynamicPropertySource
  static void threeInstances(DynamicPropertyRegistry registry) {
    registry.add("airouter.default-service", () -> "chat");
    declare(registry, 0, HEAVY, 3, true, HEAVY_UPSTREAM::baseUrl);
    declare(registry, 1, LIGHT, 1, true, LIGHT_UPSTREAM::baseUrl);
    declare(registry, 2, OFF, 1, false, OFF_UPSTREAM::baseUrl);
    // 粘滞开着：第 ⑤ 步挂在策略外面这件事只能从这一层看出来 ——
    // 单测验的是包装器自己，验不到「装配把它包上去了没有」。
    registry.add("airouter.routing.sticky-enabled", () -> "true");
    registry.add("airouter.stream.idle-timeout", () -> "15s");
    registry.add("airouter.stream.max-content-chars", () -> "262144");
    registry.add("airouter.stream.max-frame-bytes", () -> "1048576");
    registry.add("airouter.stream.inbound-buffer-bytes", () -> "262144");
    registry.add("airouter.stream.outbound-buffer-bytes", () -> "1048576");
  }

  private static void declare(DynamicPropertyRegistry registry, int index, String id, int weight,
      boolean enabled, Supplier<String> baseUrl) {
    String prefix = "airouter.services.chat.instances[" + index + "].";
    registry.add(prefix + "id", () -> id);
    registry.add(prefix + "shape", () -> "openai");
    registry.add(prefix + "base-url", baseUrl::get);
    registry.add(prefix + "api-key", () -> "not-a-real-key");
    registry.add(prefix + "model", () -> MODEL);
    registry.add(prefix + "request-timeout", () -> "30s");
    registry.add(prefix + "weight", () -> String.valueOf(weight));
    registry.add(prefix + "enabled", () -> String.valueOf(enabled));
    registry.add(prefix + "tags", () -> "");
  }

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
  }

  /**
   * 流式与非流式共用同一条序列，且序列由权重决定。
   *
   * <p>这条断言的价值在于它抓得住「流式绕过选择」那一种接线错误：
   * 若流式那条路自己挑实例（比如固定打第一个），八次里 heavy 的占比会偏离 6/8，
   * 而单测看不见这种错 —— 它判的是链，不是谁在调链。
   *
   * <p>八次**各用一个新调用方**：粘滞会把同一个调用方钉在同一个实例上，
   * 那时这一条验的就不是权重了。
   */
  @Test
  @DisplayName("流式与非流式共用同一条序列：八次交替请求仍按 3:1 轮转，关着的那个一次都没有")
  void streamingAndNonStreamingShareOneSequence() {
    HEAVY_UPSTREAM.resetRequests();
    LIGHT_UPSTREAM.resetRequests();
    OFF_UPSTREAM.resetRequests();

    List<String> targets = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      targets.add(targetOf("sequence-caller-" + i, i % 2 == 0));
    }

    // 权重 3:1 → 两整轮恰好 6:2，与光标起点无关。
    assertThat(targets).filteredOn(HEAVY::equals).hasSize(6);
    assertThat(targets).filteredOn(LIGHT::equals).hasSize(2);
    // 关着的实例不参与选择：它一次都拿不到。
    assertThat(targets).doesNotContain(OFF);
    assertThat(OFF_UPSTREAM.requests()).as("关着的实例被请求的次数").isZero();
    // 序列以四个为一轮，与起点无关。
    for (int i = 0; i < 4; i++) {
      assertThat(targets.get(i + 4)).as("第 %d 次与一轮之后", i).isEqualTo(targets.get(i));
    }
  }

  @Test
  @DisplayName("选中的实例 id 进结算记录：打到谁就记谁，而且不是关着的那个")
  void settlementRecordsTheChosenInstance() {
    HEAVY_UPSTREAM.resetRequests();
    LIGHT_UPSTREAM.resetRequests();
    OFF_UPSTREAM.resetRequests();

    List<String> settled = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      settled.add(instanceOf(callNonStreaming("settlement-caller-" + i)));
    }

    assertThat(settled).as("结算里的实例 id").containsOnly(HEAVY, LIGHT);
    assertThat(settled).filteredOn(HEAVY::equals).hasSize(3);
    assertThat(settled).filteredOn(LIGHT::equals).hasSize(1);
  }

  /**
   * 粘滞真的挂在进程上：**同一个调用方一路粘在同一个实例上**，流式与非流式共用同一份记忆。
   *
   * <p>它会红的形状是「默认策略（轮询）没被粘滞包住」：那种情况下六次请求按 3:1 轮转，
   * 六次里必然出现两个不同的实例。这一份因此只回答「包上去了没有」——
   * 包装器自己的性质（换个实例、表有界、缺键拒绝）在 {@code gateway} 的单测里判。
   */
  @Test
  @DisplayName("粘滞开着时同一个调用方的六次请求落在同一个实例上（流式与非流式共用同一份记忆）")
  void stickyCallerStaysOnOneInstanceAcrossBothPaths() {
    HEAVY_UPSTREAM.resetRequests();
    LIGHT_UPSTREAM.resetRequests();
    OFF_UPSTREAM.resetRequests();

    List<String> targets = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      targets.add(targetOf(TENANT, i % 2 == 0));
    }

    assertThat(targets).as("同一个调用方的六次请求").containsOnly(targets.get(0));
    // 粘住的那个也不能是关着的那个：第 ② 步之前的筛选仍然生效。
    assertThat(targets.get(0)).isIn(HEAVY, LIGHT);
  }

  /** 打一次非流式请求，返回「请求标识 → 响应」这一对。 */
  private String callNonStreaming(String callerId) {
    HEAVY_UPSTREAM.replay(200, completionWith("H"));
    LIGHT_UPSTREAM.replay(200, completionWith("L"));
    OFF_UPSTREAM.replay(200, completionWith("OFF"));

    String requestId = client.post()
        .uri(OpenAiFace.PATH)
        .header("Content-Type", "application/json")
        .header(OpenAiFace.CALLER_ID_HEADER, callerId)
        .bodyValue(requestBody(false))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseHeaders()
        .getFirst(OpenAiFace.REQUEST_ID_HEADER);

    assertThat(requestId).as("响应头里的请求标识").isNotBlank();
    return requestId;
  }

  /** 打一次流式请求；两个录制器给出各自的标识，用来看请求落在谁身上。 */
  private void callStreaming(String callerId) {
    HEAVY_UPSTREAM.replayBytes(chunksWith("H"), Duration.ZERO);
    LIGHT_UPSTREAM.replayBytes(chunksWith("L"), Duration.ZERO);
    OFF_UPSTREAM.replayBytes(chunksWith("OFF"), Duration.ZERO);

    client.post()
        .uri(OpenAiFace.PATH)
        .header("Content-Type", "application/json")
        .header(OpenAiFace.CALLER_ID_HEADER, callerId)
        .bodyValue(requestBody(true))
        .exchange()
        .returnResult(String.class)
        .getResponseBody()
        .collectList()
        .block(Duration.ofSeconds(20));
  }

  /** 这一次的请求落在哪个实例上：看哪个录制器的计数动了。 */
  private String targetOf(String callerId, boolean stream) {
    int beforeHeavy = HEAVY_UPSTREAM.requests();
    int beforeLight = LIGHT_UPSTREAM.requests();
    int beforeOff = OFF_UPSTREAM.requests();

    if (stream) {
      callStreaming(callerId);
    } else {
      callNonStreaming(callerId);
    }

    int heavyDelta = HEAVY_UPSTREAM.requests() - beforeHeavy;
    int lightDelta = LIGHT_UPSTREAM.requests() - beforeLight;
    int offDelta = OFF_UPSTREAM.requests() - beforeOff;

    assertThat(heavyDelta + lightDelta + offDelta)
        .as("一次请求只应落到一个实例上")
        .isEqualTo(1);
    if (heavyDelta == 1) {
      return HEAVY;
    }
    return lightDelta == 1 ? LIGHT : OFF;
  }

  /**
   * 等到这一条请求的结算出现，取它记下的实例 id。
   *
   * <p>等条件成立而不是睡固定时长：结算发生在终态那一瞬间，与「客户端收完响应」
   * 之间隔着一次网络写，睡固定时长会让这条断言在慢机器上偶发地红。
   */
  private String instanceOf(String requestId) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      List<Outcome> settled = settlementLog.ofRequest(requestId);
      if (!settled.isEmpty()) {
        return settled.get(0).instanceId();
      }
      sleepBriefly();
    }
    throw new AssertionError("十秒内没有等到 " + requestId + " 的结算");
  }

  private static void sleepBriefly() {
    try {
      Thread.sleep(20L);
    } catch (InterruptedException expected) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待结算时被中断", expected);
    }
  }

  private static String requestBody(boolean stream) {
    return "{\"model\":\"" + MODEL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
        + "\"stream\":" + stream + "}";
  }

  private static String completionWith(String marker) {
    return "{\"id\":\"chatcmpl-sel\",\"object\":\"chat.completion\",\"model\":\"" + MODEL + "\","
        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + marker
        + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  private static List<byte[]> chunksWith(String marker) {
    String body = "data: {\"id\":\"chatcmpl-sel\",\"object\":\"chat.completion.chunk\","
        + "\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
        + marker + "\"},\"finish_reason\":null}]}\n\n"
        + "data: {\"id\":\"chatcmpl-sel\",\"object\":\"chat.completion.chunk\","
        + "\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"delta\":{},"
        + "\"finish_reason\":\"stop\"}]}\n\n"
        + "data: [DONE]\n\n";
    return List.of(body.getBytes(StandardCharsets.UTF_8));
  }
}
