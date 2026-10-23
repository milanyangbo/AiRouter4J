package io.github.airouter.archtest.wiring.ordinary;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 普通写法：构造函数注入 + 配置字段。这类写法必须保持绿的。 */
@Service
public class ConstructorInjectedService {

  private final Collaborator collaborator;

  @Value("${airouter.instance-timeout:30s}")
  private String timeout;

  public ConstructorInjectedService(Collaborator collaborator) {
    this.collaborator = collaborator;
  }

  public String describe() {
    return collaborator.name() + "/" + timeout;
  }
}
