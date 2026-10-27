package io.github.airouter.gateway.providers.openai;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CanonicalUsage;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.core.StreamState;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.transport.OutboundWire;
import io.github.airouter.gateway.transport.UpstreamFramer;
import io.github.airouter.gateway.transport.UpstreamRejection;
import io.github.airouter.gateway.transport.UpstreamReply;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/**
 * OpenAI 兼容端点的 Provider。
 *
 * <p>它做四件事，其余一概不做：把 Canonical 请求译成上游 JSON、经由 {@link OutboundWire} 发出、
 * 把上游响应译回 Canonical、以及**在终态那一刻结算一次**。
 * 它不认识 Face，也不认识证书与 key 是怎么来的 —— 那些由 {@code config} 在构造时给进来。
 *
 * <p>这里的类名与注释都不提具体厂商的名字：同一个实现覆盖所有 OpenAI 兼容端点，
 * 差别被留在 {@code Endpoint} 的配置里，而不是派生出一批近乎相同的子类（ADR-0006 第 1 节）。
 *
 * <p>两个端口由同一个类实现 —— 流式与非流式是同一件事的两个形态，
 * 分成两处实现会让「流式绕过治理」变成一件容易发生的 accidentally 的事：
 * 治理挂在端口上，两个实现就意味着有两条路，而其中一条迟早少挂一样东西。
 *
 * <p>结算点在这里，是因为**只有产生终态的那一处才知道这次调用到底怎么样了**。
 * 换到别处（比如让传输层看一眼状态码）就等于把「成功还是失败」变成取决于谁在问的问题。
 */
public final class OpenAiCompatibleProvider implements CompletionPort, StreamingCompletionPort {

  /** 上游送来了不可能合法的东西（帧超限、载荷不是 JSON）。它归因到上游。 */
  private static final String MALFORMED = "upstream_malformed";

  /** 上游收了连接、但没把话说完。它**不是**成功 —— 内容是否完整无从判断。 */
  private static final String CLOSED = "upstream_closed";

  /** 调用方中途断开。它没有状态码：接收方已经不在了，「给客户端什么码」这件事不成立。 */
  private static final String CANCELLED = "client_cancelled";

  private final OutboundWire wire;

  /**
   * 怎么造一个分帧器。**它是形态自述里「流怎么结束」那一问的答案**，由 {@code config} 交进来。
   * 这个类不认识形态：它只知道「把字节切成帧要问谁」—— 于是换一种读法不需要动这个类。
   */
  private final IntFunction<UpstreamFramer> framerFactory;

  private final Map<String, String> headers;

  private final String defaultModel;

  private final String instanceId;

  private final Duration idleTimeout;

  private final int maxContentChars;

  /**
   * 结算动作：把一次结算交出去的那个出口。
   *
   * <p>存的是**动作**而不是观察者对象本身。观察者是 {@code config} 装配进来的协作者，
   * 而它自己是可变的（账本会增长、熔断器会改状态）—— 把它当作“Provider 自己的一部分”持有，
   * 等于让 Provider 的可变状态里包含别人手上的东西。
   * Provider 需要的只是「交出去」这一件事，因此它只依赖这一件事（接口隔离）。
   */
  private final Consumer<Outcome> settle;

  /**
   * @param wire 出站口，由 {@code config} 装配
   * @param framerFactory 怎么造一个分帧器，由 {@code config} 从形态那里取来。
   *                      它是「流怎么结束」这一问的答案；不放在这个类里，
   *                      是因为换一种读法不该动实现翻译的那一层
   * @param apiKey 上游凭证，由环境变量来
   * @param defaultModel 请求没有指定模型时使用的值
   * @param instanceId 这一次调用打在哪个实例上。**现阶段没有实例注册表，端点地址即身份** ——
   *                    注册表出现之后由它提供 id，那时这个参数会换成注册表给的东西
   * @param idleTimeout 块间静默上限。它必须显式给进来而不是掉进一个默认值 ——
   *                    「一条静默的流最多占多久」是一个 SLO 决定，不是一个实现细节
   * @param maxContentChars 单条流累积内容的字符上限，作用见 {@link #capContent}
   * @param observer 结算的唯一收口点，由 {@code config} 装配
   */
  public OpenAiCompatibleProvider(OutboundWire wire, IntFunction<UpstreamFramer> framerFactory,
      String apiKey, String defaultModel, String instanceId, Duration idleTimeout,
      int maxContentChars, OutcomeObserver observer) {
    this.wire = wire;
    this.framerFactory = framerFactory;
    this.headers = Map.of("Authorization", "Bearer " + apiKey);
    this.defaultModel = defaultModel;
    this.instanceId = instanceId;
    this.idleTimeout = idleTimeout;
    this.maxContentChars = maxContentChars;
    this.settle = observer::settle;
  }

