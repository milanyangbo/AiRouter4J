/**
 * 实例选择、健康检查、重试与降级（ADR-0003 第 2 节）。
 *
 * <p>只操作 Canonical 与抽象状态，不做 IO：这里的东西要能在没有容器、没有网络的情况下被单测。
 * 由结构规则 R4 判红。
 */
package io.github.airouter.gateway.routing;
