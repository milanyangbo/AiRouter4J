package io.github.airouter.archtest.wiring.ordinary;

import org.springframework.stereotype.Component;

/** 普通写法：一个普通的 bean。 */
@Component
public class Collaborator {

  public String name() {
    return "collaborator";
  }
}
