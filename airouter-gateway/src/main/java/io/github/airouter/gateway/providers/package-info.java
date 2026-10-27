/**
  Canonical 与上游 wire format 之间的翻译，以及终态的产生。

  <p>知道：一家厂商的协议与它怎么结束一条流。不该知道：请求从哪个 Face 进来（R3b 判红）。

  <p>成败的结论只有一个出口：只有这里可以构造 {@code Outcome}，别处自己造一个就有了自己的成败口径
  （R9 判红，ADR-0005 第 1 节）。

  <p>阶段 2 的第一个 Provider（OpenAI 兼容）在这里。结算产生的 {@code Outcome}
     随票 04 出现，出口仍然只有这一个。
 */
package io.github.airouter.gateway.providers;
