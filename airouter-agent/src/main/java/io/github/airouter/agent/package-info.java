/**
 * Spring AI 层（ADR-0004）。
 *
 * <p>它不 Java 依赖 gateway：Agent 要走网关的话走 HTTP，不做模块内直连。
 * 与 core 的关系是单向的 —— 这个模块认识 Canonical，Canonical 不认识它。
 */
package io.github.airouter.agent;
