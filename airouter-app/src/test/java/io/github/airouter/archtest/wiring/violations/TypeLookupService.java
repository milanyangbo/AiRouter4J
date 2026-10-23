package io.github.airouter.archtest.wiring.violations;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.stereotype.Service;

/** 违规样例（R8）：按类型 getBean —— 依赖从类型签名里消失，只能运行到那一行才发现。 */
@Service
public class TypeLookupService {

  private final BeanFactory beanFactory;

  public TypeLookupService(BeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  public Collaborator find() {
    return beanFactory.getBean(Collaborator.class);
  }
}
