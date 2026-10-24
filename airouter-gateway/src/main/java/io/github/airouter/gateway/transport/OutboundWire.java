package io.github.airouter.gateway.transport;

import io.github.airouter.core.GatewayError;
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
 */
public final class OutboundWire {

  private static final String UNREACHABLE = "upstream_unreachable";
  private static final String TIMEOUT = "upstream_timeout";

  private final WebClient client;
  private final Duration responseTimeout;

  /**
   * @param baseUrl 上游基地址，含 {@code /v1} 之类的前缀
   * @param responseTimeout 单次调用的总上限。注意它作用在**这一次调用**上，
   *                        未来接进流式读取之后，这个地方要区分「总时长」与「帧间空闲」两种超时
   */
  public OutboundWire(String baseUrl, Duration responseTimeout) {
    this.client = WebClient.builder().baseUrl(baseUrl).build();
    this.responseTimeout = responseTimeout;
  }

  /**
   * 发出一次 JSON POST，原样带回状态码与响应体。
   *
   * @param path 相对路径
   * @param headers 请求头。认证头由调用方给出：怎么证明身份是厂商协议的一部分，传输层不该知道
   * @param jsonBody 请求体原文
   * @return 原始结果；非 2xx 不作为异常抛出
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
            new GatewayError(TIMEOUT, "上游在 " + responseTimeout + " 内没有完成响应", 504), cause))
        .onErrorMap(cause -> !(cause instanceof UpstreamFailure), cause -> new UpstreamFailure(
            new GatewayError(UNREACHABLE, "无法连上上游：" + cause.getMessage(), 502), cause));
  }
}
