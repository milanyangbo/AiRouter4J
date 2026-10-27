/**
  配置绑定与校验。

  <p>知道：配置文件的形状与环境变量的取值规则。不该知道：绑定之后的业务语义 ——
  把配置变成运行时对象是 {@code registry} 的事（ADR-0003 第 2 节）。

  <p>阶段 0 是占位包（只有 package-info）：它的存在是为了让规则有位可守，
  不算「规则已覆盖」（口径见 RulesCoverNonEmptyPackagesTest）。
 */
package io.github.airouter.gateway.config;
