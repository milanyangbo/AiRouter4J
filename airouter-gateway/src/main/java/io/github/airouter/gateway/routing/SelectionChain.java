package io.github.airouter.gateway.routing;

import java.util.List;
import java.util.Objects;

/**
 * **六步选择链**（ADR-0011）：一次请求打给同一个 Service 下的哪一个实例。
 *
 * <p>六步写在**一处**，而不是散在调用它的地方：
 * 「请求是怎么被挑中一个实例的」是一个有顺序的答案，
 * 散开之后每一处都只记得自己那一步，于是「为什么挑了它」变成一个没人答得上来的问题 ——
 * 而故障现场问的恰恰是这一句。
 *
 * <ol>
 *   <li>**① 调用方标识**：由 {@link SelectionContext} 带进来，本阶段一律匿名
 *   <li>**② 目标与标签**：按 {@link SelectionContext#requiredTags()} 筛；
 *       一个都不剩就拒绝，**不退化为不筛**（ADR-0012：目标服务下无带标签实例 → 拒绝而非忽略标签）
 *   <li>**③ 可用性闸门**：{@link AvailabilityGate}，本阶段恒开
 *   <li>**④ 加权展开**：{@link WeightedCandidates#of(List)}
 *   <li>**⑤ 粘滞**：下一张票，包在策略之外（ADR-0011 第 2 节 (c)）
 *   <li>**⑥ 策略**：{@link InstanceSelector}
 * </ol>
 *
 * <p>每一步的**顺序是它的一部分**：加权必须在策略之前（策略因此不必各写一遍加权），
 * 粘滞必须在策略之外（它于是能与任意策略组合）。
 * 顺序写在这个类的方法体里而不是分成六个类，是因为分了之后「谁先谁后」要靠调用方記得 ——
 * 而顺序这件事一旦可以被调用方摆布，它就迟早会被摆错一次。
 */
public final class SelectionChain {

  private final AvailabilityGate gate;

  private final InstanceSelector selector;

  /**
   * @param gate 第 ③ 步的闸门
   * @param selector 第 ⑥ 步的策略
   */
  public SelectionChain(AvailabilityGate gate, InstanceSelector selector) {
    this.gate = Objects.requireNonNull(gate, "可用性闸门");
    this.selector = Objects.requireNonNull(selector, "选择策略");
  }

  /**
   * 走完六步，给出一个实例。
   *
   * @param serviceId 目标服务名。它只用于出错信息 —— 选择本身不需要它
   * @param candidates 这个服务下**启用着的**实例；「启用」的判定在调用方（配置那一层知道它）
   * @param context 这一次调用的上下文
   * @return 选中的那一个候选
   * @throws NoUsableInstanceException 第 ② 或第 ③ 步之后一个候选都不剩时抛出
   */
  public Candidate select(String serviceId, List<Candidate> candidates,
      SelectionContext context) {
    Objects.requireNonNull(context, "选择上下文");
    List<Candidate> pool = candidates == null ? List.of() : candidates;

    // ② 目标与标签：空集表示不筛；不为空则只留带齐的，一个不剩就拒绝。
    List<Candidate> tagged = context.requiredTags().isEmpty()
        ? pool
        : pool.stream().filter(candidate -> candidate.carriesAll(context.requiredTags())).toList();
    if (tagged.isEmpty()) {
      throw new NoUsableInstanceException(serviceId, pool.isEmpty()
          ? "它声明了实例但没有一个是启用着的"
          : "没有任何实例同时带有标签 " + String.join("、", context.requiredTags()));
    }

    // ③ 可用性闸门：熔断与限流在这一步筛掉候选。
    List<Candidate> usable = tagged.stream().filter(gate::admits).toList();
    if (usable.isEmpty()) {
      throw new NoUsableInstanceException(serviceId,
          "全部候选都被可用性闸门排除（熔断或限流）：把实例加回去或者等它恢复");
    }

    // ④ 加权展开。 ⑤ 粘滞在下一张票里插在这里。 ⑥ 策略。
    return selector.select(WeightedCandidates.of(usable), context);
  }
}
