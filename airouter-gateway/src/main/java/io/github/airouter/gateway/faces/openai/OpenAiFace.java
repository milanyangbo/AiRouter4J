package io.github.airouter.gateway.faces.openai;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.routing.CallerIdentityMissingException;
import io.github.airouter.gateway.routing.NoUsableInstanceException;
import io.github.airouter.gateway.routing.RoutedPort;
import io.github.airouter.gateway.routing.SelectionContext;
import io.github.airouter.gateway.select.ActiveCountMissingException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容的入站 Face。
 *
 * <p>它是客户端与网关之间那条协议边界的实现：把入站 JSON 译成 CanonicalRequest，
 * 交给端口，再把结果编成同一份 wire format 回去。
 * 它不知道对面是谁 —— 换一个 Provider 不需要动这里。
 *
 * <p>为什么不叫 controller：controller 是 Spring 的实现细节，
 * 而这个类的身份是「一种协议的实现」，恰好用 Spring 的注解表达了 HTTP 那一层。
 *
 * <p>流式与非流式共用**同一个入口**：OpenAI 兼容客户端在同一个路径上只改 {@code stream} 字段，
 * 不改 {@code Accept}，因此分派发生在请求体里，不在 HTTP 头里。
 * 分成两个端点（或按 {@code produces} 分派）的后果是客户端得改 header 才能拿到流 ——
 * 而现实里的客户端不会为了过一道网关改自己的调用方式。
 *
 * <p>流式响应**不设响应超时**：一条流要占多久由模型决定，
 * 基础设施替调用方决定「最多想多久」的表现是长答案被悄悄掐断（ADR-0005 第 4 节）。
 */
@RestController
public final class OpenAiFace {

  /** 入站路径。 */
  public static final String PATH = "/v1/chat/completions";

  /** 每次响应都会带的请求标识头，成功与失败两条路径都一样。 */
  public static final String REQUEST_ID_HEADER = "X-Airouter-Request-Id";

  /**
   * 调用方显式给出标识时用的头。没有它才退回远端地址（ADR-0011 第 5 节的 ①）。
   */
  public static final String CALLER_ID_HEADER = "X-Airouter-Caller-Id";

  private static final System.Logger LOG = System.getLogger(OpenAiFace.class.getName());

  private final RoutedPort completion;

  private final RoutedPort streaming;

  /**
   * @param routed 出网入口，**它自己同时是那两个 core 端口**（{@link RoutedPort} 继承它们）。
   *               两个字段取自同一个对象而不是各收一个参数，正是为了不给分岔留门：
   *               装配上不存在「非流式走这个、流式走那个」的写法，
   *               而「流式绕过治理」就是从那条缝里长出来的（ADR-0011 第 5 节末尾两条）。
   *
   *               这里的类型是 {@code RoutedPort} 而不是 core 那两个接口，
   *               唯一的原因是第 ① 步要把调用方标识交给链 ——
   *               core 端口的签名里只有 {@link CanonicalRequest}，装不下它。
   *               它仍然不认识 {@code providers}
   */
  public OpenAiFace(RoutedPort routed) {
    this.completion = routed;
    this.streaming = routed;
  }

  /**
   * chat 请求。
   *
   * @param requestBody 入站请求体原文。这里取原文而不是绑定到一个 DTO：
   *                    未被建模的字段要能原样出境，而绑定会先把它们丢弃
   * @param explicitCallerId 调用方显式给出的标识头，可以为 null
   * @param inbound 入站请求，只在没有显式标识时用来取远端地址
   * @return JSON 响应体（非流式）或 SSE 帧流（流式）；出错时是同一形状的错误体
   */
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Object>> complete(@RequestBody Mono<String> requestBody,
      @RequestHeader(value = CALLER_ID_HEADER, required = false) String explicitCallerId,
      ServerHttpRequest inbound) {
    String requestId = UUID.randomUUID().toString();
    // 第 ① 步：这次调用算谁的 —— 显式请求头 → 远端地址 → 空。链只取不校验。
    SelectionContext context = SelectionContext.of(callerIdOf(explicitCallerId, inbound));
    return requestBody.defaultIfEmpty("")
        .map(body -> OpenAiInboundCodec.decodeRequest(body, requestId))
        .flatMap(request -> request.stream()
            ? Mono.just(stream(requestId, request, context))
            : fromCompletion(completion.complete(request, context), requestId))
        .onErrorResume(failure -> Mono.just(failed(requestId, failure)));
  }

