package io.github.airouter.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动点。
 *
 * <p>扫描范围写成显式列表而不是包根：组件扫描会扫到测试 classpath 上的类，
 * 而测试里有一批故意违规的样例（见 {@code io.github.airouter.archtest}），
 * 它们不该被当成 bean 装配进来。
 */
@SpringBootApplication(scanBasePackages = {
    "io.github.airouter.app",
    "io.github.airouter.agent",
    "io.github.airouter.gateway"
})
public class AiRouterApplication {

  public static void main(String[] args) {
    SpringApplication.run(AiRouterApplication.class, args);
  }
}
