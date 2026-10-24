package io.github.airouter.gateway.providers.openai;

import io.github.airouter.core.CanonicalFinishReason;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CanonicalUsage;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.providers.ChunkParseCounter;
import io.github.airouter.gateway.transport.OutboundWire;
import io.github.airouter.gateway.transport.UpstreamRejection;
import io.github.airouter.gateway.transport.UpstreamReply;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
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
 * <p>流式路径上它多担一份责任：**保证任何一条流都有且只有一个终态**（ADR-0006 第 1 节）。
 * 上游正常发来结束哨兵时终态是 {@code Terminal}；上游在发出哨兵之前就关闭连接时补发
 * {@code Failed}；帧间静默超过上限时同样是 {@code Failed}。
 * 这份责任不能被推给「流如何结束」去推断 —— 那正是来源项目「流式恒为成功」的成因。
 */
public final class OpenAiCompatibleProvider implements CompletionPort, StreamingCompletionPort {

  /** 状态码在 400 到 499 之间时，把上游的状态原样交给客户端。 */
  private static final int CLIENT_ERROR_FLOOR = 400;

  private static final int SERVER_ERROR_FLOOR = 500;

  /** 上游故障时对客户端给出的状态码：这是网关这一层的事，不该让客户端去猜上游发生了什么。 */
  private static final int BAD_GATEWAY = 502;

  private final OutboundWire wire;
  private final Map<String, String> headers;
  private final String defaultModel;
  private final String instanceId;
  private final OutcomeObserver observer;
  private final ChunkParseCounter parseCounter;

  /**
   * @param wire 出站口，由 {@code config} 装配
   * @param apiKey 上游凭证，由环境变量来
   * @param defaultModel 请求没有指定模型时使用的值
   * @param instanceId 实例标识，出现在 {@link Outcome} 里。现阶段没有实例注册表，端点地址即身份
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
    this.instanceId = instanceId;
    this.observer = observer;
    this.parseCounter = parseCounter;
  }

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    Instant start = Instant.now();
    String body = OpenAiUpstreamCodec.encodeRequest(withResolvedModel(request));
    return wire.post(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body)
        .map(this::toCanonical)
        .toFuture()
        .whenComplete((response, failure) -> report(request, start, response, failure));
  }

  @Override
  public Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    return JdkFlowAdapter.publisherToFlowPublisher(streamEvents(withResolvedModel(request)));
  }

  /**
   * 流式路径的全部内容。
   *
   * <p>{@code defer} 是刻意的：每一份累积状态都属于**一次订阅**。少了它，第二次订阅
   * 会接着上一次的「是否已发终态」往下走，而那一次的流已经结束了 ——
   * 表现为第二次订阅永久挂起，且不报错。
   */
  private Flux<CanonicalStreamEvent> streamEvents(CanonicalRequest request) {
    String body = OpenAiUpstreamCodec.encodeRequest(request);
    return Flux.defer(() -> {
      StreamState state = new StreamState();
      return wire.postStream(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body)
          .concatMap(payload -> Flux.fromIterable(toEvents(payload, state)))
          .concatWith(Flux.defer(state::closeWithoutTerminal))
          .onErrorResume(failure -> Flux.just(failedEvent(state, failure)))
          .doOnNext(event -> observe(request, state, event));
    });
  }

