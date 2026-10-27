package io.github.airouter.gateway.observe;

import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeObserver;
import java.util.ArrayList;
import java.util.List;

/**
 * 结算的**账本**：每一次结算按到达顺序留一条，能被读到。
 *
 * <p>它是本阶段「结算记录」的落地形态，也是 {@link OutcomeObserver} 的第一个消费者。
 * 熔断、配额、调用历史随后接的是**同一个接口**，不是这个类 ——
 * 它们接进来时这个类仍在（它是诊断用的账本），但不再是唯一的消费者。
 *
 * <p>留多少条是有上限的：它是诊断用的窗口，不是存储。
 * 一个无上限的账本会把「记下来看看」变成一次缓慢的内存泄漏，
 * 而这种泄漏只在跑了很久之后才有人注意到。
 *
 * <p>实现是**非阻塞**的（{@code OutcomeObserver} 的要求）：结算发生在流式路径的关键帧上，
 * 在这里做 IO 会把一次模型调用的延迟压到每一个调用方头上。
 */
public final class SettlementLog implements OutcomeObserver {

  /** 账本窗口。够回答「刚才那几条是什么」，不够当存储用 —— 后者是持久化那一层的事。 */
  private static final int CAPACITY = 256;

  private final List<Outcome> settled = new ArrayList<>();

  @Override
  public synchronized void settle(Outcome outcome) {
    settled.add(outcome);
    if (settled.size() > CAPACITY) {
      settled.subList(0, settled.size() - CAPACITY).clear();
    }
  }

  /**
   * 某一次请求的结算。
   *
   * <p>按 {@code requestId} 取而不是「取最后一条」：一次调用与它的结算之间隔着整条流，
   * 而测试与诊断都从「这一条请求」出发。取最后一条在并发下会拿到别人的结果。
   *
   * @param requestId 请求标识
   * @return 这一条请求的结算，可能有多个（按到达顺序）：正常情形只有一个
   */
  public synchronized List<Outcome> ofRequest(String requestId) {
    return settled.stream().filter(outcome -> outcome.requestId().equals(requestId)).toList();
  }

  /** 账本里现在有什么。诊断与测试用它，生产路径不该依赖它。 */
  public synchronized List<Outcome> all() {
    return List.copyOf(settled);
  }

  /** 清空。只给测试与「重新计数」的场合用。 */
  public synchronized void clear() {
    settled.clear();
  }
}
