/**
  启动装配与该模块的跨模块约束。

  <p>app 是装配终点：它的测试既断言「容器能起来」，也承载全部跨模块结构约束 ——
  因为它是唯一在 classpath 上同时看得到 core、gateway、agent 的模块（ADR-0003 第 2 节）。
 */
package io.github.airouter.app;
