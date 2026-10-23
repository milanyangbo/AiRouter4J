package io.github.airouter.archtest.wiring.violations;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 违规样例（R7）：字段注入 —— 对象可以在半成品状态下被发布。 */
@Service
public class FieldInjectedService {

  @Autowired
  private Collaborator collaborator;

  public String describe() {
    return collaborator.name();
  }
}
