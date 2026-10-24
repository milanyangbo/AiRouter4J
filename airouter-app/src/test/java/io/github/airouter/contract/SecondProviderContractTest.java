package io.github.airouter.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.GenerationParams;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.faces.openai.OpenAiInboundCodec;
import io.github.airouter.gateway.observe.SettlementObserver;
import io.github.airouter.gateway.providers.ChunkParseCounter;
import io.github.airouter.gateway.providers.ollama.OllamaProvider;
import io.github.airouter.gateway.transport.InFlightStreams;
import io.github.airouter.gateway.transport.OutboundWire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第二种 wire 形态的契约：给定录制的**原生**上游字节，客户端看到的仍然是 OpenAI 那一套。
 *
 * <p>它与 {@link GoldenWireContractTest} / {@link GoldenStreamContractTest} 是平行的两个目录，
 * 内容不同但比的东西是同一件：**对外 wire format**。差别只在输入那一侧 ——
 * 这里的样本是逐行 JSON（{@code ndjson}），那边是 SSE（{@code sse}）。
 * 不把它们塞进同一份用例表，是因为「同一份输入喂给两种形态」没有意义：
 * 一段 SSE 字节不是原生端点会说的话，把它喂给 Ollama 的翻译器只能得到一个人造的结论。
 *
 * <p>这一份存在的意义在于把「Face 不必知道对面是谁」变成可被推翻的东西：
 * 所有期望帧都是 OpenAI 的形态（{@code chat.completion.chunk}、{@code [DONE]}），
 * 而它们的来源是一份连 {@code choices} 这个字段都没有的原生响应。
 *
 * <p>样本来自本机 Ollama 的真实响应（见 {@code docs/runbook/local-openai-endpoint.md}），
 * 只有「上游 4xx」那一组是手写的 —— 错误体是 {@code {"error":"..."}} 这样的字符串，
 * 真实端点在模型不存在时确实这么回，这里只是把模型名换成了好认的。
 */
@Tag("integration")
final class SecondProviderContractTest {

  private static final Path CASES = Path.of("src/test/resources/contract-ollama");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final String MODEL = "recorded-model";

  /** 回放出来的网关结果与下游：状态码与响应体一起。 */
  private record Actual(int status, String body) {
  }

