package io.github.airouter.archtest.wiring.ordinary;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 普通写法：@Bean 方法与条件装配都不属于被禁的三种压环手法。 */
@Configuration
public class OrdinaryConfiguration {

  @Bean
  @ConditionalOnProperty(name = "airouter.ordinary", havingValue = "true", matchIfMissing = true)
  public Collaborator collaborator() {
    return new Collaborator();
  }
}
