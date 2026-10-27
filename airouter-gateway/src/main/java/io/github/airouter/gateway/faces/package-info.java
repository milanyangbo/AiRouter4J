/**
  入站协议与 Canonical 之间的翻译。

  <p>知道：调用方说的协议（OpenAI 兼容的那一套请求/响应形状）。
  不该知道：背后有几个实例、走哪家厂商、上游的 wire format ——
  一旦它认识 providers，加一个 provider 就得回来改这里（R3a 判红）。

  <p>阶段 0 是占位包（只有 package-info）。
 */
package io.github.airouter.gateway.faces;
