package io.github.airouter.gateway.providers.openai;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CompletionPort;
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
 * <p>这里的类名与注释都不提具体厂商的名字：同一个实现覆盖所有 OpenAI 兼容端点，
 * 差别被留在 {@code Endpoint} 的配置里，而不是派生出一批近乎相同的子类（ADR-0006 第 1 节）。
 *
 * <p>阶段 2 只实现非流式那一个端口。流式随票 03 接入，届时「一条流恰好结算一次」
 * 那类纪律也不落在本类里 —— 它们属于所有形态共用的一份结算逻辑。
 */
public final class OpenAiCompatibleProvider implements CompletionPort {

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
    CanonicalRequest resolved = resolveModel(request, defaultModel);
    String body = OpenAiUpstreamCodec.encodeRequest(resolved);
    return wire.post(OpenAiUpstreamCodec.CHAT_COMPLETIONS_PATH, headers, body)
        .map(this::toCanonical)
        .toFuture();
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
