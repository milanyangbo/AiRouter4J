/**
  OpenAI 兼容协议的入站 Face。

  <p>它面向客户端说的协议，把它译成 Canonical；响应回到这里再被编码成同一份 wire format。
  它不知道请求最终去了哪一家 Provider（结构规则 R3a 判红）——
  「同一个请求由哪个实现去完成」这件事由 {@code config} 决定，不由这里决定。
 */
package io.github.airouter.gateway.faces.openai;
