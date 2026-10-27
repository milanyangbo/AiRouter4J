package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.GenerationParams;
import io.github.airouter.gateway.faces.openai.OpenAiInboundCodec;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.transport.OutboundWire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.adapter.JdkFlowAdapter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 流式路径的端到端行为：**从入站 HTTP 打进去**，内部不替换任何组件。
 *
 * <p>被替换掉的只有远端那个端点 —— 用 {@link RecordedUpstream} 按块回放固定的字节。
 * 「上游怎么切块」与「块间静默多久」都在这里可控，而这两件事正是流式最容易写错的地方。
 *
 * <p>本票只走正常路径：上游好好说话、好好结束。异常终态（不讲完、静默、取消）归票 04。
 */
@SpringBootTest(classes = io.github.airouter.app.AiRouterApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
final class StreamingEndToEndTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 块间静默上限。它**长于**长流用例里每一段的间隔，短于那条流的总时长。 */
  private static final Duration IDLE_TIMEOUT = Duration.ofMillis(600);

  /** 累积内容上限。截断用例故意越过它。 */
  private static final int MAX_CONTENT_CHARS = 8;

  private static final int BUFFER_BYTES = 1 << 20;

  private static final int MAX_FRAME_BYTES = 1 << 20;

  private static final String MODEL = "recorded-model";

  private static final Path CASES = Path.of("src/test/resources/contract-stream");

  private static final RecordedUpstream UPSTREAM = startUpstream();

  private WebTestClient client;

  private static RecordedUpstream startUpstream() {
    try {
      return new RecordedUpstream(0, 200, "{}");
    } catch (IOException failure) {
      throw new IllegalStateException("回放上游起不来", failure);
    }
  }

  /**
   * 端点指向录制器；SLO 三角的五个值在这里显式给出（与 {@code application.yml} 同一套名字）。
   *
   * <p>{@code request-timeout} 刻意只有 2 秒：块间时长那条用例要靠「总时长 > 它」来证明
   * 流式没有套用非流式那个总上限。
   */
  @DynamicPropertySource
  static void upstreamEndpoint(DynamicPropertyRegistry registry) {
    registry.add("airouter.endpoint.base-url", UPSTREAM::baseUrl);
    registry.add("airouter.endpoint.api-key", () -> "not-a-real-key");
    registry.add("airouter.endpoint.model", () -> MODEL);
    registry.add("airouter.endpoint.request-timeout", () -> "2s");
    registry.add("airouter.stream.idle-timeout", () -> "600ms");
    registry.add("airouter.stream.max-content-chars", () -> "8");
    registry.add("airouter.stream.max-frame-bytes", () -> "1048576");
    registry.add("airouter.stream.inbound-buffer-bytes", () -> "262144");
    registry.add("airouter.stream.outbound-buffer-bytes", () -> "1048576");
  }

  @BeforeEach
  void bindClient(@Value("${local.server.port}") int port) {
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        // 客户端这一侧的超时刻意给得比任何一条用例都长：
        // 「对 text/event-stream 不设响应超时」是被断言的一方，不能先被测试自己掐断。
        .responseTimeout(Duration.ofSeconds(30))
        .build();
  }

  /**
   * 一次正常的流式调用：逐块拿到内容、以哨兵收尾，且走的是与非流式**同一条**出站路径
   * （同一个路径、同一个凭证 —— 治理挂在端口上，两个端口来自同一个对象）。
   */
  @Test
  @DisplayName("流式请求以 SSE 逐块拿到内容，并以 [DONE] 收尾")
  void streamingCallYieldsChunksAndDoneSentinel() {
    UPSTREAM.resetRequests();
    UPSTREAM.replayStream(List.of(chunkWith("你"), chunkWith("好"), chunkWith("吗"),
        terminalChunk("stop"), done()), Duration.ZERO);

    List<String> frames = exchangeStreaming();

    assertThat(frames).isNotEmpty();
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
    assertThat(concatenatedContent(frames)).isEqualTo("你好吗");
    assertThat(UPSTREAM.requests()).isEqualTo(1);
    assertThat(UPSTREAM.lastPath()).isEqualTo("/v1/chat/completions");
    assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer not-a-real-key");
  }

  /**
   * 上游还没讲完，客户端就已经收到了前面的块。
   *
   * <p>它是「背压向两端传导」能被看见的那一面：攒够了再发的实现（{@code collectList()}、
   * 或者任何先把流读进内存的一步）会让这一条红 —— 客户端只在最后才一次性拿到全部。
   */
  @Test
  @DisplayName("上游还没讲完时，客户端已经收到了前面的块 —— 没有被攒起来再发")
  void firstChunkArrivesBeforeUpstreamFinishes() {
    UPSTREAM.replayStream(List.of(chunkWith("你"), chunkWith("好"), chunkWith("吗"),
        terminalChunk("stop"), done()), Duration.ofMillis(400));

    AtomicInteger writtenWhenFirstArrived = new AtomicInteger(-1);
    List<String> frames = client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody(true))
        .exchange()
        .returnResult(String.class)
        .getResponseBody()
        .doOnNext(frame -> writtenWhenFirstArrived.compareAndSet(-1, UPSTREAM.chunksWritten()))
        .collectList()
        .block(Duration.ofSeconds(20));

    assertThat(frames).isNotEmpty();
    // 五块还没写完就已经有东西到了客户端。攒够再发的实现会在这里给出 5。
    assertThat(writtenWhenFirstArrived.get())
        .as("第一帧到达时上游已经写出的块数")
        .isLessThan(5);
    // 剩下的仍然要好好结束：终态与哨兵都在。
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
  }

  /**
   * 块间时长 vs 总时长。
   *
   * <p>这条流的总时长（约 2.5 秒）**超过**非流式那个总上限（2 秒），
   * 但每一段间隔（500ms）都在块间上限（600ms）之内。它必须完整结束 ——
   * 给流式套上总时长上限的实现会在这里掐断它，客户端拿到的是一条没有终态的半截响应。
   */
  @Test
  @DisplayName("idleTimeout 是块间时长：一条总时长超过非流式上限的流仍然完整结束")
  void idleTimeoutIsBetweenChunksNotTotal() {
    UPSTREAM.replayStream(List.of(chunkWith("慢"), chunkWith("慢"), chunkWith("来"),
        chunkWith("吧"), terminalChunk("stop"), done()), Duration.ofMillis(500));

    List<String> frames = exchangeStreaming();

    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
    assertThat(concatenatedContent(frames)).isEqualTo("慢慢来吧");
  }

  /**
   * 累积内容超限：截断只影响展示。
   *
   * <p>上限是 8 个字符，这里上游给了 13 个。客户端应当只收到 8 个，
   * 但**终态与哨兵照旧** —— 反过来（超限就断流）会让客户端拿到一条没有终态的响应，
   * 而「没有终态」在结算里是最难归因的那一种。
   */
  @Test
  @DisplayName("累积内容超过上限时截断展示，但终态与哨兵照旧")
  void contentBeyondCapIsTruncatedButStillTerminates() {
    UPSTREAM.replayStream(List.of(chunkWith("这是一段明显超过上限的内容"), terminalChunk("stop"), done()),
        Duration.ZERO);

    List<String> frames = exchangeStreaming();

    assertThat(concatenatedContent(frames)).hasSize(MAX_CONTENT_CHARS);
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
  }

  /**
   * 终态之后的帧不出现在客户端。
   *
   * <p>上游在哨兵之后又发了几块（真实世界里它发生在超时与重试交叠的时候）。
   * 客户端收到的最后一帧必须是哨兵本身。
   */
  @Test
  @DisplayName("哨兵之后上游又发来的帧不出现在客户端")
  void framesAfterSentinelDoNotReachClient() {
    UPSTREAM.replayStream(List.of(chunkWith("完"), terminalChunk("stop"), done(),
        chunkWith("迟到"), terminalChunk("stop"), done()), Duration.ZERO);

    List<String> frames = exchangeStreaming();

    assertThat(frames).filteredOn(frame -> frame.contains("迟到")).isEmpty();
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
  }

  /**
   * 上游保活既不冒充内容，也不冒充结局。
   *
   * <p>注释帧与空 {@code data:} 帧都不该产生客户端可见的东西 ——
   * 当成内容会在正文里冒出空行，当成结束会让一条好好的流提前收尾。
   */
  @Test
  @DisplayName("上游保活（注释与空帧）不产生客户端帧，也不影响终态")
  void keepaliveDoesNotBecomeContentOrTerminal() {
    UPSTREAM.replayStream(List.of(": 上游还在想\n\n", "data:\n\n", chunkWith("在"),
        ": 又想了一会儿\n\n", terminalChunk("stop"), done()), Duration.ZERO);

    List<String> frames = exchangeStreaming();

    assertThat(concatenatedContent(frames)).isEqualTo("在");
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
  }

  /**
   * 每一份录制的流式字节都产出与基线一致的**帧序列**。
   *
   * <p>新增用例不需要改代码：在 {@code src/test/resources/contract-stream/} 下建一个目录，
   * 放入 {@code upstream.json} 与 {@code expected.json} 即可（形状见该目录下的 README）。
   *
   * <p>这一份跑的是翻译层（Provider + 编码），不打 HTTP 入口 ——
   * 与上面那些端到端断言是两件事：前者钉「同一份字节进出之后变成哪一串帧」，
   * 后者钉「HTTP 那一层怎么把这一串帧交给客户端」。
   */
  @TestFactory
  @DisplayName("每一份录制的流式字节都产出与基线一致的帧序列")
  Stream<DynamicTest> recordedCases() throws IOException {
    return discoverCases().stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(),
        () -> assertFramesMatchBaseline(dir)));
  }

  /** 防「一个用例都没跑到还全绿」。目录被挪走或路径写错都不会报错，只会默默产出零个测试。 */
  @Test
  @DisplayName("至少四组流式基线存在，且每组都同时有输入与期望两个文件")
  void everyCaseDirectoryHasBothFiles() throws IOException {
    List<Path> cases = discoverCases();

    assertThat(cases).as("流式契约用例少于 4 组，检查一下是不是目录被挪走了：" + CASES)
        .hasSizeGreaterThanOrEqualTo(4);
    for (Path dir : cases) {
      assertThat(Files.exists(dir.resolve("upstream.json"))).as(dir + " 缺 upstream.json").isTrue();
      assertThat(Files.exists(dir.resolve("expected.json"))).as(dir + " 缺 expected.json").isTrue();
    }
  }

  private static void assertFramesMatchBaseline(Path dir) throws IOException {
    JsonNode upstream = MAPPER.readTree(Files.readString(dir.resolve("upstream.json")));
    JsonNode expected = MAPPER.readTree(Files.readString(dir.resolve("expected.json")));
    List<String> chunks = new ArrayList<>();
    upstream.get("chunks").forEach(node -> chunks.add(node.asString()));
    List<String> expectedFrames = new ArrayList<>();
    expected.get("frames").forEach(node -> expectedFrames.add(node.asString()));

    try (RecordedUpstream recorded = new RecordedUpstream(0, 200, "{}")) {
      recorded.replayStream(chunks, Duration.ZERO);

      assertThat(semantic(framesFrom(recorded)))
          .as("帧序列与基线不一致：" + dir.getFileName())
          .isEqualTo(semantic(expectedFrames));
    }
  }

  /**
   * 语义化：能解析成 JSON 的按树比（键顺序不算差异），解析不了的按原文比（哨兵就是后者）。
   *
   * <p>键顺序的变化不会影响任何客户端，把它当成契约只会带来无意义的基线改动。
   */
  private static List<Object> semantic(List<String> frames) {
    return frames.stream().map(frame -> {
      try {
        return (Object) MAPPER.readTree(frame);
      } catch (JacksonException expected) {
        return frame;
      }
    }).toList();
  }

  /** 一次流式调用产出哪些客户端帧（走 Provider，不走 HTTP 入口）。 */
  private static List<String> framesFrom(RecordedUpstream recorded) {
    OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
        new OutboundWire(recorded.baseUrl(), Duration.ofSeconds(10), BUFFER_BYTES, MAX_FRAME_BYTES),
        "golden-key", MODEL, IDLE_TIMEOUT, 1 << 16);
    OpenAiInboundCodec.StreamEncoder encoder = OpenAiInboundCodec.newStreamEncoder();
    return JdkFlowAdapter.flowPublisherToFlux(provider.stream(request()))
        .concatMapIterable(encoder::encode)
        .collectList()
        .block(Duration.ofSeconds(10));
  }

  private static List<Path> discoverCases() throws IOException {
    try (Stream<Path> children = Files.list(CASES)) {
      return children
          .filter(Files::isDirectory)
          .filter(dir -> Files.exists(dir.resolve("expected.json")))
          .sorted()
          .toList();
    }
  }

  /** 打一次流式请求，返回客户端收到的帧（按到达顺序）。 */
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

  private static String requestBody(boolean stream) {
    return "{\"model\":\"" + MODEL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
        + "\"stream\":" + stream + "}";
  }

  private static String concatenatedContent(List<String> frames) {
    StringBuilder text = new StringBuilder();
    for (String frame : frames) {
      if ("[DONE]".equals(frame)) {
        continue;
      }
      JsonNode delta = MAPPER.readTree(frame).path("choices").path(0).path("delta");
      if (!delta.path("content").isMissingNode()) {
        text.append(delta.path("content").asString(""));
      }
    }
    return text.toString();
  }

  private static String chunkWith(String content) {
    return "data: {\"id\":\"chatcmpl-e2e\",\"object\":\"chat.completion.chunk\","
        + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
        + "\"finish_reason\":null}]}\n\n";
  }

  private static String terminalChunk(String reason) {
    return "data: {\"id\":\"chatcmpl-e2e\",\"object\":\"chat.completion.chunk\","
        + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason + "\"}]}\n\n";
  }

  private static String done() {
    return "data: [DONE]\n\n";
  }

  private static CanonicalRequest request() {
    return new CanonicalRequest(
        "golden-stream-1",
        "chat",
        MODEL,
        List.of(new CanonicalMessage(CanonicalRole.USER, "hi")),
        List.of(),
        GenerationParams.EMPTY,
        true,
        Map.of());
  }
}
