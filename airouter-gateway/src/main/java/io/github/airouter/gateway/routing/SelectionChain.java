package io.github.airouter.gateway.routing;

import io.github.airouter.gateway.select.Candidate;
import io.github.airouter.gateway.select.InstanceSelector;
import io.github.airouter.gateway.select.WeightedCandidates;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 六步落在**一处**（ADR-0011 第 5 节）：① 调用方标识 → ② 目标与标签 →
 * ③ 可用性闸门 → ④ 加权展开 → ⑤ 粘滞 → ⑥ 策略。
 *
 * <p>本类编排 ①②③；④⑤⑥ 在 {@code select} 包，由本类在编排中调用。
 * 编排散到若干 handler 里的代价来源项目已经付过一次：流式那条路自己抄了一遍，
 * 于是绕过了治理 —— 那正是它最贵的那个缺陷的成因。
 *
 * <p>链**不做 IO、看不到 HTTP 状态码**（R4），也**不知道实例有没有被关掉**：
 * 那是配置与注册表的事，由装配层在取候选时筛掉（ADR-0010 第 2 节）。
 * 这一句同时也是拒绝措辞的根据：候选为空的含义是「一个启用着的都没有」，
 * 不是「筛掉了所有」—— 后者有它自己的原因。
 *
 * <p>⑤ 本票**直通**：粘滞是包在策略之外的包装器，它来的时候这里不用改 ——
 * 装配层把包装好的那一份交进来即可（票 09）。
 */
public final class SelectionChain {

  private final AvailabilityGate gate;

  private final InstanceSelector selector;

  /**
   * @param gate 第 ③ 步的闸门。本票注入的是恒开的那一份
   * @param selector 第 ⑥ 步的策略；第 ⑤ 步的粘滞包装器包在它外面（票 09）
   */
  public SelectionChain(AvailabilityGate gate, InstanceSelector selector) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.selector = Objects.requireNonNull(selector, "selector");
  }

  /**
   * 跑完六步，给出一个实例 id。
   *
   * @param serviceId 目标 Service 名。它只进拒绝原因 —— 解析服务是装配层的事
   * @param candidates 候选，即配置里启用着的那些，顺序沿用配置里的顺序
   * @param context 调用方标识与要求的标签
   * @return 选中的实例 id
   * @throws NoUsableInstanceException 三种原因之一。**调用方应当拒绝，不要退化** ——
   *     退回任何一个更宽的集合都会让这一条验收失去意义
   */
  public String select(String serviceId, List<Candidate> candidates, SelectionContext context) {
    // ① 调用方标识：只取不校验。它随 context 一起交给 ⑥（粘滞与两个 hash 策略读它），
    //    认证与租户属阶段 6（ADR-0018），接上后来源换成认证结果，链不变。
    Objects.requireNonNull(context, "context");

    // ② 目标与标签：目标是参数里的 serviceId，这里做的是标签筛。
    //    候选为空不是「筛完了」，而是「这个服务一个启用着的都没有」。
    if (candidates.isEmpty()) {
      throw new NoUsableInstanceException(serviceId, Rejection.NO_ENABLED_INSTANCES);
    }
    List<Candidate> matching = filterByTags(candidates, context.requiredTags());
    if (matching.isEmpty()) {
      throw new NoUsableInstanceException(serviceId, Rejection.NO_TAG_MATCH);
    }

    // ③ 可用性闸门：排除，不是降权。
    List<Candidate> usable = matching.stream()
        .filter(candidate -> gate.allows(candidate.id()))
        .toList();
    if (usable.isEmpty()) {
      throw new NoUsableInstanceException(serviceId, Rejection.GATE_EXHAUSTED);
    }

    // ④ 加权展开（独立一步，不在策略里）→ ⑤ 粘滞（本票直通）→ ⑥ 策略。
    return selector.select(WeightedCandidates.of(usable), context);
  }

  /**
   * 带齐**所有**要求标签的候选才留下。
   *
   * <p>要求为空时原样返回，而不是返回空：不筛与「筛完一个不剩」是两种含义，
   * 混在一起会让「标签没生效」与「标签筛完了」分不开。
   */
  private static List<Candidate> filterByTags(List<Candidate> candidates,
      Set<String> requiredTags) {
    if (requiredTags.isEmpty()) {
      return candidates;
    }
    return candidates.stream()
        .filter(candidate -> candidate.tags().containsAll(requiredTags))
        .toList();
  }
}
