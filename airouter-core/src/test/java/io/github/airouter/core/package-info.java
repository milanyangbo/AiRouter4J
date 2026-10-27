/**
  core 的测试。

  <p>这里只能用 JUnit 与 AssertJ：任何 Spring / Jackson / Reactor 的测试依赖都会让 R1 判红，
  而 R1 查的是**依赖树**，不限作用域。测试不能用框架，正是这条约束想要的结果 ——
  领域模型本来就不需要容器。
 */
package io.github.airouter.core;
