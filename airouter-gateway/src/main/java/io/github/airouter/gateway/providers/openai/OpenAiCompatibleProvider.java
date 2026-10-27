package io.github.airouter.gateway.providers.openai;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.transport.OutboundWire;
import io.github.airouter.gateway.transport.UpstreamRejection;
import io.github.airouter.gateway.transport.UpstreamReply;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/**
 * OpenAI 兼容端点的 Provider。
 *
 * <p>它做三件事，其余一概不做：把 Canonical 请求译成上游 JSON、经由 {@link OutboundWire} 发出、
 * 把上游响应译回 Canonical。它不认识 Face，也不认识证书与 key 是怎么来的 ——
 * 那些由 {@code config} 在构造时给进来。
 *
 * <p>这里的类名与注释都不提具体厂商的名字：同一个实现覆盖所有 OpenAI 兼容端点，
 * 差别被留在 {@code Endpoint} 的配置里，而不是派生出一批近乎相同的子类（ADR-0006 第 1 节）。
 *
 * <p>两个端口由同一个类实现 —— 流式与非流式是同一件事的两个形态，
 * 分成两处实现会让「流式绕过治理」变成一件容易发生的 accidentally 的事：
 * 治理挂在端口上，两个实现就意味着有两条路，而其中一条迟早少挂一样东西。
 *
 * <p>「一条流恰好结算一次」那类纪律不落在本类里 —— 它们属于所有形态共用的一份结算逻辑（票 04）。
 */
public final class OpenAiCompatibleProvider implements CompletionPort, StreamingCompletionPort {

  /** 上游送来了不可能合法的东西（帧超限、载荷不是 JSON）。它归因到上游。 */
  private static final String MALFORMED = "upstream_malformed";

  private final OutboundWire wire;

  private final Map<String, String> headers;

  private final String defaultModel;

  private final Duration idleTimeout;

  private final int maxContentChars;

