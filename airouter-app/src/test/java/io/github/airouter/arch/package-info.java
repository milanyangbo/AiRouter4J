/**
  跨模块结构约束的三类判断。

  <p>规则定义与理由集中在 {@link io.github.airouter.arch.StructuralRules}，
  这里的三个测试分别回答一件事：真实模块上是否通过（ModuleStructureTest）、
  违规样例上是否真的判红（GuardsActuallyFireTest）、普通写法会不会被误伤
  （OrdinarySpringUsageIsNotFlaggedTest）。少了任何一个，规则都可能在不被发现的情况下失效。
 */
package io.github.airouter.arch;
