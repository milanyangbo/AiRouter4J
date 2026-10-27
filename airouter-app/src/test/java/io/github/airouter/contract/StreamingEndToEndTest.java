package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.GenerationParams;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.gateway.faces.openai.OpenAiInboundCodec;
import io.github.airouter.gateway.observe.SettlementLog;
import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import io.github.airouter.gateway.transport.OutboundWire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
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
 * <p>覆盖两类路径：上游好好说话、好好结束的正常路径，以及「不讲完、静默、中途断开」
 * 三种异常终态。后三种各自都要在结算账本里留下**自己那一条**，
 * 而它们的判定互不相同 —— 这正是流式结算的全部内容：上游不讲完就不是成功，
 * 调用方断开不是上游失败，一条流只留一条。
 *
 * <p>还有一类是**字节层的分块任意性**：上游怎么切 TCP 段与帧的边界毫无关系，
 * 一个三字节的汉字被劈成两半也必须读成完整的字。这一类只在录制器按字节块回放时才造得出来 ——
 * 真实端点切不出这些形状。
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

  /**
   * 块间隔。刻意留得比「一次 socket 写」长：不留的话内核会把相邻的小块合成一个 TCP 段，
   * 「切在两个段之间」就根本没有发生 —— 测试看起来在测分块，其实测的是合并后的那一段。
   */
  private static final int SEGMENT_GAP_MS = 200;

  /** 生成式切点的种子。property-based 的失败必须能复现。 */
  private static final long SPLIT_SEED = 20261027L;

  private static final String MODEL = "recorded-model";

  private static final Path CASES = Path.of("src/test/resources/contract-stream");

  private static final RecordedUpstream UPSTREAM = startUpstream();

  private WebTestClient client;

  /**
   * 结算账本：{@code OutcomeObserver} 在本阶段的落地。
   *
   * <p>断言落在它上面，是因为「这次调用被记成了什么」只有它能回答：
   * 客户端看到的只是最后一帧，而「上游失败」与「调用方取消」在帧上是同一种东西（都不是哨兵）。
   */
  @Autowired
  private SettlementLog settlementLog;

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

    FluxExchangeResult<String> result = exchangeStreamingResult();
    String requestId = requestIdOf(result);
    List<String> frames = framesOf(result);

    assertThat(frames).isNotEmpty();
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
    assertThat(concatenatedContent(frames)).isEqualTo("你好吗");
    assertThat(UPSTREAM.requests()).isEqualTo(1);
    assertThat(UPSTREAM.lastPath()).isEqualTo("/v1/chat/completions");
    assertThat(UPSTREAM.lastAuthorization()).isEqualTo("Bearer not-a-real-key");
    // 好好结束的那一条：结算类别是成功，而且只有一条。
    assertThat(soleSettlementOf(requestId).kind()).isEqualTo(OutcomeKind.SUCCESS);
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
   * 一个多字节字符被切在两个 TCP 段之间。
   *
   * <p>三种切法都走一遍：切在这个三字节字的内部（第一字节后、第二字节后），
   * 以及一个字节一块 —— 后者一次覆盖**所有**可能的切点。
   *
   * <p>读错的表现是替换字符（{@code U+FFFD}）或者干脆少一个字：先把字节解码成字符串再拼接的实现
   * 会在块边界上产生它。这种故障只在响应够长、网络够忙的时候出现，属于最难复现的那一类。
   */
  @Test
  @DisplayName("多字节字符被切在两个 TCP 段之间：仍然读成完整的字，不出现替换字符")
  void multiByteCharacterSplitAcrossSegmentsIsStillReadWhole() {
    String body = chunkWith("你") + chunkWith("好") + chunkWith("吗") + terminalChunk("stop") + done();
    int start = byteOffsetOf(body, "你");

    for (int cut : List.of(start, start + 1, start + 2)) {
      UPSTREAM.replayBytes(splitAt(body, List.of(cut)), Duration.ofMillis(SEGMENT_GAP_MS));

      List<String> frames = exchangeStreaming();

      assertThat(concatenatedContent(frames))
          .as("切在第 " + cut + " 个字节处读到的内容")
          .isEqualTo("你好吗");
      assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
    }

    UPSTREAM.replayBytes(splitAt(body, everyOffsetOf(body)), Duration.ofMillis(1));

    List<String> frames = exchangeStreaming();

    assertThat(concatenatedContent(frames)).as("一个字节一块时读到的内容").isEqualTo("你好吗");
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
  }

  /**
   * 空 {@code data:} 帧不是内容。
   *
   * <p>上游用它做保活。当成内容的后果不是报错，而是客户端正文里冒出空行 ——
   * 一种只在「上游压力大、保活发得多」的时候才看得出来的脏数据。
   */
  @Test
  @DisplayName("空 data: 帧不是内容：上游保活不在客户端正文里冒出空行")
  void emptyDataFramesAreNotContent() {
    UPSTREAM.replayStream(List.of("data:\n\n", "data: \n\n", "data:   \n\n",
        chunkWith("在"), "data:\n\n", terminalChunk("stop"), done()), Duration.ZERO);

    List<String> frames = exchangeStreaming();

    assertThat(frames).as("客户端收到的帧：空帧不该出现").doesNotContain("");
    // 开场 + 内容 + 终态 + 哨兵：多出来的那一帧就是有空帧被当成了内容。
    assertThat(frames).hasSize(4);
    assertThat(contents(frames)).containsExactly("在");
    assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
  }

  /**
   * 只有保活的流**没有终态**。
   *
   * <p>保活两个方向都不能冒充别的东西：不冒充内容（见上面两条），也不冒充结局 ——
   * 一条一直在保活、但始终没讲完的流，与一条静默的流在判定上是同一类：上游没把话说完。
   * 上游不发保活时的那种「连接还在但没有任何字节」的待命状态，正是靠保活区分的，
   * 所以保活本身不为这条流提供任何「它进行得顺利」的证据。
   */
  @Test
  @DisplayName("只有保活的流没有终态：保活不冒充结局，仍然判为上游没讲完")
  void keepaliveOnlyStreamHasNoTerminalAndIsNotSuccess() {
    UPSTREAM.replayStream(List.of(": 还在想\n\n", ": 又想了一会儿\n\n"), Duration.ZERO);

    FluxExchangeResult<String> result = exchangeStreamingResult();
    String requestId = requestIdOf(result);
    List<String> frames = framesOf(result);

    // 保活不产生客户端帧；剩下的只有「上游没给终态就把连接收了」那一帧失败。
    assertThat(frames).as("客户端收到的帧").hasSize(1);
    assertThat(frames.get(0)).contains("upstream_closed");
    assertThat(soleSettlementOf(requestId).kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
  }

  /**
   * 同一块字节只被翻译一次（{@code CHUNK_PARSE_COUNT = 1}）。
   *
   * <p>「解析了几次」是一个内部计数，没有外部可观测的计数器；
   * 而为了测它往生产代码里塞一个计数器，等于让测试决定生产代码长什么样。
   * 于是这里断言它在外的**两个**后果：上游只被请求一次（同一份字节没有被读两遍），
   * 以及每一块的内容在客户端恰好出现一次、顺序不变 —— 重复解析在「译码 → 发事件」
   * 这条链上的唯一外在表现就是内容重复，或者被后面那一次覆盖掉。
   */
  @Test
  @DisplayName("同一块上游字节只被翻译一次：内容不重复、不丢失、顺序不变")
  void eachUpstreamChunkIsTranslatedExactlyOnce() {
    UPSTREAM.resetRequests();
    UPSTREAM.replayStream(List.of(chunkWith("甲"), chunkWith("乙"), chunkWith("丙"), chunkWith("丁"),
        terminalChunk("stop"), done()), Duration.ZERO);

    List<String> frames = exchangeStreaming();

    assertThat(UPSTREAM.requests()).as("上游被请求的次数").isEqualTo(1);
    // 开场 + 四块内容 + 终态 + 哨兵：多一帧意味着某一块被解析了两次，少一帧意味着某一块被吞了。
    assertThat(frames).hasSize(7);
    assertThat(contents(frames)).containsExactly("甲", "乙", "丙", "丁");
  }

  /**
   * 任意分块方式下合并结果唯一。
   *
   * <p>输入是**生成**的而不是手写的：三组固定步长加二十组随机切点。
   * 手写几种切法只能证明那几种恰好没问题，而这里要的不变量与切法无关 ——
   * 一旦有人让分帧依赖「块通常是完整的」这个假设，随机切点会在某一次把它撞出来。
   *
   * <p>种子固定，失败消息带上切点：property-based 的失败必须能复现，也要能说出是哪一组输入。
   */
  @Test
  @DisplayName("任意分块方式下合并结果唯一：生成式切点下的帧序列与一次收到全部完全相同")
  void arbitrarySplittingYieldsOneMergedResult() {
    String body = chunkWith("你") + chunkWith("好") + chunkWith("吗") + terminalChunk("stop") + done();
    int length = body.getBytes(StandardCharsets.UTF_8).length;

    UPSTREAM.replayStream(List.of(body), Duration.ZERO);
    List<String> reference = exchangeStreaming();
    assertThat(reference).as("对照：一次收到全部").isNotEmpty();

    List<List<Integer>> splittings = new ArrayList<>();
    splittings.add(everyNth(length, 3));
    splittings.add(everyNth(length, 17));
    splittings.add(List.of(length / 2));
    Random random = new Random(SPLIT_SEED);
    for (int round = 0; round < 20; round++) {
      splittings.add(randomCuts(length, random));
    }

    for (List<Integer> cuts : splittings) {
      UPSTREAM.replayBytes(splitAt(body, cuts), Duration.ofMillis(1));

      assertThat(exchangeStreaming())
          .as("切点 " + cuts + " 下客户端收到的帧序列")
          .isEqualTo(reference);
    }
  }

  /**
   * 上游收了连接、说了几句，然后**没给终态就把连接关了**。
   *
   * <p>这不是成功 —— 内容是否完整**无从判断**：连接关掉的那一刻，最后一段是不是完整，
   * 谁也不知道。把它记成成功，就是来源项目最贵的那个缺陷本身：
   * 一个持续吐出半截响应的实例，在「响应头写出即成功」的口径里是 100% 成功。
   *
   * <p>客户端那一侧同样要看得出来：失败之后**不补哨兵**。补了哨兵，
   * 客户端就会照着「正常结束」去收尾，而这一次调用其实是失败的。
   */
  @Test
  @DisplayName("上游没给终态就收了连接：补一个失败，不补成功，也不补哨兵")
  void upstreamClosedWithoutTerminalIsNotSuccess() {
    UPSTREAM.replayStream(List.of(chunkWith("半"), chunkWith("截")), Duration.ZERO);

    FluxExchangeResult<String> result = exchangeStreamingResult();
    String requestId = requestIdOf(result);
    List<String> frames = framesOf(result);

    assertThat(frames.get(frames.size() - 1))
        .as("最后一帧：失败之后不补哨兵，补了就是把失败说成正常结束")
        .doesNotContain("[DONE]")
        .contains("upstream_closed");

    Outcome outcome = soleSettlementOf(requestId);
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.UPSTREAM_FAILURE);
    assertThat(outcome.error().type()).isEqualTo("upstream_closed");
  }

  /**
   * 讲了几句之后**静默**。
   *
   * <p>它既不是成功（没把话说完），也不是「上游拒绝」（连得上、也说过话）。
   * 只有块间静默上限能判它，而这条路径在真实世界里只在网络抖动或对端卡死的时候出现 ——
   * 靠运气等它发生，等于没有测试。
   */
  @Test
  @DisplayName("上游讲了几句之后静默：静默超过块间上限判为超时，不判为成功")
  void silenceBeyondIdleTimeoutIsUpstreamTimeout() {
    // 静默 3 秒，远长于块间上限 600ms。
    UPSTREAM.holdOpen(List.of(chunkWith("想"), chunkWith("一")), Duration.ofSeconds(3));

    FluxExchangeResult<String> result = exchangeStreamingResult();
    String requestId = requestIdOf(result);
    List<String> frames = framesOf(result);

    assertThat(frames.get(frames.size() - 1)).contains("upstream_timeout");

    Outcome outcome = soleSettlementOf(requestId);
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.UPSTREAM_TIMEOUT);
    assertThat(outcome.error().type()).isEqualTo("upstream_timeout");
  }

  /**
   * 调用方中途断开。
   *
   * <p>它**不产生事件**（接收方已经不在了，往断开的下游发东西不成立），
   * 但**必须结算**成 {@code CLIENT_CANCELLED} —— 少记一次与记成上游失败，
   * 对下游的熔断器是同一种误导：移动端切后台、用户关页面都会产生取消，
   * 把取消算进熔断会让一次共享网络的抖动熔断掉一个健康的实例。
   */
  @Test
  @DisplayName("调用方中途断开：没有多余的事件，但结算成 CLIENT_CANCELLED —— 取消不是上游失败")
  void cancellationSettlesAsCancelledAndNotAsUpstreamFailure() {
    UPSTREAM.replayStream(List.of(chunkWith("一"), chunkWith("二"), chunkWith("三"),
        terminalChunk("stop"), done()), Duration.ofMillis(700));

    FluxExchangeResult<String> result = exchangeStreamingResult();
    String requestId = requestIdOf(result);
    // 只取前两帧就取消：上游后面还有三块没写出来。
    List<String> frames = result.getResponseBody()
        .take(2)
        .collectList()
        .block(Duration.ofSeconds(20));

    assertThat(frames).as("取消之前收到的帧").hasSize(2);

    Outcome outcome = soleSettlementOf(requestId);
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.CLIENT_CANCELLED);
    // 取消不对应任何状态码：接收方已经不在了，「给客户端什么状态码」这件事不成立。
    assertThat(outcome.error().httpStatus()).isNull();
  }

  /**
   * 截断影响展示，不影响计费。
   *
   * <p>用量走**上游回报**那一条独立轨道：客户端少收到多少个字符，与这一次调用花了多少 token
   * 是两件事。按截断后的字符数估算，等于让「上游发疯时网关的自我保护」变成一次少计费。
   */
  @Test
  @DisplayName("累积内容被截断时，用量仍然是上游回报的那个数 —— 截断影响展示，不影响计费")
  void truncationDoesNotChangeReportedUsage() {
    UPSTREAM.replayStream(List.of(chunkWith("这是一段明显超过上限的内容"),
        usageChunk(11, 7, 18), terminalChunk("stop"), done()), Duration.ZERO);

    FluxExchangeResult<String> result = exchangeStreamingResult();
    String requestId = requestIdOf(result);
    List<String> frames = framesOf(result);

    assertThat(concatenatedContent(frames)).hasSize(MAX_CONTENT_CHARS);
    Outcome outcome = soleSettlementOf(requestId);
    assertThat(outcome.usage()).as("结算里带的用量").isNotNull();
    assertThat(outcome.usage().totalTokens()).isEqualTo(18L);
  }

  /**
   * 延迟算到**收到终态**为止，不是算到响应头写出的那一瞬间。
   *
   * <p>这两者的差别就是整条响应体。在响应头写出时结算的实现会让流式的耗时恒为零，
   * 于是流式的 P99 看起来特别好，而那只是「没记」造成的错觉 ——
   * 延迟感知负载均衡拿到的 EWMA 输入是假的（ADR-0005 第 2 节）。
   */
  @Test
  @DisplayName("延迟算到收到终态为止：流式的耗时覆盖整条流，不是响应头写出那一瞬间")
  void latencyIsMeasuredToTheTerminalNotToTheHeaders() {
    UPSTREAM.replayStream(List.of(chunkWith("慢"), chunkWith("慢"), chunkWith("来"),
        terminalChunk("stop"), done()), Duration.ofMillis(400));

    FluxExchangeResult<String> result = exchangeStreamingResult();
    String requestId = requestIdOf(result);
    framesOf(result);

    // 终态在第 4 块才到（约 1.2 秒）：块间 400ms 仍在 600ms 上限之内，这条流是好好说话的。
    assertThat(soleSettlementOf(requestId).latency())
        .isGreaterThanOrEqualTo(Duration.ofMillis(900));
  }

  /**
   * 非流式走**同一个**收口点结算。
   *
   * <p>两个端口、一个 Provider、一本账：只有一侧记的话，另一侧的延迟与成功率都好看到没有意义，
   * 而「流式绕过治理」正是从「流式那条路单独有一套东西」开始的。
   */
  @Test
  @DisplayName("非流式调用同样结算一次，且记在同一本账上")
  void nonStreamingCallSettlesThroughTheSameSink() {
    UPSTREAM.replay(200, """
        {"id":"chatcmpl-ns","object":"chat.completion","model":"recorded-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"好"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":11,"completion_tokens":2,"total_tokens":13}}
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

    Outcome outcome = soleSettlementOf(requestId);
    assertThat(outcome.kind()).isEqualTo(OutcomeKind.SUCCESS);
    assertThat(outcome.usage().totalTokens()).isEqualTo(13L);
    // 实例身份现阶段就是端点地址：结算里少了它，事后分不清一次失败属于哪一个实例。
    assertThat(outcome.instanceId()).isEqualTo(UPSTREAM.baseUrl());
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
        "golden-key", MODEL, recorded.baseUrl(), IDLE_TIMEOUT, 1 << 16, outcome -> { });
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

  /**
   * 打一次流式请求，返回还能读响应头的那个结果对象。
   *
   * <p>请求标识从响应头里取，因为结算是按 {@code requestId} 记的，
   * 而它是把「这一次调用」与「它的结算」对上的唯一凭据 ——
   * 取「账本最后一条」在并发下会拿到别人的结果。
   */
  private FluxExchangeResult<String> exchangeStreamingResult() {
    return client.post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(requestBody(true))
        .exchange()
        .returnResult(String.class);
  }

  private static List<String> framesOf(FluxExchangeResult<String> result) {
    return result.getResponseBody().collectList().block(Duration.ofSeconds(20));
  }

  private static String requestIdOf(FluxExchangeResult<String> result) {
    String requestId = result.getResponseHeaders().getFirst("X-Airouter-Request-Id");
    assertThat(requestId).as("响应头里的请求标识").isNotBlank();
    return requestId;
  }

  /**
   * 这一条请求的**唯一**结算：等到它出现，并断言只有一条。
   *
   * <p>「恰好结算一次」在这里而不是只在 core 的单测里断言：core 那边守的是 {@code claim()} 本身，
   * 这里守的是**一条真实的流**在走过分帧、译码、截断、补发终态之后只留下一条记录 ——
   * 终态与取消同时发生时多出来的那一条，只有走到末端才看得见。
   */
  private Outcome soleSettlementOf(String requestId) {
    List<Outcome> settled = awaitSettlements(requestId);
    assertThat(settled).as(requestId + " 的结算条数").hasSize(1);
    return settled.get(0);
  }

  /**
   * 等条件成立，不睡固定时长再比数字。
   *
   * <p>结算发生在流式的终态那一瞬间，与「客户端收完最后一帧」之间隔着一次网络写；
   * 睡一个固定时长去等它，会让这条断言在慢机器上偶发地红。
   */
  private List<Outcome> awaitSettlements(String requestId) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      List<Outcome> settled = settlementLog.ofRequest(requestId);
      if (!settled.isEmpty()) {
        return settled;
      }
      sleepBriefly();
    }
    throw new AssertionError("十秒内没有等到 " + requestId + " 的结算；账本里现在有 "
        + settlementLog.all().size() + " 条");
  }

  private static void sleepBriefly() {
    try {
      Thread.sleep(20L);
    } catch (InterruptedException expected) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待结算时被中断", expected);
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
    return String.join("", contents(frames));
  }

  /**
   * 每一帧带的正文内容，按到达顺序。没有内容的帧（开场带的是角色、终态带的是结束原因、
   * 哨兵不是 JSON）不进这张表 —— 断言「内容不重复」只能在**只有内容**的那张表上做。
   */
  private static List<String> contents(List<String> frames) {
    List<String> pieces = new ArrayList<>();
    for (String frame : frames) {
      if ("[DONE]".equals(frame)) {
        continue;
      }
      JsonNode content = MAPPER.readTree(frame).path("choices").path(0).path("delta").path("content");
      if (!content.isMissingNode()) {
        pieces.add(content.asString(""));
      }
    }
    return pieces;
  }

  /**
   * 按字节切点把一份 SSE 原文切成若干块。
   *
   * <p>切点落在多字节字符**内部**是刻意允许的：那正是要造的形状，
   * 而它无法用字符串表达（半个 UTF-8 序列不是合法文本），所以这里进出都是字节块。
   */
  private static List<byte[]> splitAt(String body, List<Integer> cutPoints) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    List<byte[]> parts = new ArrayList<>();
    int start = 0;
    for (int cut : new TreeSet<>(cutPoints)) {
      if (cut > start && cut < bytes.length) {
        parts.add(Arrays.copyOfRange(bytes, start, cut));
        start = cut;
      }
    }
    parts.add(Arrays.copyOfRange(bytes, start, bytes.length));
    return parts;
  }

  /** 每一个字节都是一块时的切点：所有可能的切点一次覆盖。 */
  private static List<Integer> everyOffsetOf(String body) {
    return everyNth(body.getBytes(StandardCharsets.UTF_8).length, 1);
  }

  private static List<Integer> everyNth(int length, int step) {
    List<Integer> cuts = new ArrayList<>();
    for (int offset = step; offset < length; offset += step) {
      cuts.add(offset);
    }
    return cuts;
  }

  /** 一到六个随机切点，升序去重。 */
  private static List<Integer> randomCuts(int length, Random random) {
    Set<Integer> cuts = new TreeSet<>();
    int count = 1 + random.nextInt(6);
    for (int i = 0; i < count; i++) {
      cuts.add(1 + random.nextInt(length - 1));
    }
    return List.copyOf(cuts);
  }

  /**
   * 一个字符在原文里的**字节**偏移。
   *
   * <p>字符偏移与字节偏移在含中文的正文里不是一回事 ——
   * 用 {@link String#indexOf} 的结果去切字节，切中的位置根本不在那个字上。
   */
  private static int byteOffsetOf(String body, String character) {
    return body.substring(0, body.indexOf(character)).getBytes(StandardCharsets.UTF_8).length;
  }

  private static String chunkWith(String content) {
    return "data: {\"id\":\"chatcmpl-e2e\",\"object\":\"chat.completion.chunk\","
        + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
        + "\"finish_reason\":null}]}\n\n";
  }

  /** 上游把用量单独发一块（{@code stream_options.include_usage} 打开时就是这样）。 */
  private static String usageChunk(int promptTokens, int completionTokens, int totalTokens) {
    return "data: {\"id\":\"chatcmpl-e2e\",\"object\":\"chat.completion.chunk\","
        + "\"created\":1700000000,\"model\":\"" + MODEL + "\",\"choices\":[],"
        + "\"usage\":{\"prompt_tokens\":" + promptTokens
        + ",\"completion_tokens\":" + completionTokens
        + ",\"total_tokens\":" + totalTokens + "}}\n\n";
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
