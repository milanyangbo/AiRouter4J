package io.github.airouter.gateway.transport;

/**
 * 一次出站调用拿到的原始结果。
 *
 * <p>它只携带状态码与响应体原文，**不含任何解读**：4xx 与 5xx 是 Provider 的语义问题，
 * 传输层不做判断，也不替上游改写。新增一种故障形态时，要改的是 Provider 的理解，而不是这里的分支。
 *
 * @param status HTTP 状态码
 * @param body 响应体原文。空响应体是空串，不是 null
 */
public record UpstreamReply(int status, String body) {
}
