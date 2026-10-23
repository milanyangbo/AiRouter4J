package io.github.airouter.archtest.layout.violations.core;

import io.github.airouter.archtest.layout.violations.app.StartupRunner;

/** 违规样例（R2）：离框架最远的模块反向依赖了装配终点。 */
public final class CanonicalModel {

  public StartupRunner runner() {
    return new StartupRunner();
  }
}
