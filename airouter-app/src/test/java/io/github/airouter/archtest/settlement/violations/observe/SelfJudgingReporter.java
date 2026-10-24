package io.github.airouter.archtest.settlement.violations.observe;

import io.github.airouter.core.CanonicalUsage;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import java.time.Duration;

/**
 * 违规样例（R9）：一个观测组件自己造了一个 {@code Outcome}。
 *
 * <p>{@code Outcome} 是「一次调用的结果」这一事实的载体。谁能造它，谁就有了自己的成败口径。
 * 这个样例里的判断甚至可能是「对的」 —— 问题从来不在于对不对，而在于从此有两个地方
 * 都在说这件事，而它们不会长期一致。
 */
public final class SelfJudgingReporter {

  public Outcome judge(String requestId, boolean ok) {
    return new Outcome(requestId, "chat", "instance-1",
        ok ? OutcomeKind.SUCCESS : OutcomeKind.UPSTREAM_FAILURE,
        Duration.ZERO, (CanonicalUsage) null,
        ok ? null : new GatewayError("upstream_5xx", "自己看出来的", 502));
  }
}
