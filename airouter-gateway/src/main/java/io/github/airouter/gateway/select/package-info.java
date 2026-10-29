/**
 * 候选 + 权重 → 一个实例：**选择链的 ④⑤⑥**（ADR-0011 第 5 节）。
 *
 * <p>本包是**纯函数**：不做 IO、不读健康、不看 HTTP 状态码、不认识注册表里那个能出网的对象。
 * 随机源、轮询光标、活跃连接数、延迟样本这些外部输入一律是**参数**，由装配层递进来 ——
 * 于是「给定输入 → 结果可复现」是一条真能写的断言，判策略对不对不需要起端点（ADR-0011 第 2 节 (a)）。
 *
 * <p>候选的形状是 {@link io.github.airouter.gateway.select.Candidate}：只有 id / 权重 / 标签。
 * 用注册表里的 {@code RegisteredInstance} 也可以跑通，但那样每判一次「选得对不对」
 * 就得先造出一个能出网的东西 —— 而这与选择毫无关系。
 *
 * <p>加权是这里**独立的一步**，不在任何一个策略里：六个策略共用它一份，
 * 于是不存在「某个策略忘了加权」这种静默失效
 * （来源项目的 `CachedWeightedSelector` 实测只有轮询在用，最少连接根本没有）。
 * 它有两个形状，因为问的问题不同：
 *
 * <ul>
 *   <li>{@link io.github.airouter.gateway.select.WeightedCandidates} —— 「给定一个值，它落在谁身上」。
 *       随机抽签的取值、轮询的光标、{@code ip-hash} 的哈希值都走这一条
 *   <li>{@link io.github.airouter.gateway.select.WeightedRotation} —— 「一次次往前走，下一个是谁」。
 *       有效权重是分数的时候（延迟反比就是）它才成立，理由写在那个类上
 * </ul>
 */
package io.github.airouter.gateway.select;
