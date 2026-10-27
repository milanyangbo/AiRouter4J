package io.github.airouter.archtest.layout.ordinary.routing;

import io.github.airouter.archtest.layout.ordinary.core.Canonical;

/** 普通写法：实例选择只操作 Canonical，不做 IO。 */
public final class InstanceSelector {

  public String pick(Canonical request) {
    return "instance-for-" + request.content();
  }
}
