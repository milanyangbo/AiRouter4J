package io.github.airouter.gateway.transport;

import io.github.airouter.core.GatewayError;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.UpstreamFailure;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
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
 * <p>两个方向各有自己的时间约束，**不能合并成一个**：
 *
 * <ul>
 *   <li>非流式：{@code responseTimeout} 是**总时长**上限 —— 一次调用该在多久之内有结果
 *   <li>流式：{@code idleTimeout} 是**块间**上限 —— 两块之间最多静默多久
 * </ul>
 *
 * <p>给流式的出站套上非流式那个总时长上限，等于替调用方决定「模型最多想多久」：
 * 一条想了四十秒才答完的流会被掐断，客户端拿到的是一条没有终态的半截响应。
 */
public final class OutboundWire {

  /** 连不上的错误类别。客户端看到它时，网关与上游之间这条路上什么都没有发生。 */
  private static final String UNREACHABLE = "upstream_unreachable";

  private static final String TIMEOUT = "upstream_timeout";

  private final WebClient client;

  private final Duration responseTimeout;

  private final int maxFrameBytes;

  /**
   * @param baseUrl 上游基地址，含 {@code /v1} 之类的前缀
   * @param responseTimeout 非流式调用的总上限
   * @param bufferBytes 聚合读取的缓冲区上限：一次非流式响应最多允许多少字节进内存。
   *                    流式不聚合，因此不受它约束 —— 那正是流式该有的样子
   * @param maxFrameBytes 单帧上限：一条流上「一个换行之前最多允许多少字节」。
   *                      它是流式这一侧真正会吃内存的地方：一个永远不发换行的上游会让缓存无限增长，
   *                      而累积内容上限管不到它 —— 后者作用在解码之后，这个上限作用在解码之前
   */
  public OutboundWire(String baseUrl, Duration responseTimeout, int bufferBytes, int maxFrameBytes) {
    this.client = WebClient.builder()
        .baseUrl(baseUrl)
        .exchangeStrategies(ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(bufferBytes))
            .build())
        .build();
    this.responseTimeout = responseTimeout;
    this.maxFrameBytes = maxFrameBytes;
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
        .onErrorMap(cause -> !isAttributed(cause), cause -> new UpstreamFailure(
            new GatewayError(UNREACHABLE, "无法连上上游：" + cause.getMessage(), 502), cause));
  }

  /**
   * 发出一次 JSON POST，并按调用方给定的**读法**把响应体切成帧。
   *
   * <p>这里**只做分帧**：{@code data: [DONE]} 是不是结束哨兵、一段载荷里有没有 {@code finish_reason}，
   * 都留给 Provider 去判断（ADR-0006 第 2 节）。传输层认下哨兵，等于替某一个厂商的协议做决定。
   *
   * <p>用哪一种读法由**形态**决定，而形态住在 {@code providers}。
   * 结构规则 R5 不允许这里去认识它，所以进来的是「怎么造一个分帧器」这个函数，
   * 而不是一个形态枚举 —— 管道仍然只搬字节，读法是被人交给它的。
   *
   * <p>非 2xx 以 {@link UpstreamRejection} 传播：它只报告「上游明确地拒绝了」这个事实而不带判断 ——
   * 4xx 与 5xx 各自该让客户端看到什么是网关的策略。
   *
   * <p>时间约束只有 {@code idleTimeout} 一个，且它是**块间**时长（每收到一块就重新计时）：
   * 一条想很久但一直在说话的流不会被误杀，而一条收了连接就不再出声的流会被判超时。
   *
   * @param path 相对路径
   * @param headers 请求头
   * @param jsonBody 请求体原文
   * @param idleTimeout 块间静默上限。它必须显式给进来：让它掉进某个默认值，
   *                    等于把「一条静默的流最多占多久」变成一件只有看过实现才知道的事
   * @param framerFactory 按单帧上限造一个分帧器。每个订阅造一个：分帧器带着
   *                      「上一行没攒完」的状态，共享实例会让两次调用互相污染对方的半行
   * @return 帧流；上游拒绝或静默超时时以错误结束
   */
  public Flux<UpstreamFrame> postStream(String path, Map<String, String> headers, String jsonBody,
      Duration idleTimeout, IntFunction<UpstreamFramer> framerFactory) {
    return client.post()
        .uri(path)
        .headers(httpHeaders -> headers.forEach(httpHeaders::add))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(jsonBody)
        .exchangeToFlux(response -> {
          if (!response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMapMany(body -> Flux.error(new UpstreamRejection(response.statusCode().value(), body)));
          }
          // 分帧器带订阅级的状态，因此它建在这里而不是做成字段：
          // 共享一个实例会让两次流式调用互相污染对方的半行。
          UpstreamFramer framer = framerFactory.apply(maxFrameBytes);
          return response.bodyToFlux(DataBuffer.class)
              .map(OutboundWire::drain)
              .concatMapIterable(framer::push)
              .concatWith(Flux.defer(() -> Flux.fromIterable(framer.finish())));
        })
        .timeout(idleTimeout)
        .onErrorMap(TimeoutException.class, cause -> new UpstreamFailure(
            OutcomeKind.UPSTREAM_TIMEOUT,
            new GatewayError(TIMEOUT, "上游静默超过 " + idleTimeout + " 没有给出下一块", 504), cause))
        .onErrorMap(cause -> !isAttributed(cause), cause -> new UpstreamFailure(
            new GatewayError(UNREACHABLE, "无法连上上游：" + cause.getMessage(), 502), cause));
  }

  /**
   * 取走缓冲里的字节并立刻归还。
   *
   * <p>借用后不归还会在池化的分配器上表现为内存缓慢增长；
   * 而「先攒够一帧再解码」这类写法会让归还时机变得难以判断，所以这里取完就还。
   */
  private static byte[] drain(DataBuffer buffer) {
    byte[] bytes = new byte[buffer.readableByteCount()];
    buffer.read(bytes);
    DataBufferUtils.release(buffer);
    return bytes;
  }

  /**
   * 这个失败是不是已经被归过因了。
   *
   * <p>{@link UpstreamFailure}（静默超时、够不着）与 {@link UpstreamRejection}（上游明确拒绝）
   * 都是本层自己下的结论，其余异常才需要被收敛成「够不着上游」。
   * 少了这一个判断，上游的 4xx 会在这一层被改写成 502 —— 状态码是对客户端说的第一句话。
   */
  private static boolean isAttributed(Throwable cause) {
    return cause instanceof UpstreamFailure || cause instanceof UpstreamRejection;
  }
}
