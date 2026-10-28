/**
  延迟样本：从结算点学，而不是自己去看延迟。

  <p>它只消费 {@code Outcome}。这一份测试因此不需要容器、端点与网络 ——
  「给定一串结算，表里是什么」是一件纯内存里就能回答的事。
*/
package io.github.airouter.gateway.observe;
