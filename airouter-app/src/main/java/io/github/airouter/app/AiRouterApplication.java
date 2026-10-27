package io.github.airouter.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 装配声明。
 *
 * <p>这里只有注解，没有代码：它是一个配置类，会被代理，因此不该出现静态方法 ——
 * 进程入口在 {@link AiRouterBootstrap}，两边分开之后，也没人会顺手往配置类里塞工具方法。
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
}
