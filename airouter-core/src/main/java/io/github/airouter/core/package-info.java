/**
  领域模型与端口。

  <p>知道：Canonical 中间表示、{@code Outcome}、以及 gateway 要实现的端口签名。
  不该知道：Spring、Jackson、Reactor —— 依赖树里出现它们即构建失败（R1，由 enforcer 判红，
  理由是领域模型要能在没有任何容器的情况下被单测，ADR-0003 第 1 节）。

  <p>阶段 0 这里是占位：模型与端口随阶段 1 进入。
 */
package io.github.airouter.core;