  /**
   * 流式那一条路。
   *
   * <p>**六步链在方法被调用的那一刻跑完，不是订阅时**（{@link RoutedPort#stream} 的契约，
   * 票 12 的设计决定：不新增「只解析不调用」的入口，把 defer 的边界挪到选择之后）。
   * 于是「选不出实例」「缺调用方标识」「在途计数查不到」这三类拒绝从这里同步抛出，
   * 落在 {@link #complete} 的 {@code onErrorResume} 里，客户端拿到的是
   * 与非流式**同一份**错误体（400/503），而不是 200 + {@code text/event-stream} 之后的断流。
   * 拒绝发生在状态码还定得下来的时候 —— 第一帧写出之后的失败才轮到
   * 「只能作为帧上的错误载荷」那条规矩。
   *
   * <p>{@code defer} 只包**编码与订阅**这一段：上游的字节在订阅时才被读，
   * 这与拒绝无关 —— 选择已经做完了，defer 里没有任何会再抛拒绝的东西。
   *
   * @param requestId 这一次请求的标识
   * @param request Canonical 请求
   * @param context 选择链的输入
   * @return SSE 响应
   */
  private ResponseEntity<Object> stream(String requestId, CanonicalRequest request,
      SelectionContext context) {
    Publisher<CanonicalStreamEvent> events = streaming.stream(request, context);
    Flux<ServerSentEvent<String>> frames = Flux.defer(() -> {
      OpenAiInboundCodec.StreamEncoder encoder = OpenAiInboundCodec.newStreamEncoder();
      return JdkFlowAdapter.flowPublisherToFlux(events)
          .concatMapIterable(encoder::encode)
          .map(payload -> ServerSentEvent.builder(payload).build());
    });
    return ResponseEntity.ok()
        .header(REQUEST_ID_HEADER, requestId)
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(frames);
  }

  private static Mono<ResponseEntity<Object>> fromCompletion(
      CompletionStage<CanonicalResponse> completion, String requestId) {
    return Mono.fromCompletionStage(completion).map(response -> ok(requestId, response));
  }

  /**
   * 调用方标识的来源：**显式请求头 → 远端地址 → 空**。
   *
   * <p>空是这一版的合法结果，不是兜底：认证（阶段 6 / ADR-0018）接上之后来源换成认证结果，
   * 而这里的顺序不变。链不校验它 —— 校验是认证的事。
   */
  private static String callerIdOf(String explicitCallerId, ServerHttpRequest inbound) {
    if (explicitCallerId != null && !explicitCallerId.isBlank()) {
      return explicitCallerId;
    }
    InetSocketAddress remote = inbound.getRemoteAddress();
    if (remote == null || remote.getAddress() == null) {
      return null;
    }
    return remote.getAddress().getHostAddress();
  }

  private static ResponseEntity<Object> ok(String requestId, CanonicalResponse response) {
    return ResponseEntity.ok()
        .header(REQUEST_ID_HEADER, requestId)
        .contentType(MediaType.APPLICATION_JSON)
        .body((Object) OpenAiInboundCodec.encodeResponse(response));
  }

  /**
   * 唯一的出错收口点。
   *
   * <p>所有的失败在这里变成同一种形状。分类的顺序是有意的：
   * 已知的上游失败用上游给的建议状态码，坏请求是我们自己判的 400，
   * 其余一律 500 且**不把异常文本交出去** —— 内部异常里的路径与类名不是客户端需要知道的事。
   */
  private static ResponseEntity<Object> failed(String requestId, Throwable failure) {
    GatewayError error = describe(failure);
    LOG.log(System.Logger.Level.ERROR, "[" + requestId + "] " + error.type(), failure);
    return ResponseEntity.status(error.httpStatus())
        .header(REQUEST_ID_HEADER, requestId)
        .contentType(MediaType.APPLICATION_JSON)
        .body((Object) OpenAiInboundCodec.encodeError(error));
  }

  private static GatewayError describe(Throwable failure) {
    Throwable cause = unwrap(failure);
    if (cause instanceof UpstreamFailure upstream) {
      GatewayError error = upstream.error();
      if (error.httpStatus() != null) {
        return error;
      }
      return new GatewayError(error.type(), error.message(), 502);
    }
    if (cause instanceof NoUsableInstanceException rejected) {
      // 选不出实例：这一次调用根本没发生过（不出网、也不结算，ADR-0013 (e)），
      // 所以对客户端说的是 503（现在没处可去，过会儿再来），而不是 500（网关自己坏了）。
      return new GatewayError(rejected.rejection().name().toLowerCase(Locale.ROOT),
          rejected.getMessage(), 503);
    }
    if (cause instanceof CallerIdentityMissingException missing) {
      // 选择需要知道这一次调用算谁的，而它认不出来：这是客户端能补的东西（带上调用方标识），
      // 所以是 400 而不是 503 —— 503 说的是「我这边暂时不行，你过会儿重试」，重试不会带来身份。
      // 粘滞与两个 hash 策略共用这一个形状，于是不存在「同样是缺标识，一个给 400 另一个给 503」。
      // 也不结算：这一次调用还没有发生过（ADR-0013 (e)）。
      return new GatewayError("caller_identity_required", missing.getMessage(), 400);
    }
    if (cause instanceof ActiveCountMissingException missing) {
      // 选得出候选，却查不到它压着几个请求：装配把在途计数接丢了一半，
      // 客户端做什么都改不了它。503 说的是「现在没处可去」—— 正是这里的情况。
      return new GatewayError("instance_selection_unavailable", missing.getMessage(), 503);
    }
    if (cause instanceof IllegalArgumentException) {
      return new GatewayError("bad_request", "请求体无法被解析", 400);
    }
    return new GatewayError("internal", "网关内部错误", 500);
  }

  /**
   * 剥掉异步传播加上去的那一层。
   *
   * <p>经过 {@code CompletableFuture} 的失败会被包一层 {@code CompletionException}：
   * 不剥掉它，「上游拒绝了」这一类我们自己判的失败会在这一层被认成内部错误，
   * 于是 404 变成 500 —— 状态码是对客户端说的第一句话，说错了后面都不用看。
   */
  private static Throwable unwrap(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }
}
