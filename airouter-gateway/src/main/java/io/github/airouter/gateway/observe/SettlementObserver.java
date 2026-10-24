package io.github.airouter.gateway.observe;

import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeObserver;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 结算点（ADR-0006 第 2 节）在本阶段的形态：留一份样本，并把结论投递给登记在它上面的消费者。
 *
 * <p>它是**唯一的 {@code OutcomeObserver} bean**，这一点是装配上的硬约束而不是风格偏好：
 * 让熔断器自己成为一个 bean，「注入一个 {@code OutcomeObserver}」就同时匹配两个候选，
 * 装配歧义只能靠 {@code @Primary} 之类的注解压下去 ——
 * 而那些注解的含义是「有多个候选时选我」，与「结算点只有一个」正好相反。
 * 因此第二个消费者出现时是**登记在这里**，不是另起一个 bean。
 *
 * <p>容量是有上限的：一个无界的记录器在生产上就是一次缓慢的内存泄漏，
 * 而它记录的东西只在出事的那一刻有价值，出事之后没人会去翻三天前的样本。
 */
public final class SettlementObserver implements OutcomeObserver {

  /** 保留的样本数。够看最近一阵子的形态，又不会让内存跟着流量涨。 */
  public static final int CAPACITY = 128;

  private static final System.Logger LOG = System.getLogger(SettlementObserver.class.getName());

  private final ConcurrentLinkedQueue<Outcome> recent = new ConcurrentLinkedQueue<>();

  private final List<OutcomeObserver> consumers;

  public SettlementObserver() {
    this(List.of());
  }

  /**
   * @param consumers 结算的其它消费者，按顺序投递
   */
  public SettlementObserver(List<OutcomeObserver> consumers) {
    this.consumers = List.copyOf(consumers);
  }

  /**
   * 结算一次。
   *
   * <p>投递发生在收到终态之后，不在热路径上。
   *
   * <p>消费者逐个投递且**互相隔离**：一个消费者抛异常不能让后面的消费者失去这次结果。
   * 这不是防御性编程 —— 熔断器的输入一旦因为限流回放写日志失败而丢一批，
   * 表现是「熔断器迟迟不开」，而那种现象在排查时会被归因为上游不够坏。
   */
  @Override
  public void onTerminal(Outcome outcome) {
    record(outcome);
    for (OutcomeObserver consumer : consumers) {
      try {
        consumer.onTerminal(outcome);
      } catch (RuntimeException failure) {
        LOG.log(System.Logger.Level.ERROR,
            () -> "结算的消费者抛了异常，其余消费者不受影响：[" + outcome.requestId() + "] "
                + consumer.getClass().getName(),
            failure);
      }
    }
  }

  /** 最近若干次结算的结果，按发生顺序。 */
  public List<Outcome> recent() {
    return List.copyOf(recent);
  }

  /** 最近一次结算，一次都没结算过时为空。 */
  public Optional<Outcome> last() {
    List<Outcome> outcomes = recent();
    return outcomes.isEmpty() ? Optional.empty() : Optional.of(outcomes.get(outcomes.size() - 1));
  }

  /**
   * 某一次请求的全部结算结果。
   *
   * <p>「一条流恰好结算一次」这条断言只有在能按请求把结果挑出来的时候才成立：
   * 看总数会被别的用例干扰，看最近一次则发现不了多出来的那一条。
   *
   * @param requestId 请求标识，与响应头 {@code X-Airouter-Request-Id} 里的那个一致
   * @return 这一次请求产生的结算，按发生顺序；一次都没有时是空列表
   */
  public List<Outcome> forRequest(String requestId) {
    return recent().stream().filter(outcome -> outcome.requestId().equals(requestId)).toList();
  }

  /** 留一份样本。容量之外的先到先出：留不住全部，但永远留得住最近的一批。 */
  private void record(Outcome outcome) {
    recent.add(outcome);
    while (recent.size() > CAPACITY) {
      recent.poll();
    }
    LOG.log(System.Logger.Level.DEBUG,
        () -> "[" + outcome.requestId() + "] " + outcome.kind()
            + " latency=" + outcome.latency().toMillis() + "ms");
  }
}
