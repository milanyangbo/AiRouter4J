package io.github.airouter.gateway.governance;

import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeObserver;
import java.time.Instant;
import java.util.Objects;

/**
 * 把结算结果投递给对应实例的熔断器。
 *
 * <p>它是治理侧唯一的 {@link OutcomeObserver} 消费者，也是「熔断器只从结算点取输入」这件事
 * 的落点：这里没有状态码，没有异常类型判断，只有 {@link Outcome#kind()}。
 *
 * <p>它不判断该不该放行 —— 那是 {@link CircuitBreaker#allowsCall} 的事。
 * 观测与决定在这里是分开的两次动作，中间隔着一个「下一次请求」。
 */
public final class CircuitBreakerObserver implements OutcomeObserver {

  private final CircuitBreakers breakers;

  public CircuitBreakerObserver(CircuitBreakers breakers) {
    this.breakers = Objects.requireNonNull(breakers, "breakers");
  }

  @Override
  public void onTerminal(Outcome outcome) {
    // 投递发生在收到终态之后，不在热路径上。
    breakers.forInstance(outcome.instanceId()).record(outcome, Instant.now());
  }
}
