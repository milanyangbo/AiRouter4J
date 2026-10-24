/**
 * 领域模型与端口。
 *
 * <p>这里的类型不依赖任何框架，一条框架依赖进入它的依赖树就是构想失败 ——
 * 那条约束由 airouter-core 自己的 enforcer 规则判红，写在正文注释里是不会有人替你执行的。
 *
 * <p>Canonical 是 Face 与 Provider 之间唯一的公共知识：Face 把它译进来，
 * Provider 把它译出去，两侧互不认识（ADR-0005）。
 */
package io.github.airouter.core;
