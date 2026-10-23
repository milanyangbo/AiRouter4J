/**
 * 限流、熔断、配额与脱敏（ADR-0003 第 2 节）。
 *
 * <p>与 {@code routing} 同层：只操作 Canonical 与抽象状态，不做 IO。由结构规则 R4 判红。
 */
package io.github.airouter.gateway.governance;
