package io.github.airouter.archtest.layout.ordinary.governance;

import io.github.airouter.archtest.layout.ordinary.core.Canonical;
import io.github.airouter.archtest.layout.ordinary.routing.InstanceSelector;

/** 普通写法：治理只依赖 core 与同层的 routing，不碰协议与 IO。 */
public final class QuotaCheck {

  private final InstanceSelector selector;

  public QuotaCheck(InstanceSelector selector) {
    this.selector = selector;
  }

  public boolean allow(Canonical request) {
    return !selector.pick(request).isEmpty();
  }
}
