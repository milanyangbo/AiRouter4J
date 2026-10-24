package io.github.airouter.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.GenerationParams;
import io.github.airouter.gateway.faces.openai.OpenAiInboundCodec;
import io.github.airouter.gateway.observe.SettlementObserver;
import io.github.airouter.gateway.providers.ChunkParseCounter;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.transport.InFlightStreams;
import io.github.airouter.gateway.transport.OutboundWire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 给定录制的上游 SSE 字节，产出的帧序列必须与基线一致。
 *
 * <p>它是 {@link GoldenWireContractTest} 的流式对应物，两者分工与那边一致：
 * 新增一个用例不需要改代码，在 {@code src/test/resources/contract-stream/} 下建一个目录，
 * 放入 {@code upstream.json} 与 {@code expected.json} 即可自动被发现。
 *
 * <p>比对的对象是**客户端会看到的每一帧**，并且包含 {@code [DONE]} 哨兵本身 ——
 * 哨兵的出现时机与出现次数是流式契约里最容易在重构中丢掉的东西：
 * 丢了它客户端就不知道该停止读取，而服务端这一侧一切正常。
 *
 * <p>比对是语义级的（解析后比对树），不断言键顺序。
 */
@Tag("integration")
final class GoldenStreamContractTest {

  private static final Path CASES = Path.of("src/test/resources/contract-stream");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final String MODEL = "recorded-model";

  @TestFactory
  @DisplayName("每一份录制的上游 SSE 字节都产出与基线一致的帧序列")
  Stream<DynamicTest> recordedCases() throws IOException {
    return discoverCases().stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(),
        () -> assertProducedMatchesBaseline(dir)));
  }

  /** 防「一个用例都没跑到还全绿」，理由与非流式那一侧相同。 */
  @Test
  @DisplayName("至少三组流式基线存在，且每组都同时有输入与期望两个文件")
  void everyCaseDirectoryHasBothFiles() throws IOException {
    List<Path> cases = discoverCases();

    assertEquals(true, cases.size() >= 3, "流式契约用例少于 3 组，检查一下是不是目录被挪走了：" + CASES);
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
    JsonNode upstream = readNode(dir.resolve("upstream.json"));
    JsonNode expected = readNode(dir.resolve("expected.json"));
    int status = upstream.get("status").asInt();

    try (RecordedUpstream recorded = new RecordedUpstream(0, status, "")) {
      recorded.replayStream(status, upstream.get("sse").asString());
      List<String> actual = produce(request(), recorded);
      JsonNode expectedFrames = expected.get("frames");

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

  /**
   * 跑一遍流式翻译，产出客户端会看到的帧序列。
   *
   * <p>它走的是 Provider + Face 编解码这一段，不经过 HTTP 客户端：
   * 契约要钉住的是「字节 → 帧」的映射，而不是某一种客户端的读法。
   */
  private static List<String> produce(CanonicalRequest request, RecordedUpstream recorded) {
    OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
        new OutboundWire(recorded.baseUrl(), TIMEOUT, TIMEOUT, new InFlightStreams()),
        "golden-key",
        MODEL,
        recorded.baseUrl(),
        new SettlementObserver(),
        new ChunkParseCounter());

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

  private static JsonNode readNode(Path path) throws IOException {
    return MAPPER.readTree(Files.readString(path));
  }
}
