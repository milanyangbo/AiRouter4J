package io.github.airouter.archtest.wiring.violations;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 违规样例（R7）：setter 注入 —— 与字段注入同一个问题，只是晚一步。 */
@Service
public class SetterInjectedService {

  private Collaborator collaborator;

  @Autowired
  public void setCollaborator(Collaborator collaborator) {
    this.collaborator = collaborator;
  }

  public String describe() {
    return collaborator.name();
  }
}
