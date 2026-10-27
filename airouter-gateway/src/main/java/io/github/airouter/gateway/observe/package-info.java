/**
  结算点、指标与追踪。

  <p>知道：{@code Outcome} 的形状。不该知道：怎么判定成败 —— 它只能收下结算点给出的结论，
  自己看一眼状态码就下判断是 R4 与 R9 一起要拦的那种写法（ADR-0005 第 1 节）。

  <p>阶段 0 是占位包（只有 package-info）。
 */
package io.github.airouter.gateway.observe;
