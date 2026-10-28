package io.github.airouter.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.GenerationParams;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.faces.openai.OpenAiInboundCodec;
import io.github.airouter.gateway.providers.UpstreamShape;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.transport.ActiveCallMeter;
import io.github.airouter.gateway.transport.OutboundWire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 给定录制的上游字节，产出的响应必须与基线一致。
 *
 * <p>新增一个用例不需要改这段代码：在 {@code src/test/resources/contract/} 下建一个目录，
 * 放入 {@code upstream.json} 与 {@code expected.json} 两个文件即可自动被发现。
 * 两个文件的形状都是 {@code {"status": ..., "body": {...}}} —— 状态码与响应体一起参与比对，
 * 因为「上游 5xx 时给客户端什么码」本身就是契约的一部分。
 *
 * <p>比对是语义级的（解析后比对树），不断言键顺序：键顺序的变化不会影响任何客户端，
 * 把它当成契约只会带来无意义的基线改动。
 *
 * <p>这一份跑的是翻译层，不打 HTTP 入口 —— 要钉住的是「同一份字节进出之后变成什么」，
 * 与「HTTP 那一层怎么呈现」是两件事，后者由端到端测试守着。
 */
@Tag("integration")
final class GoldenWireContractTest {

  private static final Path CASES = Path.of("src/test/resources/contract");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** 聚合读取上限与单帧上限。这一份只跑非流式，两个值只要在场且合法即可。 */
  private static final int BUFFER_BYTES = 1 << 20;

  private static final int MAX_FRAME_BYTES = 1 << 20;

  private static final String MODEL = "recorded-model";

  /** 回放出来的网关结果与下游：状态码与响应体一起。 */
  private record Actual(int status, String body) {
  }

  @TestFactory
  @DisplayName("每一份录制的上游字节都产出与基线一致的响应")
  Stream<DynamicTest> recordedCases() throws IOException {
    return discoverCases().stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(),
        () -> assertProducedMatchesBaseline(dir)));
  }

  /**
   * 防「一个用例都没跑到还全绿」。
   *
   * <p>目录被挪走、资源被过滤掉、路径写错 —— 这些都不会报错，只会让上面的工厂默默产出零个测试。
   * 一个跑出零用例的契约测试和没有它是同一件事，所以这里显式钉住数量下限。
   */
  @Test
  @DisplayName("至少三组基线存在，且每组都同时有输入与期望两个文件")
  void everyCaseDirectoryHasBothFiles() throws IOException {
    List<Path> cases = discoverCases();

    assertEquals(true, cases.size() >= 3, "契约用例少于 3 组，检查一下是不是目录被挪走了：" + CASES);
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

    try (RecordedUpstream recorded =
             new RecordedUpstream(0, upstream.get("status").asInt(), upstream.get("body").toString())) {
      Actual actual = run(request(), recorded);

      assertEquals(expected.get("status").asInt(), actual.status(),
          "状态码不一致：" + dir.getFileName());
      JsonNode actualBody = MAPPER.readTree(actual.body());
      if (!actualBody.equals(expected.get("body"))) {
        fail("响应体与基线不一致：" + dir.getFileName()
            + "\n\n期望：\n" + expected.get("body").toPrettyString()
            + "\n\n实际：\n" + actualBody.toPrettyString());
      }
    }
  }

  private static Actual run(CanonicalRequest request, RecordedUpstream recorded) {
    OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
        new OutboundWire("golden", new ActiveCallMeter(), recorded.baseUrl(), TIMEOUT, BUFFER_BYTES,
            MAX_FRAME_BYTES),
        UpstreamShape.OPENAI.framerFactory(),
        "golden-key", MODEL, recorded.baseUrl(), TIMEOUT, 1 << 16, outcome -> { });
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

  private static CanonicalRequest request() {
    return new CanonicalRequest(
        "golden-1",
        "chat",
        MODEL,
        List.of(new CanonicalMessage(CanonicalRole.USER, "hi")),
        List.of(),
        GenerationParams.EMPTY,
        false,
        Map.of());
  }

  private static JsonNode readNode(Path path) throws IOException {
    return MAPPER.readTree(Files.readString(path));
  }
}
