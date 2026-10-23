package io.github.airouter.archtest.layout.violations.transport;

import io.github.airouter.archtest.layout.violations.routing.InstanceSelector;

/** 违规样例（R5）：出站管道自己去做实例选择，业务知识渗进了搬字节的那一层。 */
public final class HttpOutbound {

  public boolean isReachable() {
    return true;
  }

  public String send() {
    return new InstanceSelector().pick();
  }
}