  /** 一个 SSE 载荷 → 零个或多个事件。解析只发生在这里，一次一个载荷。 */
  private List<CanonicalStreamEvent> toEvents(String payload, StreamState state) {
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

  /** 终态到达时的结算：这是这条流唯一一次上报结果的地方。 */
  private void observe(CanonicalRequest request, StreamState state, CanonicalStreamEvent event) {
    if (event instanceof CanonicalStreamEvent.Terminal terminal) {
      observer.onTerminal(new Outcome(
          request.requestId(), request.serviceId(), instanceId, OutcomeKind.SUCCESS,
          state.latency(), terminal.usage(), null));
      return;
    }
    if (event instanceof CanonicalStreamEvent.Failed failed) {
      observer.onTerminal(new Outcome(
          request.requestId(), request.serviceId(), instanceId, state.failureKind(),
          state.latency(), null, failed.error()));
    }
  }

  /** 非流式的结算，与流式共用同一个 {@link OutcomeObserver}：两条路径的结果口径必须是一个。 */
  private void report(CanonicalRequest request,
                      Instant start,
                      CanonicalResponse response,
                      Throwable failure) {
    Duration latency = Duration.between(start, Instant.now());
    if (failure == null) {
      observer.onTerminal(new Outcome(request.requestId(), request.serviceId(), instanceId,
          OutcomeKind.SUCCESS, latency, response.usage(), null));
      return;
    }
    Throwable cause = unwrap(failure);
    if (cause instanceof UpstreamFailure upstream) {
      observer.onTerminal(new Outcome(request.requestId(), request.serviceId(), instanceId,
          upstream.kind(), latency, null, upstream.error()));
      return;
    }
    observer.onTerminal(new Outcome(request.requestId(), request.serviceId(), instanceId,
        OutcomeKind.UPSTREAM_FAILURE, latency, null,
        new GatewayError("internal", "网关处理本次请求时失败", BAD_GATEWAY)));
  }

  /**
   * 模型名的取用顺序：请求里指定的优先，没给才回落到配置的默认值。
   *
   * <p>不把请求里的模型名改写成配置里的名字：那样会让「客户端指定了一个不存在的模型」
   * 变成一次静默的成功，而这个错误应当在上游被看见。
   */
  private CanonicalRequest withResolvedModel(CanonicalRequest request) {
    if (request.model() != null && !request.model().isBlank()) {
      return request;
    }
    return new CanonicalRequest(
        request.requestId(),
        request.serviceId(),
        defaultModel,
        request.messages(),
        request.tools(),
        request.params(),
        request.stream(),
        request.passthrough());
  }

  /**
   * 把上游结果变成 Canonical 或失败。
   *
   * <p>状态码的处理是有讲究的：**上游的 4xx 原样透传，5xx 收敛到 502。**
   * 前者是这一次的请求有问题（模型名不存在、参数不合法），客户端需要看到自己的错误；
   * 后者是上游自身故障，客户端对此无能为力，让它去区分是哪一种上游故障只会增加耦合。
   */
  private CanonicalResponse toCanonical(UpstreamReply reply) {
    if (reply.status() >= 200 && reply.status() < 300) {
      return OpenAiUpstreamCodec.decodeResponse(reply.body());
    }
    throw new UpstreamFailure(clientError(reply.status(), reply.body()), null);
  }

  /** 上游用非 2xx 回答了：这一份分类同时供流式与非流式两条路径使用，不出现第二个口径。 */
  private static GatewayError clientError(int status, String body) {
    if (status >= CLIENT_ERROR_FLOOR && status < SERVER_ERROR_FLOOR) {
      return new GatewayError(
          "upstream_4xx",
          "上游拒绝了这次请求（" + status + "）：" + OpenAiUpstreamCodec.errorMessageFrom(body),
          status);
    }
    return new GatewayError(
        "upstream_5xx",
        "上游处理本次请求时失败（" + status + "）：" + OpenAiUpstreamCodec.errorMessageFrom(body),
        BAD_GATEWAY);
  }

  /** 上游在发出结束哨兵之前就把连接收了。这不是成功：内容是否完整无从判断。 */
  private static GatewayError closedEarly() {
    return new GatewayError(
        "upstream_closed",
        "上游在发出结束哨兵之前关闭了连接：这条流的内容可能不完整",
        BAD_GATEWAY);
  }

  /**
   * 一条流以失败收尾时的形态。
   *
   * <p>三种来源分开写是因为它们的性质不同，而后果也不同：
   * 上游明确拒绝（4xx 原样透传）、上游够不着或超时（网关自己的判断）、
   * 以及我们这一侧没预料到的东西（一律不把内部细节交出去）。
   */
  private static CanonicalStreamEvent failedEvent(StreamState state, Throwable failure) {
    Throwable cause = unwrap(failure);
    if (cause instanceof UpstreamRejection rejection) {
      state.noteFailure(OutcomeKind.UPSTREAM_FAILURE);
      return new CanonicalStreamEvent.Failed(clientError(rejection.status(), rejection.body()));
    }
    if (cause instanceof UpstreamFailure upstream) {
      state.noteFailure(upstream.kind());
      return new CanonicalStreamEvent.Failed(upstream.error());
    }
    state.noteFailure(OutcomeKind.UPSTREAM_FAILURE);
    return new CanonicalStreamEvent.Failed(
        new GatewayError("internal", "网关处理这条流时失败", BAD_GATEWAY));
  }

  private static Throwable unwrap(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  /** 一条流在翻译过程中的累积状态。它属于一次订阅，见 {@link #streamEvents}。 */
  private static final class StreamState {

    private final Instant start = Instant.now();

    private String id = "";

    private String model = "";

    private Long created;

    private boolean opened;

    private boolean terminated;

    private OutcomeKind failureKind = OutcomeKind.UPSTREAM_FAILURE;

    private CanonicalFinishReason finishReason;

    private CanonicalUsage usage;

    /**
     * 记下这一帧带来的身份，并回答「是不是第一次见到它」。
     *
     * <p>只有第一帧才值得产出 {@code Opening}：之后每一帧都重复带 id 与 model，
     * 把它们逐帧发成事件只会让消费方一遍遍处理同样的信息。
     */
    boolean open(String chunkId, String chunkModel, Long chunkCreated) {
      if (opened) {
        return false;
      }
      if (chunkId != null) {
        id = chunkId;
      }
      if (chunkModel != null) {
        model = chunkModel;
      }
      if (chunkCreated != null) {
        created = chunkCreated;
      }
      opened = true;
      return true;
    }

    void noteFinishReason(CanonicalFinishReason reason) {
      finishReason = reason;
    }

    void noteUsage(CanonicalUsage value) {
      usage = value;
    }

    void terminate() {
      terminated = true;
    }

    String id() {
      return id;
    }

    String model() {
      return model;
    }

    Long created() {
      return created;
    }

    boolean hasTerminated() {
      return terminated;
    }

    /**
     * 上游把话讲完了但没发终态就收了连接。
     *
     * <p>这里补发 {@code Failed} 而不是让流安静地结束：安静结束与成功在这一层长得一模一样，
     * 而它们对熔断器来说是相反的两种结论（ADR-0006 第 1 节）。
     */
    Flux<CanonicalStreamEvent> closeWithoutTerminal() {
      if (terminated) {
        return Flux.empty();
      }
      noteFailure(OutcomeKind.UPSTREAM_FAILURE);
      return Flux.just(new CanonicalStreamEvent.Failed(closedEarly()));
    }

    /**
     * 记下失败类别。
     *
     * <p>类别只在产生 {@code Failed} 事件的那一处说得清（超时与「上游拒绝了」在那里还是两个类型），
     * 而上报 {@link Outcome} 发生在之后；这里就是它们之间的那道桥。
     */
    void noteFailure(OutcomeKind kind) {
      failureKind = kind == null ? OutcomeKind.UPSTREAM_FAILURE : kind;
    }

    CanonicalFinishReason finishReason() {
      return finishReason;
    }

    CanonicalUsage usage() {
      return usage;
    }

    OutcomeKind failureKind() {
      return failureKind;
    }

    /** 到此时为止的耗时。终态一到就取它，因此它是「到收到终态为止」的耗时。 */
    Duration latency() {
      return Duration.between(start, Instant.now());
    }
  }
}