  /**
   * 非流式调用。
   *
   * <p>它同样要结算：流式与非流式的延迟要可比，而可比的前提是**两侧都记了同一个口径的耗时**
   * （算到拿到结果为止）。只给流式记会让流式的 P99 看起来特别难看，
   * 而那只是「非流式那一侧根本没记」造成的错觉。
   *
   * @param request Canonical 请求
   * @return 异步的 Canonical 响应
   */
  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    CanonicalRequest resolved = resolveModel(request, defaultModel);
    String body = OpenAiUpstreamCodec.encodeRequest(resolved);
    Session session = new Session(resolved, instanceId, settle);
    return wire.post(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body)
        .map(this::toCanonical)
        .doOnSuccess(response -> session.settle(OutcomeKind.SUCCESS, null, response.usage()))
        .doOnError(UpstreamFailure.class,
            failure -> session.settle(failure.kind(), failure.error(), null))
        .toFuture();
  }

  /**
   * 流式调用。
   *
   * <p>四件事按这个顺序发生，顺序本身是被守着的：先译码（一帧 → 事件）、再截断（累积内容超限）、
   * 然后补发缺席的终态、最后在终态处截断（终态之后的帧不再产生事件）。
   * 换一个顺序就会漏掉一条：把「补发终态」放在截断之后，一条被截断的流会因为没有终态
   * 而把自己记成失败；把它放在截断之前，终态之后的帧又会出现在客户端。
   *
   * @param request Canonical 请求
   * @return 事件流，**必然**以终态收尾：上游没给终态时由这里补一个 {@code Failed}
   */
  @Override
  public Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    CanonicalRequest resolved = resolveModel(request, defaultModel);
    String body = OpenAiUpstreamCodec.encodeRequest(resolved);
    Flux<CanonicalStreamEvent> events = Flux.defer(() -> {
      Session session = new Session(resolved, instanceId, settle);
      OpenAiUpstreamCodec.StreamDecoder decoder = OpenAiUpstreamCodec.newStreamDecoder();
      Flux<CanonicalStreamEvent> decoded =
          wire.postStream(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body, idleTimeout,
              framerFactory)
              .concatMapIterable(decoder::decode)
              .onErrorResume(UpstreamRejection.class, rejection -> {
                session.recordFailureKind(OutcomeKind.UPSTREAM_FAILURE);
                return Flux.just(failed(rejection.status(), rejection.body()));
              })
              .onErrorResume(UpstreamFailure.class, failure -> {
                session.recordFailureKind(failure.kind());
                return Flux.just(new CanonicalStreamEvent.Failed(failure.error()));
              })
              // 帧超限（IllegalStateException）与「载荷不是 JSON」（IllegalArgumentException）
              // 都是上游送来了不可能合法的东西。只收这两种而不是收 RuntimeException：
              // 后者会把我们自己写错的空指针也包装成「上游的问题」，那是静默失效最喜欢的形态。
              .onErrorResume(IllegalStateException.class,
                  failure -> Flux.just(session.malformed(failure)))
              .onErrorResume(IllegalArgumentException.class,
                  failure -> Flux.just(session.malformed(failure)))
              .doOnNext(session::note);
      return capContent(decoded, new AtomicInteger())
          .concatWith(Flux.defer(session::endWithoutTerminal))
          .takeUntil(CanonicalStreamEvent::isTerminal)
          .doOnCancel(session::settleCancelled);
    });
    return JdkFlowAdapter.publisherToFlowPublisher(events);
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
   * @param emitted 已经发出去的字符数。它按订阅创建 —— 两条流不该共用一本账
   * @return 内容累积量被限制在 {@link #maxContentChars} 之内的事件流
   */
  private Flux<CanonicalStreamEvent> capContent(Flux<CanonicalStreamEvent> events,
      AtomicInteger emitted) {
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

  /**
   * 一次调用的一生：**恰好结算一次**。
   *
   * <p>它按订阅创建（流式的每一次调用一个），因为「这次调用结算过了没有」是这一次调用的事。
   * 结算的时机是**收到终态的那一刻**，不是响应头写出的那一刻 —— 两者之间隔着整条响应体，
   * 而一个持续吐出半截响应的上游，在「响应头写出即成功」的口径里是 100% 成功。
   */
  private static final class Session {

    private final String requestId;

    private final String serviceId;

    private final String instanceId;

    private final Consumer<Outcome> settle;

    private final Instant startedAt = Instant.now();

    private final StreamState state = new StreamState();

    private final AtomicReference<CanonicalUsage> usage = new AtomicReference<>();

    private final AtomicReference<OutcomeKind> failureKind =
        new AtomicReference<>(OutcomeKind.UPSTREAM_FAILURE);

    Session(CanonicalRequest request, String instanceId, Consumer<Outcome> settle) {
      this.requestId = request.requestId();
      this.serviceId = request.serviceId();
      this.instanceId = instanceId;
      this.settle = settle;
    }

    /** 每个事件都要过这里：用量与终态都只在事件流上出现一次。 */
    void note(CanonicalStreamEvent event) {
      if (event instanceof CanonicalStreamEvent.Usage reported) {
        usage.set(reported.usage());
      }
      if (event instanceof CanonicalStreamEvent.Terminal) {
        settle(OutcomeKind.SUCCESS, null, null);
      }
      if (event instanceof CanonicalStreamEvent.Failed failure) {
        settle(failureKind.get(), failure.error(), null);
      }
    }

    /**
     * 流自己结束了却还没有终态。
     *
     * <p>补一个 {@code Failed} 而不是补一个 {@code Terminal}：
     * 上游有没有把话说完是**无从判断**的 —— 连接关掉的那一刻，最后一段内容是不是完整，
     * 谁也不知道。把它记成成功，就是来源项目那个最贵的缺陷本身。
     */
    Flux<CanonicalStreamEvent> endWithoutTerminal() {
      if (state.isSettled()) {
        return Flux.empty();
      }
      CanonicalStreamEvent.Failed closed = new CanonicalStreamEvent.Failed(
          new GatewayError(CLOSED, "上游在没有给出终态的情况下结束了响应", 502));
      settle(OutcomeKind.UPSTREAM_FAILURE, closed.error(), null);
      return Flux.just(closed);
    }

    /**
     * 调用方中途断开：**不产生事件**（接收方已经不在了，往断开的下游发东西不成立），
     * 但**必须结算**成 {@code CLIENT_CANCELLED} —— 少记一次与记成成功，
     * 对下游的熔断器是同一种误导。而取消恰恰是唯一一种「上游是健康的」的失败形态。
     */
    void settleCancelled() {
      settle(OutcomeKind.CLIENT_CANCELLED,
          new GatewayError(CANCELLED, "调用方在响应结束之前断开", null), null);
    }

    /** 上游送来的字节不合这一份 wire format 的形状：归因到上游，但带一句话说明是什么不合。 */
    CanonicalStreamEvent.Failed malformed(Throwable failure) {
      failureKind.set(OutcomeKind.UPSTREAM_FAILURE);
      return new CanonicalStreamEvent.Failed(new GatewayError(
          MALFORMED, "上游的流式响应无法被解析：" + failure.getMessage(), 502));
    }

    void recordFailureKind(OutcomeKind kind) {
      failureKind.set(kind);
    }

    void settle(OutcomeKind kind, GatewayError error, CanonicalUsage reported) {
      if (reported != null) {
        usage.set(reported);
      }
      settle(kind, error);
    }

    /**
     * 结算。第二次进不来 —— 终态与取消同时发生的那一瞬间，
     * 「先查有没有结算过、再结算」两步的写法会结算两次。
     */
    private void settle(OutcomeKind kind, GatewayError error) {
      if (!state.claim()) {
        return;
      }
      settle.accept(new Outcome(requestId, serviceId, instanceId, kind,
          Duration.between(startedAt, Instant.now()), usage.get(), error));
    }
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
