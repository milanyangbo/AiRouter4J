/**
 * 出站协议：把 Canonical 翻译成厂商的 wire format（ADR-0003 第 2 节）。
 *
 * <p>新增一个 provider 的完整改动应当集中在这个包内，faces 侧零改动。
 * 这条性质由结构规则 R3 与后续「第二个 provider」那一票共同保证。
 */
package io.github.airouter.gateway.providers;
