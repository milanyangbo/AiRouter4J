package io.github.airouter.gateway.observe;

import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeObserver;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 把结算结果留在内存里的 {@link OutcomeObserver}。
 *
 * <p>它是 ADR-0006 那个「唯一结算点」的第一个消费者，也是目前唯一的一个：
 * 熔断器、限流回放、配额结算都还没接上来，但**结算本身必须先存在** ——
 * 否则各组件会各自去看状态码和异常，那正是来源项目的形态。
 *
 * <p>容量是有上限的：一个无界的记录器在生产上就是一次缓慢的内存泄漏，
 * 而它记录的东西只在出事的那一刻有价值，出事之后没人会去翻三天前的样本。
 */
public final class RecordingOutcomeObserver implements OutcomeObserver {

  /** 保留的样本数。够看最近一阵子的形态，又不会让内存跟着流量涨。 */
  public static final int CAPACITY = 128;

  private static final System.Logger LOG = System.getLogger(RecordingOutcomeObserver.class.getName());

  private final ConcurrentLinkedQueue<Outcome> recent = new ConcurrentLinkedQueue<>();

  /**
   * 结算一次。
   *
   * <p>投递发生在收到终态之后，不在热路径上。这里只做两件很便宜的事：留一份样本、写一行日志。
   */
  @Override
  public void onTerminal(Outcome outcome) {
    recent.add(outcome);
    while (recent.size() > CAPACITY) {
      recent.poll();
    }
    LOG.log(System.Logger.Level.DEBUG,
        () -> "[" + outcome.requestId() + "] " + outcome.kind()
            + " latency=" + outcome.latency().toMillis() + "ms");
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
}
