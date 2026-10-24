package io.github.airouter.app;

import org.springframework.boot.SpringApplication;

/**
 * 进程入口。
 *
 * <p>与 {@link AiRouterApplication} 分开的理由：入口是静态的，装配声明不是。
 * 混在一个类里会让配置类同时承担两种职责，也会让它变成事实上的工具类。
 */
public final class AiRouterBootstrap {

  private AiRouterBootstrap() {
  }

  public static void main(String[] args) {
    SpringApplication.run(AiRouterApplication.class, args);
  }
}
