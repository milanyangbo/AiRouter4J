package io.github.airouter.app;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent 模块此刻是壳，但「壳能被装配起来」这件事要被验证，而不是被假定。
 *
 * <p>这里只断言装配链路是通的：app 扫到了 agent 的装配点。编排行为由后续票负责。
 */
@SpringBootTest
@Tag("integration")
class AgentModuleIsAssembledTest {

  @Test
  void agentAssemblyPointIsPresent(@Autowired ApplicationContext context) {
    assertThat(context.containsBean("agentConfiguration"))
        .as("app 的组件扫描没有扫到 agent 模块的装配点")
        .isTrue();
  }
}
