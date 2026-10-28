package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;

/**
 * 第 ⑥ 步：从已经加权展开的候选里挑一个。
 *
 * <p>实现必须是**纯函数**：只看参数，不看健康、不做 IO、不读时钟。
 * 随机源与轮询光标由构造器注入，活跃连接数与延迟样本由调用方作为参数递进来 ——
 * 于是一条策略的对错可以在没有容器、没有端点的情况下被断言（ADR-0011 第 2 节 (a)）。
 *
 * <p>本包与 {@code routing} 互相认识是有意的：链编排 ①②③ 时调用这里，
 * 而这里要读 ① 的产物（{@link SelectionContext}）。两者都不碰协议、IO 与状态码 ——
 * 那才是 R4 要守的那条线。
 */
@FunctionalInterface
public interface InstanceSelector {

  /**
   * @param candidates 第 ④ 步交出来的加权候选
   * @param context 第 ① ② 步的产物：调用方标识与要求的标签
   * @return 选中的实例 id
   */
  String select(WeightedCandidates candidates, SelectionContext context);
}
