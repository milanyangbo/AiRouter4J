package io.github.airouter.gateway.transport;

import io.github.airouter.core.GatewayError;
import io.github.airouter.core.UpstreamFailure;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
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
 * <p>两个超时的分工是刻意的，不要合并成一个：
 * <ul>
 *   <li>{@code responseTimeout} —— 非流式的**总时长**上限；
 *   <li>{@code idleTimeout} —— 流式的**帧间静默**上限。
 *       给一条流设总时长上限等于给「模型最多想多久」设限，而那件事网关无权决定；
 *       能设的是「上游多久不说话就算它出事了」。GLOSSARY 把这条写成了 SLO 三角的一条边。
 * </ul>
 */
public final class OutboundWire {

  private static final String UNREACHABLE = "upstream_unreachable";
  private static final String TIMEOUT = "upstream_timeout";

  private final WebClient client;
  private final Duration responseTimeout;
  private final Duration idleTimeout;

  /**
   * @param baseUrl 上游基地址，含 {@code /v1} 之类的前缀
   * @param responseTimeout 非流式调用的总上限
   * @param idleTimeout 流式调用允许的帧间静默时长
   */
  public OutboundWire(String baseUrl, Duration responseTimeout, Duration idleTimeout) {
    this.client = WebClient.builder().baseUrl(baseUrl).build();
    this.responseTimeout = responseTimeout;
    this.idleTimeout = idleTimeout;
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

  /**
   * 发出一次 JSON POST 并按 SSE 逐帧读回。
   *
   * <p>返回的每一个元素是一个完整事件的载荷（见 {@link SseFrameReader}），
   * 包括上游发出的结束哨兵本身 —— 哨兵是上游说的话，认出它是 Provider 的事，
   * 这里不替它决定流什么时候算完。
   *
   * <p>非 2xx 以 {@link UpstreamRejection} 结束这条流而不是静默给一个空流：
   * 「上游明确拒绝」与「上游什么都没说」在客户端那里是两种结果，必须能被区分。
   *
   * @param path 相对路径
   * @param headers 请求头
   * @param jsonBody 请求体原文
   * @return 帧流；帧间静默超过 {@code idleTimeout} 时以 {@code UPSTREAM_TIMEOUT} 结束
   */
  public Flux<String> postStream(String path, Map<String, String> headers, String jsonBody) {
    return client.post()
        .uri(path)
        .headers(httpHeaders -> headers.forEach(httpHeaders::add))
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(jsonBody)
        .retrieve()
        .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(body -> new UpstreamRejection(response.statusCode().value(), body)))
        .bodyToFlux(DataBuffer.class)
        .transform(SseFrameReader::read)
        .timeout(idleTimeout)
        .onErrorMap(TimeoutException.class, cause -> new UpstreamFailure(
            new GatewayError(TIMEOUT, "上游在 " + idleTimeout + " 内没有再发来任何帧", 504), cause))
        .onErrorMap(cause -> !(cause instanceof UpstreamFailure)
            && !(cause instanceof UpstreamRejection), cause -> new UpstreamFailure(
            new GatewayError(UNREACHABLE, "无法连上上游：" + cause.getMessage(), 502), cause));
  }
}
