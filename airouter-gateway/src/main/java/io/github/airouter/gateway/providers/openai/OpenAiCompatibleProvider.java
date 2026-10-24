package io.github.airouter.gateway.providers.openai;

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
 * OpenAI 兼容端点的 Provider。
 *
 * <p>它做三件事，其余一概不做：把 Canonical 请求译成上游 JSON、经由 {@link OutboundWire} 发出、
 * 把上游响应译回 Canonical。它不认识 Face，也不认识证书与 key 是怎么来的 ——
 * 那些由 {@code config} 在构造时给进来。
 *
 * <p>这里的变量名与注释都不提具体厂商的名字：同一个实现覆盖所有 OpenAI 兼容端点，
 * 差别被留在 Endpoint 的配置里，而不是派生出一批近乎相同的子类。
 *
 * <p>流式路径上「恰好结算一次」「终态必须是最后一个事件」「上游没说完就补发失败」
 * 这些纪律不在本类里，它们在 {@link StreamSettlement} —— 两个 Provider 共用那一份。
 * 本类只剩下这一件事：**一帧 OpenAI 兼容的 SSE 载荷变成哪几个事件**。
 */
public final class OpenAiCompatibleProvider implements CompletionPort, StreamingCompletionPort {

  /**
   * 上游在结束哨兵之前就关闭连接时对客户端说的话。
   *
   * <p>这句话里提到「结束哨兵」是因为这一份形态真的有（{@code [DONE]}），
   * 客户端拿着它去比对上游的日志是对得上的。另一份形态没有哨兵，它说的是另一句 ——
   * 见 {@link io.github.airouter.gateway.providers.ollama.OllamaProvider}。
   */
  private static final String PREMATURE_CLOSE = "上游在发出结束哨兵之前关闭了连接：这条流的内容可能不完整";

  private final OutboundWire wire;

  private final Map<String, String> headers;

  private final String defaultModel;

  private final StreamSettlement settlement;

  private final ChunkParseCounter parseCounter;

  /**
   * @param wire 出站口，由 {@code config} 装配
   * @param apiKey 上游凭证，由环境变量来
   * @param defaultModel 请求没有指定模型时使用的值
   * @param instanceId 实例标识，出现在结算记录里。现阶段没有实例注册表，端点地址即身份
   * @param observer 结果结算点，由 {@code config} 装配；本类不解释结算结果，只负责上报
   * @param parseCounter 上游帧的解析计数，用于守护 CHUNK_PARSE_COUNT=1
   */
  public OpenAiCompatibleProvider(OutboundWire wire,
                                  String apiKey,
                                  String defaultModel,
                                  String instanceId,
                                  OutcomeObserver observer,
                                  ChunkParseCounter parseCounter) {
    this.wire = wire;
    this.headers = Map.of("Authorization", "Bearer " + apiKey);
    this.defaultModel = defaultModel;
    this.settlement = new StreamSettlement(observer, instanceId,
        new StreamSettlement.ErrorExplainer(
            OpenAiUpstreamCodec::errorMessageFrom, PREMATURE_CLOSE));
    this.parseCounter = parseCounter;
  }

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    Instant start = Instant.now();
    CanonicalRequest resolved = StreamSettlement.resolveModel(request, defaultModel);
    String body = OpenAiUpstreamCodec.encodeRequest(resolved);
    return wire.post(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body)
        .map(this::toCanonical)
        .toFuture()
        .whenComplete((response, failure) -> settlement.report(resolved, start, response, failure));
  }

  @Override
  public Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    CanonicalRequest resolved = StreamSettlement.resolveModel(request, defaultModel);
    String body = OpenAiUpstreamCodec.encodeRequest(resolved);
    Flux<String> frames = wire.postStream(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body);
    return JdkFlowAdapter.publisherToFlowPublisher(
        settlement.assemble(frames, resolved, this::toEvents));
  }

  /**
   * 一个 SSE 载荷 → 零个或多个事件。解析只发生在这里，一次一个载荷。
   *
   * <p>{@code [DONE]} 是这一份 wire 形态宣告「我说完了」的方式。它由本方法认出并转成
   * {@link StreamSettlement.State#terminate()}，不发成事件 —— 客户端那一个 {@code [DONE]}
   * 是 Face 在确认有终态之后才写的，与上游这个不是同一个东西。
   * 另一种形态（逐行 JSON）没有哨兵，由最后一帧的内容宣告结束，
   * 差别就落在这一处，其余的纪律两边共用。
   */
  private List<CanonicalStreamEvent> toEvents(String payload, StreamSettlement.State state) {
    if (OpenAiUpstreamCodec.DONE_SENTINEL.equals(payload)) {
      state.terminate();
      return List.of(new CanonicalStreamEvent.Terminal(state.finishReason(), state.usage()));
    }
    if (payload.startsWith(":")) {
      return List.of(new CanonicalStreamEvent.Ping(payload.substring(1)));
    }
    OpenAiUpstreamCodec.StreamChunk chunk = OpenAiUpstreamCodec.decodeStreamChunk(payload);
    parseCounter.countParsedFrame();

    List<CanonicalStreamEvent> events = new ArrayList<>();
    if (state.open(chunk.id(), chunk.model(), chunk.created())) {
      events.add(new CanonicalStreamEvent.Opening(state.id(), state.model(), state.created()));
    }
    for (OpenAiUpstreamCodec.Delta delta : chunk.deltas()) {
      events.add(new CanonicalStreamEvent.TextDelta(delta.index(), delta.content()));
    }
    if (chunk.finishReason() != null) {
      state.noteFinishReason(chunk.finishReason());
    }
    if (chunk.usage() != null) {
      state.noteUsage(chunk.usage());
      events.add(new CanonicalStreamEvent.Usage(chunk.usage()));
    }
    return events;
  }

  /**
   * 把上游结果变成 Canonical 或失败。
   *
   * <p>状态码的处理不在这里分类，交给 {@link StreamSettlement#rejection}：
   * 4xx 原样透传、5xx 收敛到 502 这条规则两条路径、两种形态共用一份。
   */
  private CanonicalResponse toCanonical(UpstreamReply reply) {
    if (reply.status() >= 200 && reply.status() < 300) {
      return OpenAiUpstreamCodec.decodeResponse(reply.body());
    }
    throw settlement.rejection(reply.status(), reply.body());
  }
}
