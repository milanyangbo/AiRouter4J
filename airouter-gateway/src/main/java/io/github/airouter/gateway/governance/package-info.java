/**
 * 限流、熔断、配额与脱敏（ADR-0003 第 2 节）。
 *
 * <p>与 {@code routing} 同层：只操作 Canonical 与抽象状态，不做 IO。由结构规则 R4 判红。
 *
 * <p>这里的每一个组件**只消费 {@code Outcome}**（ADR-0007 第 1 节）：
 * 它看不到 HTTP 状态码，也看不到异常类型 —— 那两样一旦进来，
 * 「同一个请求在熔断器看来失败、在指标看来成功」就只是时间问题。由结构规则 R9 判红。
 */
package io.github.airouter.gateway.governance;
