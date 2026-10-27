/**
  结构规则用的样例包。

  <p>分 ordinary 与 violations 两支：前者是合规写法，后者是刻意违规的写法。
  两者都不参与运行，只是 ArchUnit 的输入 —— 它们存在的唯一目的是让
  「规则真的会红」与「规则不误伤普通写法」这两件事各有一份断言。

  <p>{@link io.github.airouter.archtest.Outcome} 是样例用的结算结论类型：
  阶段 0 的 core 还没有它，而 R9 要在这时候就证明自己会红。
 */
package io.github.airouter.archtest;
