package io.github.airouter.archtest.wiring.violations;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** 违规样例（R6）：用 @Lazy 把循环依赖从启动期推迟到首次调用。 */
@Service
@Lazy
public class LazyWiredService {

  public String describe() {
    return new Collaborator().name();
  }
}
