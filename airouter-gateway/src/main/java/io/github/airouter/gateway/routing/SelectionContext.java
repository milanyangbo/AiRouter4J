package io.github.airouter.gateway.routing;

import java.util.Set;

/**
 * 一次选择的**上下文**：链要用、但不属于候选本身的那些输入（ADR-0011 第 2 节 (e)）。
 *
 * <p>两个策略需要外部输入：`least-connections` 要活跃连接数，`ip-hash` / `consistent-hash`
 * 要调用方标识。**它们是参数，不是让策略去做 IO** —— 一个数由传输层在途计数给出，
 * 一个标识由第 ① 步的认证结果给出，两者都在请求开始时就已经存在。
 * 让策略自己去问「现在有几个连接」，它就要认识传输层，而那样它再也纯不起来。
 *
 * <p>本阶段只有前两项：认证（阶段 6）还没进来，调用方标识一律是匿名；
 * 规则引擎（阶段 8）还没进来，标签要求一律是空集。它们先在这里占位 ——
 * 「没有来源时是什么」与「有来源时怎么填」分开定义，那一天不用回头改策略。
 *
 * @param clientId 调用方标识。第 ① 步的产出；空串表示匿名（没有认证来源时的取值，
 *                 而不是 null —— null 会让每一个消费它的策略各写一遍判空）
 * @param requiredTags 第 ② 步要的标签。空集表示不筛；不为空则**只**保留带齐这些标签的候选
 */
public record SelectionContext(String clientId, Set<String> requiredTags) {

  /** 匿名：没有认证来源时的上下文。 */
  public static final SelectionContext ANONYMOUS = new SelectionContext("", Set.of());

  /** 两个字段都在这里归一（null → 空值），消费它们的策略于是不必各判一次。 */
  public SelectionContext {
    clientId = clientId == null ? "" : clientId;
    requiredTags = requiredTags == null ? Set.of() : Set.copyOf(requiredTags);
  }
}
