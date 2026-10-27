/**
  Spring AI 层：ChatClient 装配、Advisor 链与 Subagent（ADR-0007）。

  <p>知道：core 的端口。不该知道：gateway 的任何类 —— Agent 要走网关的话走 HTTP，
  不做模块内直连。

  <p>阶段 0 这里是占位：Spring AI 的 starter 与编排逻辑属阶段 14。
 */
package io.github.airouter.agent;
