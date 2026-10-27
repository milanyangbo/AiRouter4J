package io.github.airouter.gateway.transport;

/**
 * SSE 的一帧：一个空行之前的全部 {@code data:} 行（或注释行）拼成的一份载荷。
 *
 * <p>它只回答「线上那一段字节说的是什么」，**不解释内容**：
 * {@code data: [DONE]} 是不是结束哨兵、一段 JSON 里有没有 {@code finish_reason}，
 * 都是形态自己的事，由 codec 判断（ADR-0006 第 2 节：形态差异只留在 codec 的「一帧字节 → 哪些事件」）。
 * 把哨兵认在这里，等于让传输层替某一家的协议做决定。
 *
 * <p>因此它只有两型：有载荷的帧与注释帧。
 */
public sealed interface SseFrame {

  /**
   * 一份载荷。多行 {@code data:} 按 SSE 的规则以 {@code \n} 相接。
   *
   * @param payload 载荷原文，可能是 {@code [DONE]}，也可能是空串（空 {@code data:} 帧）
   */
  record Data(String payload) implements SseFrame {
  }

  /**
   * 注释：以 {@code :} 开头的行。上游用它做保活，不携带内容。
   *
   * @param text 注释原文
   */
  record Comment(String text) implements SseFrame {
  }
}
