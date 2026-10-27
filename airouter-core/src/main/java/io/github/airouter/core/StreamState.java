package io.github.airouter.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条流「结算过了没有」的状态（ADR-0005 第 2 节）。
 *
 * <p>一条流**恰好结算一次**由 {@link #claim()} 保证：它是一次 CAS，
 * 第二次调用返回 false。写成「先查有没有结算过、再结算」两步的话，
 * 终态与取消同时发生的那一瞬间会结算两次 —— 而重复结算的后果是重复扣配额、重复写调用历史，
 * 且两处都看不出自己是第二次。
 *
 * <p>它按流创建，不做成单例：两条并发的流各自有自己的一次机会。
 */
public final class StreamState {

  private final AtomicBoolean settled = new AtomicBoolean();

  /**
   * 抢一次结算的机会。
   *
   * @return 抢到了返回 true；已经有人结算过返回 false —— 后者意味着**这次不要结算**
   */
  public boolean claim() {
    return settled.compareAndSet(false, true);
  }

  /** 这条流是不是已经结算过。用于断言与诊断，不用于决定是否结算（那只能用 {@link #claim()}）。 */
  public boolean isSettled() {
    return settled.get();
  }
}