  /**
   * @param wire 出站口，由 {@code config} 装配
   * @param apiKey 上游凭证，由环境变量来
   * @param defaultModel 请求没有指定模型时使用的值
   * @param idleTimeout 块间静默上限。它必须显式给进来而不是掉进一个默认值 ——
   *                    「一条静默的流最多占多久」是一个 SLO 决定，不是一个实现细节
   * @param maxContentChars 单条流累积内容的字符上限，作用见 {@link #capContent}
   */
  public OpenAiCompatibleProvider(OutboundWire wire, String apiKey, String defaultModel,
      Duration idleTimeout, int maxContentChars) {
    this.wire = wire;
    this.headers = Map.of("Authorization", "Bearer " + apiKey);
    this.defaultModel = defaultModel;
    this.idleTimeout = idleTimeout;
    this.maxContentChars = maxContentChars;
  }

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    CanonicalRequest resolved = resolveModel(request, defaultModel);
    String body = OpenAiUpstreamCodec.encodeRequest(resolved);
    return wire.post(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body)
        .map(this::toCanonical)
        .toFuture();
  }

  /**
   * 流式调用。
   *
   * <p>三件事按这个顺序发生，顺序本身是被守着的：先译码（一帧 → 事件），
   * 再截断（累积内容超限），最后在终态处截断（终态之后的帧不再产生事件）。
   * 换一个顺序就会漏掉某一条：先截断再译码会让「超限之后的那一段」被当成内容；
   * 先截终态再截断会让超限判断作用在已经被丢掉的帧上。
   *
   * @param request Canonical 请求
   * @return 事件流，以终态收尾
   */
  @Override
  public Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    CanonicalRequest resolved = resolveModel(request, defaultModel);
    String body = OpenAiUpstreamCodec.encodeRequest(resolved);
    Flux<CanonicalStreamEvent> events = Flux.defer(() -> {
      OpenAiUpstreamCodec.StreamDecoder decoder = OpenAiUpstreamCodec.newStreamDecoder();
      return wire.postStream(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body, idleTimeout)
          .concatMapIterable(decoder::decode)
          .onErrorResume(UpstreamRejection.class,
              rejection -> Flux.just(failed(rejection.status(), rejection.body())))
          .onErrorResume(UpstreamFailure.class, failure -> Flux.just(new CanonicalStreamEvent.Failed(
              failure.error())))
          // 帧超限（IllegalStateException）与「载荷不是 JSON」（IllegalArgumentException）
          // 都是上游送来了不可能合法的东西。只收这两种而不是收 RuntimeException：
          // 后者会把我们自己写错的空指针也包装成「上游的问题」，那是静默失效最喜欢的形态。
          .onErrorResume(IllegalStateException.class, OpenAiCompatibleProvider::malformed)
          .onErrorResume(IllegalArgumentException.class, OpenAiCompatibleProvider::malformed);
    });
    return JdkFlowAdapter.publisherToFlowPublisher(
        capContent(events).takeUntil(CanonicalStreamEvent::isTerminal));
  }

  /**
   * 累积内容的上限。
   *
   * <p>超出之后**不再往下发内容**，但流继续走到终态：截断只影响展示。
   * 一条失控的流因此不会吃满内存，而终态仍然会被判出来 ——
   * 反过来（超限就断流）会让客户端拿到一条没有终态的半截响应，
   * 而「没有终态」在下游的结算里是最难归因的那一种。
   *
   * <p>它**不影响计费**：用量取上游回报，与这里截断了多少个字符无关（ADR-0005 第 4 节）。
   *
   * @param events 原始事件流
   * @return 内容累积量被限制在 {@link #maxContentChars} 之内的事件流
   */
  private Flux<CanonicalStreamEvent> capContent(Flux<CanonicalStreamEvent> events) {
    AtomicInteger emitted = new AtomicInteger();
    return events.handle((event, sink) -> {
      if (!(event instanceof CanonicalStreamEvent.TextDelta delta)) {
        sink.next(event);
        return;
      }
      int remaining = maxContentChars - emitted.get();
      if (remaining <= 0) {
        return;
      }
      String text = delta.text();
      if (text.length() <= remaining) {
        emitted.addAndGet(text.length());
        sink.next(delta);
        return;
      }
      emitted.set(maxContentChars);
      sink.next(new CanonicalStreamEvent.TextDelta(text.substring(0, remaining)));
    });
  }

  /**
   * 把上游结果变成 Canonical 或失败。
   *
   * <p>状态码的分级不在这里做：4xx 透传、5xx 收敛到 502 是这一份 wire 形态的说法，
   * 写在 codec 里（{@link OpenAiUpstreamCodec#rejection}），与流式那条路共用同一份。
   *
   * @param reply 上游给回的原始结果
   * @return Canonical 响应
   */
  private CanonicalResponse toCanonical(UpstreamReply reply) {
    if (reply.status() >= 200 && reply.status() < 300) {
      return OpenAiUpstreamCodec.decodeResponse(reply.body());
    }
    throw OpenAiUpstreamCodec.rejection(reply.status(), reply.body());
  }

  /** 上游明确拒绝：与流式、非流式共用同一份分级（写在 codec 里）。 */
  private static CanonicalStreamEvent.Failed failed(int status, String upstreamBody) {
    return new CanonicalStreamEvent.Failed(
        OpenAiUpstreamCodec.rejection(status, upstreamBody).error());
  }

  /** 上游送来的字节不合这一份 wire format 的形状：归因到上游，但带一句话说明是什么不合。 */
  private static Flux<CanonicalStreamEvent> malformed(Throwable failure) {
    return Flux.just(new CanonicalStreamEvent.Failed(
        new GatewayError(MALFORMED, "上游的流式响应无法被解析：" + failure.getMessage(), 502)));
  }

  /**
   * 模型名的兜底。
   *
   * <p>客户端不写 {@code model} 是常见的（尤其是把网关当成「一个模型」来用的那些），
   * 而把它原样发上去会得到一个 400。缺省值取自 {@code Endpoint} 的配置 ——
   * 「网关后面默认是谁」是一个部署决定，不该由网关发明一个。
   *
   * @param request 入站请求
   * @param fallback 缺省模型名
   * @return 模型名已确定的请求
   */
  private static CanonicalRequest resolveModel(CanonicalRequest request, String fallback) {
    if (request.model() != null && !request.model().isBlank()) {
      return request;
    }
    String model = fallback == null ? "" : fallback;
    return new CanonicalRequest(
        request.requestId(),
        request.serviceId(),
        model,
        request.messages(),
        request.tools(),
        request.params(),
        request.stream(),
        request.passthrough());
  }
}
