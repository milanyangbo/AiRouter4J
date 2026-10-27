package io.github.airouter.gateway.faces.openai;

import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.UpstreamFailure;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容的入站 Face。
 *
 * <p>它是客户端与网关之间那条协议边界的实现：把入站 JSON 译成 CanonicalRequest，
 * 交给 {@link CompletionPort}，再把结果编成同一份 wire format 回去。
 * 它不知道对面是谁 —— 换一个 Provider 不需要动这里。
 *
 * <p>为什么不叫 controller：controller 是 Spring 的实现细节，
 * 而这个类的身份是「一种协议的实现」，恰好用 Spring 的注解表达了 HTTP 那一层。
 *
 * <p>阶段 2 只有非流式那一条路。流式随票 03 接入**同一个入口**：
 * OpenAI 兼容客户端在同一个路径上只改 {@code stream} 字段，不改 {@code Accept}，
 * 因此分派发生在请求体里，不在 HTTP 头里。
 */
@RestController
public final class OpenAiFace {

  /** 入站路径。 */
  public static final String PATH = "/v1/chat/completions";

  /** 每次响应都会带的请求标识头，成功与失败两条路径都一样。 */
  public static final String REQUEST_ID_HEADER = "X-Airouter-Request-Id";

  private static final System.Logger LOG = System.getLogger(OpenAiFace.class.getName());

  private final CompletionPort port;

  /**
   * @param port 非流式的完成能力，具体实现由 {@code config} 装配进来
   */
  public OpenAiFace(CompletionPort port) {
    this.port = port;
  }

  /**
   * chat 请求。
   *
   * @param requestBody 入站请求体原文。这里取原文而不是绑定到一个 DTO：
   *                    未被建模的字段要能原样出境，而绑定会先把它们丢弃
   * @return JSON 响应体；出错时是同一形状的错误体
   */
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Object>> complete(@RequestBody Mono<String> requestBody) {
    String requestId = UUID.randomUUID().toString();
    return requestBody.defaultIfEmpty("")
        .map(body -> OpenAiInboundCodec.decodeRequest(body, requestId))
        .flatMap(request -> Mono.fromCompletionStage(port.complete(request))
            .map(response -> ok(requestId, response)))
        .onErrorResume(failure -> Mono.just(failed(requestId, failure)));
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
   * 不剥掉它，「上游拒绝了」这一类我们自己判的失败会在这一层被认成内部错误，
   * 于是 404 变成 500 —— 状态码是对客户端说的第一句话，说错了后面都不用看。
   */
  private static Throwable unwrap(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }
}
