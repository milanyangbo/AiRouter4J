/**
  配置绑定与校验。

  <p>知道：配置文件的形状与环境变量的取值规则。不该知道：绑定之后的业务语义 ——
  把配置变成运行时对象是 {@code registry} 的事（ADR-0003 第 2 节）。

  <p>阶段 2 的内容是端点绑定与装配：把地址、凭证、超时变成运行时对象，
     并在这里唯一一次把 Face 与 Provider 连起来。
 */
package io.github.airouter.gateway.config;