  @TestFactory
  @DisplayName("每一份录制的原生上游字节都产出与基线一致的客户端形态")
  Stream<DynamicTest> recordedCases() throws IOException {
    return discoverCases().stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(),
        () -> assertProducedMatchesBaseline(dir)));
  }

  /**
   * 防「一个用例都没跑到还全绿」，理由与另外两份契约测试相同。
   *
   * <p>这里额外钉住一件：**两种回放方式都要有**。只跑非流式会漏掉「没有哨兵时怎么认终态」，
   * 那正是这一份形态与 SSE 的实质差别所在。
   */
  @Test
  @DisplayName("至少四组基线存在，非流式与流式两种回放都覆盖到")
  void everyCaseDirectoryHasBothFiles() throws IOException {
    List<Path> cases = discoverCases();

    assertEquals(true, cases.size() >= 4, "原生契约用例少于 4 组，检查一下是不是目录被挪走了：" + CASES);
    long streaming = cases.stream()
        .filter(dir -> Files.exists(dir.resolve("upstream.json")))
        .map(SecondProviderContractTest::readUpstream)
        .filter(node -> node.has("ndjson"))
        .count();
    assertEquals(true, streaming >= 2, "流式用例少于 2 组：" + CASES);
    for (Path dir : cases) {
      assertEquals(true, Files.exists(dir.resolve("upstream.json")), dir + " 缺 upstream.json");
      assertEquals(true, Files.exists(dir.resolve("expected.json")), dir + " 缺 expected.json");
    }
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

  private static void assertProducedMatchesBaseline(Path dir) throws IOException {
    JsonNode upstream = readUpstream(dir);
    JsonNode expected = readNode(dir.resolve("expected.json"));
    int status = upstream.get("status").asInt();

    if (upstream.has("ndjson")) {
      assertStreamMatches(dir, status, upstream.get("ndjson").asString(), expected);
      return;
    }
    assertNonStreamingMatches(dir, status, upstream.get("body").toString(), expected);
  }

  private static void assertNonStreamingMatches(Path dir,
                                                int status,
                                                String upstreamBody,
                                                JsonNode expected) throws IOException {
    try (RecordedUpstream recorded = new RecordedUpstream(0, status, upstreamBody)) {
      Actual actual = run(request(false), recorded);

      assertEquals(expected.get("status").asInt(), actual.status(), "状态码不一致：" + dir.getFileName());
      JsonNode actualBody = MAPPER.readTree(actual.body());
      if (!actualBody.equals(expected.get("body"))) {
        fail("响应体与基线不一致：" + dir.getFileName()
            + "\n\n期望：\n" + expected.get("body").toPrettyString()
            + "\n\n实际：\n" + actualBody.toPrettyString());
      }
    }
  }

  /**
   * 流式比对，外加一条计数断言。
   *
   * <p>{@code CHUNK_PARSE_COUNT=1} 在第二种形态上同样要成立，而且更要紧：
   * 逐行 JSON 的每一帧都是一份完整 JSON，看起来「顺手再解析一次」毫无代价 ——
   * 同一份字节被解析两次在这里比在 SSE 那边更容易发生。
   */
  private static void assertStreamMatches(Path dir,
                                          int status,
                                          String ndjson,
                                          JsonNode expected) throws IOException {
    List<byte[]> lines = linesOf(ndjson);
    try (RecordedUpstream recorded = new RecordedUpstream(0, status, "")) {
      recorded.replayStream(status, lines, Duration.ZERO);
      ChunkParseCounter counter = new ChunkParseCounter();
      List<String> actual = produce(request(true), recorded, counter);
      JsonNode expectedFrames = expected.get("frames");

      assertEquals(lines.size(), counter.parsedFrames(),
          "逐行 JSON 的解析次数应当等于帧数：" + dir.getFileName());
      if (actual.size() != expectedFrames.size()) {
        fail("帧数与基线不一致：" + dir.getFileName()
            + "\n\n期望 " + expectedFrames.size() + " 帧：\n" + expectedFrames.toPrettyString()
            + "\n\n实际 " + actual.size() + " 帧：\n" + actual);
      }
      for (int i = 0; i < actual.size(); i++) {
        assertFrame(expectedFrames.get(i), actual.get(i), dir, i);
      }
    }
  }

  private static void assertFrame(JsonNode expected, String actual, Path dir, int index) {
    if (!expected.isObject()) {
      assertEquals(expected.asString(), actual, "第 " + index + " 帧与基线不一致：" + dir.getFileName());
      return;
    }
    JsonNode actualNode = MAPPER.readTree(actual);
    if (!actualNode.equals(expected)) {
      fail("第 " + index + " 帧与基线不一致：" + dir.getFileName()
          + "\n\n期望：\n" + expected.toPrettyString()
          + "\n\n实际：\n" + actualNode.toPrettyString());
    }
  }

  /** 逐行 JSON 按行切块：每一行是一个完整事件，块边界与事件边界一致。 */
  private static List<byte[]> linesOf(String ndjson) {
    List<byte[]> chunks = new ArrayList<>();
    for (String line : ndjson.split("\n", -1)) {
      if (!line.isEmpty()) {
        chunks.add((line + "\n").getBytes(StandardCharsets.UTF_8));
      }
    }
    return chunks;
  }

  private static Actual run(CanonicalRequest request, RecordedUpstream recorded) {
    OllamaProvider provider = provider(recorded, new ChunkParseCounter());
    try {
      CanonicalResponse response = provider.complete(request).toCompletableFuture().join();
      return new Actual(200, OpenAiInboundCodec.encodeResponse(response));
    } catch (CompletionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof UpstreamFailure upstream) {
        GatewayError error = upstream.error();
        return new Actual(error.httpStatus(), OpenAiInboundCodec.encodeError(error));
      }
      throw failure;
    }
  }

  /** 跑一遍流式翻译，产出客户端会看到的帧序列。编码那一侧与 SSE 那份测试是同一套，因此 Face 未被旁路。 */
  private static List<String> produce(CanonicalRequest request,
                                      RecordedUpstream recorded,
                                      ChunkParseCounter counter) {
    OllamaProvider provider = provider(recorded, counter);
    List<CanonicalStreamEvent> events = Flux
        .from(JdkFlowAdapter.flowPublisherToFlux(provider.stream(request)))
        .collectList()
        .block(TIMEOUT);

    String id = "";
    String model = "";
    Long created = null;
    List<String> frames = new ArrayList<>();
    for (CanonicalStreamEvent event : events) {
      if (event instanceof CanonicalStreamEvent.Opening opening) {
        id = opening.id();
        model = opening.model();
        created = opening.created();
        continue;
      }
      String payload = OpenAiInboundCodec.encodeStreamEvent(event, id, model, created);
      if (payload != null) {
        frames.add(payload);
      }
    }
    frames.add(OpenAiInboundCodec.DONE_SENTINEL);
    return frames;
  }

  /** 原生接口挂在 {@code /api} 上：地址形态也是 wire 形态的一部分。 */
  private static OllamaProvider provider(RecordedUpstream recorded, ChunkParseCounter counter) {
    String instance = recorded.root() + "/api";
    return new OllamaProvider(
        new OutboundWire(instance, TIMEOUT, TIMEOUT, new InFlightStreams()),
        MODEL,
        instance,
        new SettlementObserver(),
        counter);
  }

  private static CanonicalRequest request(boolean stream) {
    return new CanonicalRequest(
        "second-provider-1",
        "chat",
        MODEL,
        List.of(new CanonicalMessage(CanonicalRole.USER, "hi")),
        List.of(),
        GenerationParams.EMPTY,
        stream,
        Map.of());
  }

  private static JsonNode readUpstream(Path dir) {
    return readNode(dir.resolve("upstream.json"));
  }

  private static JsonNode readNode(Path path) {
    try {
      return MAPPER.readTree(Files.readString(path));
    } catch (IOException failure) {
      throw new IllegalStateException("读不了契约样本：" + path, failure);
    }
  }
}
