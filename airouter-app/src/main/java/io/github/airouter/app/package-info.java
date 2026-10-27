/**
  启动装配，以及跨模块结构规则的唯一住所。

  <p>知道：全部四个模块 —— 它是唯一在 classpath 上看得到它们的地方，
  因此「app → gateway → core」这类方向只能在这里被判红（ADR-0003 第 4 节）。
  不该知道的东西恰恰是它的角色本身：没有模块可以依赖它（R2 判红）。

  <p>阶段 0 这里是占位：{@code @SpringBootApplication} 与可执行 jar 随阶段 2 进入。
 */
package io.github.airouter.app;
