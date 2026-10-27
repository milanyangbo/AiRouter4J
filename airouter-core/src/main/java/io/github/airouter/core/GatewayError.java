package io.github.airouter.core;

/**
 * 网关自己定义的错误形态。
 *
 * <p>它是 Canonical 的一部分，因此 Face 侧不必知道上游的错误长什么样，
 * 也不必知道 transport 层之外任何具体故障。
 *
 * @param type 错误类别，例如 upstream_4xx / upstream_5xx / upstream_unreachable / bad_request
 * @param message 可以给人看的说明，不含凭证
 * @param httpStatus 建议的对客户端状态码；网关自己决定的故障才会用到上游之外的取值
 */
public record GatewayError(String type, String message, Integer httpStatus) {
}
