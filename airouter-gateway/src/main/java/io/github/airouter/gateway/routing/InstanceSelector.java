package io.github.airouter.gateway.routing;

/**
 * 第 ⑥ 步：一个**纯函数**的策略（ADR-0011 第 2 节 (a)）。
 *
 * <p>它只看两样东西：第 ④ 步加权之后的候选，以及这一次调用的上下文。
 * 它不许看健康状态（那是第 ③ 步的事）、不许做 IO、不许自己造随机数之外的不确定性。
 * 守这条边界的收益直接体现在测试上：判「策略对不对」不需要起容器、不需要端点、
 * 也不需要真的发出一次请求 —— 给定候选与固定随机源，结果可复现。
 *
 * <p>六个实现：`random` / `round-robin` / `least-connections` / `ip-hash` /
 * `consistent-hash` / `latency`。前两个随本张票落地，其余四个随后。
 * 新增一个 = 加一个实现类并在工厂登记，不动注册表、不动路由、不动 Face。
 */
public interface InstanceSelector {

  /**
   * 挑一个。
   *
   * @param candidates 第 ④ 步的产物，至少一个候选
   * @param context 这一次调用的上下文：调用方标识与标签要求在这里
   * @return 选中的那一个候选
   */
  Candidate select(WeightedCandidates candidates, SelectionContext context);
}
