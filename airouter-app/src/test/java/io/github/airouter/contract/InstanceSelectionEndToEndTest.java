package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.core.Outcome;
import io.github.airouter.gateway.config.InstanceDefinition;
import io.github.airouter.gateway.config.RoutedUpstream;
import io.github.airouter.gateway.faces.openai.OpenAiFace;
import io.github.airouter.gateway.observe.SettlementLog;
import io.github.airouter.gateway.providers.ProviderInputs;
import io.github.airouter.gateway.providers.UpstreamProvider;
import io.github.airouter.gateway.providers.UpstreamProviderFactory;
import io.github.airouter.gateway.providers.UpstreamShape;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.ServiceView;
import io.github.airouter.gateway.routing.AvailabilityGate;
import io.github.airouter.gateway.routing.SelectionChain;
import io.github.airouter.gateway.select.LeastConnectionsSelector;
import io.github.airouter.gateway.select.RoundRobinSelector;
import io.github.airouter.gateway.transport.ActiveCallMeter;
import io.github.airouter.gateway.transport.OutboundWire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

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
 * <p>最后一个 {@code @Nested} 起的是**另一个上下文**：只换策略名与粘滞开关，实例还是这三个。
 * 它只验一条 —— 配了 {@code latency} 之后，被量到慢的那个实例的**份额**掉下去。
 * 其余策略的算术在 {@code gateway} 的单测里判过了，端到端不重抄一遍。
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

  /**
   * 真实的 Face bean。400 那一条断言打在它身上（见 {@code
   * streamingRejectionWithoutCallerIdentityReachesTheClientAsAnErrorBody} 的说明）。
   */
  @Autowired
  private OpenAiFace face;

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
    declareThreeInstances(registry);
    // 粘滞开着：第 ⑤ 步挂在策略外面这件事只能从这一层看出来 ——
    // 单测验的是包装器自己，验不到「装配把它包上去了没有」。
    registry.add("airouter.routing.sticky-enabled", () -> "true");
  }

  /**
   * 三个实例 + 流式的那五个值。**抽出来是为了让 {@code LatencyAwareSelection} 复用它**：
   * 那一个上下文是自己起的一份（{@code OVERRIDE} 之后外层这份动态属性不会带过去），
   * 而「两个上下文用的是同一批录制器」正是这一条断言要的 —— 只有策略名不同。
   */
  private static void declareThreeInstances(DynamicPropertyRegistry registry) {
    registry.add("airouter.default-service", () -> "chat");
    declare(registry, 0, HEAVY, 3, true, HEAVY_UPSTREAM::baseUrl);
    declare(registry, 1, LIGHT, 1, true, LIGHT_UPSTREAM::baseUrl);
    declare(registry, 2, OFF, 1, false, OFF_UPSTREAM::baseUrl);
    declareStreamLimits(registry);
  }

  /** 流式的那五个值。散值（非列表元素）本可由 {@code application.yml} 补齐，显式给全更稳。 */
  private static void declareStreamLimits(DynamicPropertyRegistry registry) {
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

  /**
   * 流式请求缺调用方标识：客户端拿到 400 的错误体，而不是 200 之后的断流。
   *
   * <p>打在**真实的 Face bean** 上（真链、真注册表、真粘滞 —— 这个上下文里粘滞开着，
   * 它正是要求标识的那一方），只是不起那条 TCP 连接：真实 HTTP 连接永远带远端地址，
   * 而远端地址正是缺显式标识时的最后一条来源 —— 「缺标识」在 HTTP 接缝上造不出来，
   * 于是这一条落在 Face 的入口方法上（票 12 的单元接缝）。
   *
   * <p>断言的是**客户端收到什么**：状态码、错误体里的 type、不出网、不结算。
   * 只断言状态码不够 —— 200 + 断流与 200 + 空流会让它绿，而那正是本票要消灭的形状。
   */
  @Test
  @DisplayName("流式缺调用方标识：400 错误体，不出网也不结算，不是 200 之后的断流")
  void streamingRejectionWithoutCallerIdentityReachesTheClientAsAnErrorBody() {
    int beforeHeavy = HEAVY_UPSTREAM.requests();
    int beforeLight = LIGHT_UPSTREAM.requests();
    int beforeOff = OFF_UPSTREAM.requests();

    ResponseEntity<Object> response = face.complete(Mono.just(requestBody(true)), null,
        MockServerHttpRequest.post(OpenAiFace.PATH).build())
        .block(Duration.ofSeconds(5));

    assertThat(response).as("拒绝要收口成一个响应，而不是断流").isNotNull();
    assertThat(response.getStatusCode().value())
        .as("缺标识是客户端能补的东西，所以是 400 而不是 503")
        .isEqualTo(400);
    assertThat((String) response.getBody()).contains("caller_identity_required");
    String requestId = response.getHeaders().getFirst(OpenAiFace.REQUEST_ID_HEADER);
    assertThat(requestId).as("错误响应同样带请求标识").isNotBlank();
    assertThat(settlementLog.ofRequest(requestId)).as("拒绝的那一次不结算").isEmpty();
    assertThat(HEAVY_UPSTREAM.requests()).as("拒绝的那一次不出网").isEqualTo(beforeHeavy);
    assertThat(LIGHT_UPSTREAM.requests()).isEqualTo(beforeLight);
    assertThat(OFF_UPSTREAM.requests()).isEqualTo(beforeOff);
  }

  /**
   * 流式请求选不出实例：客户端拿到 503 与同一份错误体，而不是 200 之后的断流。
   *
   * <p>**为什么打在不起容器的真实装配上**：启动期的配置闸（票 07）保证每个服务
   * 至少有一个启用着的实例，于是「候选为空」在容器装配里造不出来 ——
   * 它的真实来路是**运行时视图**：注册表里的实例被关掉之后（热重载，阶段 3），
   * 这一次请求看到的就是空候选。这里全是真实对象：真链、真 {@link RoutedUpstream}、
   * 真 Face，唯一的差别是注册表里那个实例是关闭着的。
   * 「不结算」在这条装配上是结构性的：结算只发生在上游事件之后，而这里没有上游。
   */
  @Test
  @DisplayName("流式选不出实例：503 与同一份错误体，不是 200 之后的断流")
  void streamingRejectionForNoUsableInstanceReachesTheClientAsAnErrorBody() {
    OpenAiFace isolated = new OpenAiFace(new RoutedUpstream(
        serviceId -> new ServiceView("chat", List.of(registered("closed", false))),
        "chat", new SelectionChain(AvailabilityGate.OPEN, new RoundRobinSelector())));

    ResponseEntity<Object> response = isolated.complete(Mono.just(requestBody(true)),
        "selection-rejection-caller", MockServerHttpRequest.post(OpenAiFace.PATH).build())
        .block(Duration.ofSeconds(5));

    assertThat(response).as("拒绝要收口成一个响应，而不是断流").isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat((String) response.getBody())
        .as("错误体与非流式是同一份（OpenAiInboundCodec.encodeError）")
        .contains("no_enabled_instances");
    assertThat(response.getHeaders().getFirst(OpenAiFace.REQUEST_ID_HEADER))
        .as("错误响应同样带请求标识")
        .isNotBlank();
  }

  /**
   * 流式请求查不到在途计数：503 {@code instance_selection_unavailable}，同一份错误体。
   *
   * <p>这一条的真实来路是**装配错误**（把在途计数接丢了一半），容器装配里造不出来；
   * 策略那一侧的显式失败已在 {@code InputDrivenStrategiesTest} 判过，
   * 这里判的是它经过 Face 之后客户端收到什么。在途计数是构造期注入的输入，
   * 给它一个「什么都不知道」的函数（真实签名下的退化值，不是 stub）——
   * 与 {@code least-connections} 的单测用的是同一把钥匙。
   */
  @Test
  @DisplayName("流式在途计数查不到：503 instance_selection_unavailable，同一份错误体")
  void streamingRejectionForUnknownActiveCountReachesTheClientAsAnErrorBody() {
    OpenAiFace isolated = new OpenAiFace(new RoutedUpstream(
        serviceId -> new ServiceView("chat", List.of(registered("lonely", true))),
        "chat", new SelectionChain(AvailabilityGate.OPEN,
            new LeastConnectionsSelector(instanceId -> null))));

    ResponseEntity<Object> response = isolated.complete(Mono.just(requestBody(true)),
        "active-count-caller", MockServerHttpRequest.post(OpenAiFace.PATH).build())
        .block(Duration.ofSeconds(5));

    assertThat(response).as("拒绝要收口成一个响应，而不是断流").isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat((String) response.getBody()).contains("instance_selection_unavailable");
    assertThat(response.getHeaders().getFirst(OpenAiFace.REQUEST_ID_HEADER))
        .as("错误响应同样带请求标识")
        .isNotBlank();
  }

  /**
   * 一只注册好的实例：真实 Provider，地址指向一个必然关着的端口。
   * 选择在这两条断言里抛出，Provider 永远不会被用到 —— 它在参数表里只是因为构造器要求它。
   */
  private static RegisteredInstance registered(String id, boolean enabled) {
    OutboundWire wire = new OutboundWire(id, new ActiveCallMeter(),
        "http://127.0.0.1:1/v1", Duration.ofSeconds(30), 1 << 16, 1 << 20);
    UpstreamProvider provider = new UpstreamProviderFactory().create(new ProviderInputs(wire,
        UpstreamShape.OPENAI, "not-a-real-key", MODEL, id,
        Duration.ofSeconds(15), 1 << 16, outcome -> { }));
    InstanceDefinition definition = new InstanceDefinition(id, UpstreamShape.OPENAI, null,
        "http://127.0.0.1:1/v1", "not-a-real-key", MODEL, Duration.ofSeconds(30), 1, enabled,
        List.of());
    return new RegisteredInstance(definition.id(), definition.shape(), definition.vendor(),
        definition.weight(), definition.enabled(), Set.copyOf(definition.tags()), provider);
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

  /**
   * **另一个上下文**：只换了策略名与粘滞开关，三个录制器还是同一批。
   *
     * <p>看的是 {@code latency} 这一条链路真的接到进程上了：
     * 慢的实例被量到之后，它的份额掉下去。
     * 单测验的是「给定样本怎么算权重」，验不到「样本从哪里来、有没有真的在喂策略」 ——
     * 而这两件事的接线错了以后，表现恰恰是「配了延迟感知，流量还是平均走」。
   */
  @Nested
  @Tag("integration")
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(classes = io.github.airouter.app.AiRouterApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @TestPropertySource(properties = {
      "airouter.routing.strategy=latency",
      "airouter.routing.sticky-enabled=false",
      "airouter.routing.latency.alpha=0.5",
      "airouter.routing.latency.failure-penalty-millis=30000",
      "airouter.routing.latency.retention-millis=600000"})
  final class LatencyAwareSelection {

    /**
     * 慢的那一个：三个事件之间各留这么久，于是它要花上**两倍这个数**才走到终态。
     *
     * <p>快的那一边是同机回环上的几毫秒，两者相差两个数量级 ——
     * 差得不够远时「谁更快」会变成一件要看运气的事，而这一条断言要的是必然。
     */
    private static final long SLOW_BETWEEN_MILLIS = 300L;

    /**
     * 这一个上下文**自己**声明实例：{@code OVERRIDE} 之后外层的动态属性不会带过来，
     * 于是这里复用的只是「声明哪三个实例」这一件事（{@code declareThreeInstances}），
     * 三个录制器仍是同一批静态对象。
     */
    @DynamicPropertySource
    static void sameThreeInstances(DynamicPropertyRegistry registry) {
      declareThreeInstances(registry);
    }

    @Autowired
    private SettlementLog ledger;

    private WebTestClient client;

    @BeforeEach
    void bindClient(@Value("${local.server.port}") int port) {
      client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + port)
          .responseTimeout(Duration.ofSeconds(30))
          .build();
    }

    /**
     * 八个请求：慢的那个先被量到两次，之后流量基本不再落到它身上。
     *
     * <p>**为什么它仍然可以是「最后四次都在快的那一个上」这个形状 —— 尽管加权口径下
     * 慢的那个并没有被排除。** 量到之后它的份额是 3% 上下（315 毫秒对几毫秒，
     * 有效权重 = 3/(1+315) 比上 1/(1+3)），八次里因此落不到它身上一次：
     * 这是加权该有的结果，不是「硬排除」的证据。
     * **「份额下降但仍然大于零」这一半不在这里判** —— 3% 的份额要跑几百次才量得出来，
     * 而每次打给慢的那个都要花上三百毫秒；那条性质在 {@code gateway} 的单测里判，
     * 那里一次选择只是几次浮点加法（票 11）。
     *
     * <p>开头的两次落在慢的那个身上也不是巧合：那时候**两个实例都还没有样本**，
     * 于是它们同档、按声明权重 3:1 走 —— 这正是「没有样本不算最快也不算最慢」那一档
     * 在真实请求上的样子。少了「至少打到过一次慢的」那一条断言，
     * 一个压根没量过任何实例的实现也能绿。
     *
     * <p>实测的八次是 {@code [heavy, heavy, light, light, light, light, light, light]}；
     * 把延迟换成「不看延迟」之后它立刻变回 {@code [heavy, heavy, light, heavy, heavy, heavy,
     * light, heavy]} —— 正好是按权重 3:1 轮转的形状，所以这一条断言抓得住接线错误。
     *
     * <p>**每一次都要等到它的结算落地**，再发下一个请求。少了这一等，
     * 下一个请求会在上一个的样本还没被记下来的时候就发出去，
     * 于是它看到的是「两个实例都没有历史」—— 这种写法红得毫无规律，
     * 而它恰恰是「端到端断言不能靠睡眠」这条规矩的来源。
     *
     * <p>**每次换一个调用方**，于是这一条断言与粘滞开关的取值无关：
     * 粘滞若开着（它是配置，不是一个默认行为），同一个调用方会被钉在同一个实例上，
     * 那时这一条验的是粘滞而不是延迟。换着换调用方，粘滞无论开着关着都退场 ——
     * 少了这一句，把默认粘滞改成开着的那一天，这条断言会静默变成另一条断言。
     */
    @Test
    @DisplayName("配了 latency：被量到慢的那个实例之后基本不再拿到流量（最后四次都在快的那一个上）")
    void slowInstanceStopsReceivingTraffic() {
      HEAVY_UPSTREAM.resetRequests();
      LIGHT_UPSTREAM.resetRequests();
      OFF_UPSTREAM.resetRequests();

      List<String> targets = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        targets.add(targetOfOneStreamingCall("latency-caller-" + index));
      }

      assertThat(targets).as("至少有一次打到了慢的那个，否则它没有样本可谈").contains(HEAVY);
      assertThat(targets.subList(4, 8))
          .as("收敛之后四次；八次依次落在 %s", targets)
          .containsOnly(LIGHT);
    }

    /**
     * 打一次流式请求；慢的那个**分三块、块间各留一段静默**，快的那个一次写完。
     *
     * <p>为什么必须分块：录制器的静默只发生在**块与块之间**，
     * 一块写完的模式（{@code chunksWith}）再大的静默也落不下去 ——
     * 那时两个实例一样快，延迟感知退化成按权重轮询，这一条断言验的就不再是延迟了。
     */
    private String targetOfOneStreamingCall(String callerId) {
      int beforeHeavy = HEAVY_UPSTREAM.requests();
      int beforeLight = LIGHT_UPSTREAM.requests();
      int beforeOff = OFF_UPSTREAM.requests();

      HEAVY_UPSTREAM.replayBytes(staggeredChunks("H"), slowBetween());
      LIGHT_UPSTREAM.replayBytes(chunksWith("L"), Duration.ZERO);
      OFF_UPSTREAM.replayBytes(chunksWith("OFF"), Duration.ZERO);

      FluxExchangeResult<String> result = client.post()
          .uri(OpenAiFace.PATH)
          .header("Content-Type", "application/json")
          .header(OpenAiFace.CALLER_ID_HEADER, callerId)
          .bodyValue(requestBody(true))
          .exchange()
          .returnResult(String.class);

      String requestId = result.getResponseHeaders().getFirst(OpenAiFace.REQUEST_ID_HEADER);
      assertThat(requestId).as("响应头里的请求标识").isNotBlank();
      // 把整条流读完：只看响应头就走的话，这一次调用在客户端这一侧已经结束了，
      // 而上游还没把话说完 —— 那时被测的不是「这次调用有多慢」，是「客户端有多快放弃」。
      String body = result.getResponseBody().collectList().block(Duration.ofSeconds(20))
          .stream().collect(Collectors.joining());
      // 断言它真的走到终态：样本只在终态那一刻产生（ADR-0005），
      // 一条没走完的流要么被记成取消（不采纳），要么记成失败（用惩罚值）——
      // 两种情形之下这一条验的都不是延迟。
      assertThat(body)
          .as("这一次流式调用要走完终态，否则它产不出关于快慢的样本")
          .endsWith("[DONE]");
      awaitSettlement(requestId);

      int heavyDelta = HEAVY_UPSTREAM.requests() - beforeHeavy;
      assertThat(heavyDelta + LIGHT_UPSTREAM.requests() - beforeLight
          + OFF_UPSTREAM.requests() - beforeOff)
          .as("一次请求只应落到一个实例上")
          .isEqualTo(1);
      return heavyDelta == 1 ? HEAVY : LIGHT;
    }

    /**
     * 等到这一条请求的结算出现在账本里。
     *
     * <p>结算落地也是**延迟样本落地**的同一个时刻（两者都是 {@code OutcomeObserver}），
     * 于是等它不只是为了好看：下一个请求要看得见这一个请求学到的东西。
     */
    private void awaitSettlement(String requestId) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        if (!ledger.ofRequest(requestId).isEmpty()) {
          return;
        }
        sleepBriefly();
      }
      throw new AssertionError("十秒内没有等到 " + requestId + " 的结算");
    }

    private Duration slowBetween() {
      return Duration.ofMillis(SLOW_BETWEEN_MILLIS);
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

  /** 一块写完的 SSE 样本：快的那一种上游。 */
  private static List<byte[]> chunksWith(String marker) {
    return List.of(sseBody(marker).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 同一份 SSE 样本**按事件拆成三块**：慢的那一种上游靠它把静默落下去。
   *
   * <p>在 {@code \n\n} 处切而不是按字节数切：前者保证每一块都是完整的事件，
   * 于是「多字节字符被劈成两半」那一条断言仍然是它自己那条路造出来的形状，
   * 这里不会顺手把它造出来一次。
   */
  private static List<byte[]> staggeredChunks(String marker) {
    String body = sseBody(marker);
    int firstCut = body.indexOf("\n\n") + 2;
    int secondCut = body.indexOf("\n\n", firstCut) + 2;
    return List.of(
        body.substring(0, firstCut).getBytes(StandardCharsets.UTF_8),
        body.substring(firstCut, secondCut).getBytes(StandardCharsets.UTF_8),
        body.substring(secondCut).getBytes(StandardCharsets.UTF_8));
  }

  private static String sseBody(String marker) {
    return "data: {\"id\":\"chatcmpl-sel\",\"object\":\"chat.completion.chunk\","
        + "\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
        + marker + "\"},\"finish_reason\":null}]}\n\n"
        + "data: {\"id\":\"chatcmpl-sel\",\"object\":\"chat.completion.chunk\","
        + "\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"delta\":{},"
        + "\"finish_reason\":\"stop\"}]}\n\n"
        + "data: [DONE]\n\n";
  }
}
