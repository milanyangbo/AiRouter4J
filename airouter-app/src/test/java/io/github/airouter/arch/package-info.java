/**
  跨模块结构约束的唯一住所。

  <p>放在这里而不是任何一个被约束的模块里，理由见 ADR-0003 第 4 节：
  `gateway` 里的规则无法完整表达 `gateway → core` 的方向，因为它在 `core` 里没有立足点。
  `app` 是唯一允许依赖全部四个模块的模块，也只有它能一次看到全部。

  <p>规则的定义与理由在 {@link io.github.airouter.arch.StructuralRules}，
  「规则真的会红」在 {@link io.github.airouter.arch.GuardsActuallyFireTest}。
 */
package io.github.airouter.arch;
