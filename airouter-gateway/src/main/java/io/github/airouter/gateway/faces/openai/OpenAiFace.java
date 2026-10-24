package io.github.airouter.gateway.faces.openai;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.core.UpstreamFailure;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容的入站 Face。
 *
 * <p>它是客户端与网关之间那条协议边界的实现：把入站 JSON 译成 CanonicalRequest，
 * 交给 {@link CompletionPort} / {@link StreamingCompletionPort}，再把结果编成同一份 wire format 回去。
 * 它不知道对面是谁 —— 换一个 Provider 不需要动这里。
 *
 * <p>为什么不叫 controller：controller 是 Spring 的实现细节，
 * 而这个类的身份是「一种协议的实现」，恰好用 Spring 的注解表达了 HTTP 那一层。
 *
 * <p>流式与非流式共用同一个入站路径，由请求体里的 {@code stream} 分派 ——
 * 这不是设计取舍而是协议事实：OpenAI 兼容客户端只在同一个路径上改这个字段。
 */
@RestController
public final class OpenAiFace {

  /** 入站路径。 */
  public static final String PATH = "/v1/chat/completions";

  /** 每次响应都会带的请求标识头，成功与失败两条路径都一样。 */
  public static final String REQUEST_ID_HEADER = "X-Airouter-Request-Id";

  private static final System.Logger LOG = System.getLogger(OpenAiFace.class.getName());

  private final CompletionPort port;

  private final StreamingCompletionPort streamingPort;

  /**
   * @param port 非流式的完成能力，具体实现由 config 装配进来
   * @param streamingPort 流式的完成能力，同上
   */
  public OpenAiFace(CompletionPort port, StreamingCompletionPort streamingPort) {
    this.port = port;
    this.streamingPort = streamingPort;
  }

  /**
   * chat 请求。两种返回形态由请求体决定，不由 {@code Accept} 头决定：
   * OpenAI 兼容客户端在请求流式时并不改 Accept 头，只改 {@code stream} 字段。
   *
   * @param requestBody 入站请求体原文。这里取原文而不是绑定到一个 DTO：
   *                    未被建模的字段要能原样出境，而绑定会先把它们丢弃
   * @return 非流式是 JSON 响应体，流式是 SSE 帧流；出错时是同一形状的错误体
   */
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Object>> complete(@RequestBody Mono<String> requestBody) {
    String requestId = UUID.randomUUID().toString();
    return requestBody.defaultIfEmpty("")
        .map(body -> OpenAiInboundCodec.decodeRequest(body, requestId))
        .flatMap(request -> request.stream()
            ? streaming(requestId, request)
            : nonStreaming(requestId, request))
        .onErrorResume(failure -> Mono.just(failed(requestId, failure)));
  }

  private Mono<ResponseEntity<Object>> nonStreaming(String requestId, CanonicalRequest request) {
    return Mono.fromCompletionStage(port.complete(request))
        .map(response -> ok(requestId, response));
  }

  private Mono<ResponseEntity<Object>> streaming(String requestId, CanonicalRequest request) {
    return Mono.just(ResponseEntity.ok()
        .header(REQUEST_ID_HEADER, requestId)
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body((Object) frames(request)));
  }

  /**
   * 事件流 → SSE 帧流。
   *
   * <p>{@code [DONE]} 是**无条件**追加在末尾的，不是「上游说了 DONE 之后跟着写」：
   * 上游的哨兵经过 Provider 之后已经变成终态事件，这里不再认识它。
   * 追加无条件成立的依据是端口契约 —— 一条流有且只有一个终态，且它是最后一个事件。
   *
   * <p>出错时写出的是「错误帧 + [DONE]」而不是一个错误状态码：流已经开始写了，
   * 状态码在那之前就发出去了。半途改主意会让客户端收到一个既不是流也不是 JSON 的东西。
   */
  private Flux<String> frames(CanonicalRequest request) {
    return Flux.defer(() -> {
      StreamIdentity identity = new StreamIdentity();
      Flux<String> payloads = JdkFlowAdapter.flowPublisherToFlux(streamingPort.stream(request))
          .handle((event, sink) -> {
            identity.note(event);
            String payload = OpenAiInboundCodec.encodeStreamEvent(
                event, identity.id(), identity.model(), identity.created());
            if (payload != null) {
              sink.next(payload);
            }
          });
      return payloads
          .concatWith(Flux.just(OpenAiInboundCodec.DONE_SENTINEL))
          .onErrorResume(failure -> Flux.just(
              OpenAiInboundCodec.encodeError(describe(failure)),
              OpenAiInboundCodec.DONE_SENTINEL));
    });
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
    if (cause instanceof IllegalArgumentException) {
      return new GatewayError("bad_request", "请求体无法被解析", 400);
    }
    return new GatewayError("internal", "网关内部错误", 500);
  }

  /**
   * 剥掉异步传播加上去的那一层。
   *
   * <p>经过 {@code CompletableFuture} 的失败会被包一层 {@code CompletionException}：
   * 不剥掉它，「本地拒绝」这一类我们自己判的失败会在这一层被认成内部错误，
   * 于是 503 变成 500 —— 状态码是对客户端说的第一句话，说错了后面都不用看。
   */
  private static Throwable unwrap(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  /** 一条流在编码过程中需要记住的三个字段。它们属于这一次订阅，因此跟着 {@code defer} 一起新建。 */
  private static final class StreamIdentity {

    private String id = "";

    private String model = "";

    private Long created;

    void note(CanonicalStreamEvent event) {
      if (event instanceof CanonicalStreamEvent.Opening opening) {
        id = opening.id();
        model = opening.model();
        created = opening.created();
      }
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
  }
}
