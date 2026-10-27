/**
  字节与帧之间的转换，以及连接与超时。

  <p>知道：SSE 与逐行 JSON 的分帧规则、块边界、空闲超时。
  不该知道：任何业务包 —— faces、providers、routing、governance、config 一律不碰（R5 判红）。
  它只搬字节。

  <p>阶段 0 是占位包（只有 package-info）。
 */
package io.github.airouter.gateway.transport;
