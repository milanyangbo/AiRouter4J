/**
  OpenAI 兼容端点的出站实现。

  <p>知道：这一家的请求怎么写、响应怎么读、错误体里哪一段可以给人看。
  不该知道：请求是从哪个 Face 进来的（R3b 判红）。

  <p>它覆盖**所有** OpenAI 兼容端点，差别留在 {@code Endpoint} 的配置里，
  而不是派生出一批近乎相同的子类 —— 因此这里的类名与注释都不提具体厂商的名字（ADR-0006 第 1 节）。
 */
package io.github.airouter.gateway.providers.openai;
