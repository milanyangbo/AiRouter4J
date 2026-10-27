package io.github.airouter.archtest.layout.violations.governance;

import io.github.airouter.archtest.layout.violations.transport.HttpOutbound;

/** 违规样例（R4）：治理逻辑直接发起了出站调用，从此脱离容器就无法单测。 */
public final class RateLimiter {

  public boolean allow() {
    return new HttpOutbound().isReachable();
  }
}
