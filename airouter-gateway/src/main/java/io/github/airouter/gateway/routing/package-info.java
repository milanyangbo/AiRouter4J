/**
 * 选择链的编排：**六步的前三步与拒绝原因**（ADR-0011 第 5 节，那里是六步的唯一定义处）。
 *
 * <p>本包知道：一次调用要哪个 Service、要求哪些标签、调用方是谁（只是标识，不校验），
 * 以及哪些实例当前被可用性闸门排除。
 *
 * <p>本包**不该知道**：HTTP 状态码、任何 IO、请求的具体协议形态
 * （{@code faces} / {@code providers}）、字节怎么走（{@code transport}）、配置长什么样
 * （{@code config}）。这几条由结构规则 R4 判红 —— 正因如此，本包可以脱离容器被断言：
 * 给定候选与上下文，结果可复现，不需要知道当时网络是什么状态。
 *
 * <p>六步落在 {@link io.github.airouter.gateway.routing.SelectionChain} 一处。
 * ④⑤⑥（加权展开 / 粘滞 / 策略）在 {@code select} 包，由本包在编排中调用。
 */
package io.github.airouter.gateway.routing;
