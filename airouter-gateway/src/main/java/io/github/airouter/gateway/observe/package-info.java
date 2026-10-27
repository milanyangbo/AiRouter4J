/**
  结算点、指标与追踪。

  <p>知道：{@code Outcome} 的形状。不该知道：怎么判定成败 —— 它只能收下结算点给出的结论，
  自己看一眼状态码就下判断是 R4 与 R9 一起要拦的那种写法（ADR-0005 第 1 节）。

  <p>票 04 的第一件东西是 {@link io.github.airouter.gateway.observe.SettlementLog}：
  它是「结算记录」在本阶段的形态 —— 一个能被读到的账本，而不是一个指标系统。
  熔断、配额、调用历史随后接的是同一个 {@code OutcomeObserver} 接口，不是这个账本。
 */
package io.github.airouter.gateway.observe;
