/**
  领域模型与端口。

  <p>知道：Canonical 中间表示、{@code Outcome}、以及 gateway 要实现的端口签名。
  不该知道：Spring、Jackson、Reactor —— 依赖树里出现它们即构建失败（R1，由 enforcer 判红，
  理由是领域模型要能在没有任何容器的情况下被单测，ADR-0003 第 1 节）。

  <p>阶段 1 与票 03 / 04 的内容已经在这里：请求与响应的六型、两个端口（非流式与流式）、
  流式事件的六型、{@code Outcome}、以及结算的两个件（{@code OutcomeObserver} 与 {@code StreamState}）。
  结算的出口始终只有一个，那一点由 R9 守着。
 */
package io.github.airouter.core;
