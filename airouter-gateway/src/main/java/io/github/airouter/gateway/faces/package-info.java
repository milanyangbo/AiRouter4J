/**
 * 入站协议：把外部请求翻译成 Canonical（ADR-0003 第 2 节）。
 *
 * <p>归属判断的唯一依据是「代码知道多少外部世界」：这里只知道入站协议的形状，
 * 不知道任何厂商的出站格式 —— 后者属于 {@code providers}。这两个包之间的隔离由结构规则 R3 判红。
 */
package io.github.airouter.gateway.faces;
