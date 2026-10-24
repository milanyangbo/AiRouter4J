package io.github.airouter.gateway.providers.openai;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.transport.OutboundWire;
import io.github.airouter.gateway.transport.UpstreamReply;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * OpenAI 兼容端点的 Provider。
 *
 * <p>它做三件事，其余一概不做：把 Canonical 请求译成上游 JSON、经由 {@link OutboundWire} 发出、
 * 把上游响应译回 Canonical。它不认识 Face，也不认识证书与 key 是怎么来的 ——
 * 那些由 {@code config} 在构造时给进来。
 *
 * <p>这里的变量名与注释都不提具体厂商的名字：同一个实现覆盖所有 OpenAI 兼容端点，
 * 差别被留在 Endpoint 的配置里，而不是派生出一批近乎相同的子类。
 */
public final class OpenAiCompatibleProvider implements CompletionPort {

  /** 状态码在 400 到 499 之间时，把上游的状态原样交给客户端。 */
  private static final int CLIENT_ERROR_FLOOR = 400;

  private static final int SERVER_ERROR_FLOOR = 500;

  /** 上游故障时对客户端给出的状态码：这是网关这一层的事，不该让客户端去猜上游发生了什么。 */
  private static final int BAD_GATEWAY = 502;

  private final OutboundWire wire;
  private final Map<String, String> headers;
  private final String defaultModel;

  /**
   * @param wire 出站口，由 {@code config} 装配
   * @param apiKey 上游凭证，由环境变量来
   * @param defaultModel 请求没有指定模型时使用的值
   */
  public OpenAiCompatibleProvider(OutboundWire wire, String apiKey, String defaultModel) {
    this.wire = wire;
    this.headers = Map.of("Authorization", "Bearer " + apiKey);
    this.defaultModel = defaultModel;
  }

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    String body = OpenAiUpstreamCodec.encodeRequest(withResolvedModel(request));
    return wire.post(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body)
        .map(this::toCanonical)
        .toFuture();
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
    throw new UpstreamFailure(clientError(reply), null);
  }

  private GatewayError clientError(UpstreamReply reply) {
    if (reply.status() >= CLIENT_ERROR_FLOOR && reply.status() < SERVER_ERROR_FLOOR) {
      return new GatewayError(
          "upstream_4xx",
          "上游拒绝了这次请求（" + reply.status() + "）：" + OpenAiUpstreamCodec.errorMessageFrom(reply.body()),
          reply.status());
    }
    return new GatewayError(
        "upstream_5xx",
        "上游处理本次请求时失败（" + reply.status() + "）：" + OpenAiUpstreamCodec.errorMessageFrom(reply.body()),
        BAD_GATEWAY);
  }
}
