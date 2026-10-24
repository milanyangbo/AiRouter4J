package io.github.airouter.gateway.providers.ollama;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.gateway.providers.ChunkParseCounter;
import io.github.airouter.gateway.providers.StreamSettlement;
import io.github.airouter.gateway.transport.OutboundWire;
import io.github.airouter.gateway.transport.UpstreamReply;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/**
 * Ollama **原生**接口的 Provider（{@code /api/chat}）。
 *
 * <p>它是第二个 Provider，存在的第一目的不是「多支持一家」，而是给 ADR-0005 那句
 * 「新增 Provider 不需要改动 Face」提供一个可以被推翻的对照物：
 * 一家形态确实不同的上游接进来之后，Face 与 {@code core} 一个字都没动 ——
 * 这件事由 {@code SecondProviderTouchesNoFaceTest} 用差异统计断言，不靠声明。
 *
 * <p>与 OpenAI 兼容 Provider 相比，本类只有两处不同，其余全部共用：
 * <ol>
 *   <li>走 {@link OutboundWire#postLines} 而不是 {@link OutboundWire#postStream} ——
 *       对面说的是逐行 JSON，不是 SSE（差异清单见 {@link OllamaUpstreamCodec}）；
 *   <li>{@link #toEvents} 按 {@code done:true} 认终态，而不是认 {@code [DONE]} 哨兵。
 * </ol>
 * 结算纪律（恰好一次、终态最后、上游没说完就补发失败）不在这里，
 * 它与另一个 Provider 是同一份 {@link StreamSettlement} ——
 * 那一份抽出来正是这一票带来的结构变化。
 */
public final class OllamaProvider implements CompletionPort, StreamingCompletionPort {

  /**
   * 原生接口没有凭证这一项。
   *
   * <p>把配置里那个与凭证无关的占位串当作 bearer 发出去，读日志的人会以为这次调用是被授权过的 ——
   * 而它只是本地档位为了通过非空校验填的一个值。
   */
  private static final Map<String, String> NO_HEADERS = Map.of();

  /**
   * 上游在讲完之前就关闭连接时对客户端说的话。
   *
   * <p>这一份形态没有结束哨兵，因此不能说「在结束哨兵之前」——
   * 说了客户端会去找一个不存在的东西。判据是帧里的 {@code done}，所以这里说「在结束之前」。
   */
  private static final String PREMATURE_CLOSE = "上游在结束之前关闭了连接：这条流的内容可能不完整";

  private final OutboundWire wire;

  private final String defaultModel;

  private final StreamSettlement settlement;

  private final ChunkParseCounter parseCounter;

  /**
   * @param wire 出站口，由 {@code config} 装配
   * @param defaultModel 请求没有指定模型时使用的值
   * @param instanceId 实例标识，出现在结算记录里。现阶段没有实例注册表，端点地址即身份
   * @param observer 结果结算点，由 {@code config} 装配
   * @param parseCounter 上游帧的解析计数，用于守护 CHUNK_PARSE_COUNT=1
   */
  public OllamaProvider(OutboundWire wire,
                        String defaultModel,
                        String instanceId,
                        OutcomeObserver observer,
                        ChunkParseCounter parseCounter) {
    this.wire = wire;
    this.defaultModel = defaultModel;
    this.settlement = new StreamSettlement(observer, instanceId,
        new StreamSettlement.ErrorExplainer(
            OllamaUpstreamCodec::errorMessageFrom, PREMATURE_CLOSE));
    this.parseCounter = parseCounter;
  }

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    Instant start = Instant.now();
    CanonicalRequest resolved = StreamSettlement.resolveModel(request, defaultModel);
    String body = OllamaUpstreamCodec.encodeRequest(resolved);
    return wire.post(OllamaUpstreamCodec.CHAT_PATH, NO_HEADERS, body)
        .map(this::toCanonical)
        .toFuture()
        .whenComplete((response, failure) -> settlement.report(resolved, start, response, failure));
  }

  @Override
  public Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    CanonicalRequest resolved = StreamSettlement.resolveModel(request, defaultModel);
    String body = OllamaUpstreamCodec.encodeRequest(resolved);
    Flux<String> frames = wire.postLines(OllamaUpstreamCodec.CHAT_PATH, NO_HEADERS, body);
    return JdkFlowAdapter.publisherToFlowPublisher(
        settlement.assemble(frames, resolved, this::toEvents));
  }

  /**
   * 一整行 JSON → 零个或多个事件。解析只发生在这里，一次一行。
   *
   * <p>终态的依据是 {@code done:true}。这一份 wire 形态**没有结束哨兵**，
   * 因此「上游说完了」这件事只能从帧的内容里读出来 —— 这正是它与另一个 Provider 唯一实质性的差别，
   * 也是「同一个 SseFrameReader 能不能覆盖两种形态」的答案：不能，
   * 因为「什么时候算说完」不是分帧层能决定的事，它取决于字段的含义。
   *
   * <p>没有流标识：原生接口不返回 id，因此 {@code Opening} 带的是空串而不是编造一个。
   * 客户端按「有没有 id」分支的场合是有的，但编一个 {@code chatcmpl-} 前缀的假 id
   * 会让排查问题的人以为上游真的给过它。
   */
  private List<CanonicalStreamEvent> toEvents(String payload, StreamSettlement.State state) {
    OllamaUpstreamCodec.StreamFrame frame = OllamaUpstreamCodec.decodeStreamFrame(payload);
    parseCounter.countParsedFrame();

    List<CanonicalStreamEvent> events = new ArrayList<>();
    if (state.open(null, frame.model(), frame.created())) {
      events.add(new CanonicalStreamEvent.Opening(state.id(), state.model(), state.created()));
    }
    if (frame.content() != null && !frame.content().isEmpty()) {
      events.add(new CanonicalStreamEvent.TextDelta(0, frame.content()));
    }
    if (frame.usage() != null) {
      state.noteUsage(frame.usage());
      events.add(new CanonicalStreamEvent.Usage(frame.usage()));
    }
    if (frame.doneReason() != null) {
      state.noteFinishReason(frame.doneReason());
    }
    if (frame.done()) {
      state.terminate();
      events.add(new CanonicalStreamEvent.Terminal(state.finishReason(), state.usage()));
    }
    return events;
  }

  /** 2xx 之外的回答交给结算纪律分类：4xx 原样透传、5xx 收敛到 502，两种形态同一个口径。 */
  private CanonicalResponse toCanonical(UpstreamReply reply) {
    if (reply.status() >= 200 && reply.status() < 300) {
      return OllamaUpstreamCodec.decodeResponse(reply.body());
    }
    throw settlement.rejection(reply.status(), reply.body());
  }
}
