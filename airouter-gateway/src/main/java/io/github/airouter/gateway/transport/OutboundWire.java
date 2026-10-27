package io.github.airouter.gateway.transport;

import io.github.airouter.core.GatewayError;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.UpstreamFailure;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 唯一的 HTTP 出站口。
 *
 * <p>它被建在一个地方，由 {@code config} 装配给需要出站的那一侧。
 * 不允许任何模块各自 new 一个客户端：那样 SSRF 守卫、超时、连接池这些约束就没有共同的挂载点
 * （GLOSSARY「SSRF 守卫」要求所有出站经过唯一出口）。
 *
 * <p>构造参数是普通值而不是配置对象：这个类不允许知道配置包的存在（结构规则 R5）。
 *
 * <p>现阶段只有非流式那一个方向，因此只有一个超时 —— 非流式的**总时长**上限。
 * 流式接进来时会多出一个含义不同的超时（帧间静默），那两个不能合并成一个：
 * 给一条流设总时长上限等于替调用方决定「模型最多想多久」，而那件事网关无权决定。
 */
public final class OutboundWire {

  /** 连不上的错误类别。客户端看到它时，网关与上游之间这条路上什么都没有发生。 */
  private static final String UNREACHABLE = "upstream_unreachable";

  private static final String TIMEOUT = "upstream_timeout";

  private final WebClient client;

  private final Duration responseTimeout;

  /**
   * @param baseUrl 上游基地址，含 {@code /v1} 之类的前缀
   * @param responseTimeout 非流式调用的总上限
   */
  public OutboundWire(String baseUrl, Duration responseTimeout) {
    this.client = WebClient.builder().baseUrl(baseUrl).build();
    this.responseTimeout = responseTimeout;
  }

  /**
   * 发出一次 JSON POST，原样带回状态码与响应体。
   *
   * <p>非 2xx **不作为异常抛出**：那是「上游明确地回答了」，与「够不着上游」是两件不同的事，
   * 在这一层把它们合并成同一个 {@code onStatus} 回调，会让后者在客户端那里变成 502 而前者变成 404 的区别消失。
   *
   * @param path 相对路径
   * @param headers 请求头。认证头由调用方给出：怎么证明身份是厂商协议的一部分，传输层不该知道
   * @param jsonBody 请求体原文
   * @return 原始结果
   */
  public Mono<UpstreamReply> post(String path, Map<String, String> headers, String jsonBody) {
    return client.post()
        .uri(path)
        .headers(httpHeaders -> headers.forEach(httpHeaders::add))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(jsonBody)
        .exchangeToMono(response -> response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(body -> new UpstreamReply(response.statusCode().value(), body)))
        .timeout(responseTimeout)
        .onErrorMap(TimeoutException.class, cause -> new UpstreamFailure(
            OutcomeKind.UPSTREAM_TIMEOUT,
            new GatewayError(TIMEOUT, "上游在 " + responseTimeout + " 内没有完成响应", 504), cause))
        .onErrorMap(cause -> !(cause instanceof UpstreamFailure), cause -> new UpstreamFailure(
            new GatewayError(UNREACHABLE, "无法连上上游：" + cause.getMessage(), 502), cause));
  }
}
