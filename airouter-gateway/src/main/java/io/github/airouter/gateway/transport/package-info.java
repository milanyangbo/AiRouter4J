/**
 * HTTP 出站：连接管理、超时、SSE 读取（ADR-0003 第 2 节）。
 *
 * <p>只搬字节。它一旦知道实例选择或限流，换一个出站实现就会牵动业务代码 —— 由结构规则 R5 判红。
 */
package io.github.airouter.gateway.transport;
