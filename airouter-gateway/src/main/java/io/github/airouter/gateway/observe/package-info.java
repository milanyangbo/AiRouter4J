/**
 * 观测与结算的落点（ADR-0006 第 2 节）。
 *
 * <p>这个包认识 {@code core} 的 {@code Outcome}，不认识 Face、Provider 与 transport ——
 * 它消费的是「已经结算完的结论」，不是任何一处的中间状态。正因为它不认识它们，
 * 「结算点唯一」才成立：谁想拿到一次调用的结果，都只能到这里来。
 */
package io.github.airouter.gateway.observe;
