package io.github.airouter.gateway.transport;

/**
 * 上游字节流上的一帧：**一种读法**切出来的一份载荷。
 *
 * <p>它只回答「线上那一段字节说的是什么」，**不解释内容**：
 * {@code data: [DONE]} 是不是结束哨兵、一段 JSON 里有没有 {@code finish_reason}、
 * 一行 JSON 里的 {@code done:true} 算不算说完 —— 都是形态自己的事，由 codec 判断
 * （ADR-0006 第 2 节：形态差异只留在 codec 的「一帧字节 → 哪些事件」）。
 * 把哨兵认在这里，等于让传输层替某一家的协议做决定。
 *
 * <p>类型名不写死某一种读法：SSE 与逐行 JSON 是并列的两种读法（ADR-0006 第 3 节），
 * 而它们切出来的东西是同一种 —— 一份待解释的载荷。
 *
 * <p>因此它只有两型：有载荷的帧与注释帧。**注释帧是 SSE 独有的**：
 * 逐行 JSON 那种读法切不出注释行，它也就不产这一型。
 */
public sealed interface UpstreamFrame {

  /**
   * 一份载荷。SSE 里多行 {@code data:} 按规则以 {@code \n} 相接；
   * 逐行 JSON 里它就是整整一行。
   *
   * @param payload 载荷原文，可能是 {@code [DONE]}，也可能是空串（空 {@code data:} 帧）
   */
  record Data(String payload) implements UpstreamFrame {
  }

  /**
   * 注释：SSE 里以 {@code :} 开头的行。上游用它做保活，不携带内容。
   *
   * @param text 注释原文
   */
  record Comment(String text) implements UpstreamFrame {
  }
}
