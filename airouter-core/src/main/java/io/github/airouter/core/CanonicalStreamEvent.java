package io.github.airouter.core;

/**
 * 一次流式调用的事件中间表示（ADR-0005 第 2 节）。
 *
 * <p>它是 sealed 的：分发方可以用穷尽的模式匹配处理，漏掉一个分支是编译错误而不是线上悬置。
 * 这一点对终态尤其重要 —— 终态漏处理的后果是「一条流永远没有结果」，而没有编译器会提醒。
 *
 * <p>{@link Terminal} 与 {@link Failed} 是 ADR-0006 的载体：终态是事件模型的一等成员，
 * 由 Provider **显式发出**，不允许从响应式流的完成信号里推断。
 *
 * <p>为什么这里没有 ToolCallDelta：工具调用的增量形态取决于上游真实怎么发，
 * 而现在没有能稳定复现它的端点。与其先画一个未经验证的类型，不如等有了可回放的上游再补 ——
 * 那时它是被样本钉住的，不是被想象出来的。
 */
public sealed interface CanonicalStreamEvent {

  /**
   * 流的开篇，携带这一条流的身份。
   *
   * <p>它被单独建模而不是塞进第一个增量里，是因为 id 与 model 属于整条流而不是某一帧：
   * 后续每一帧都要带着它们出境，而「第一个增量帧」这个时机在遇到空内容流时并不存在。
   *
   * @param id 上游给出的流标识，上游没给时为空串
   * @param model 实际处理请求的模型名
   * @param created 上游给出的创建时间（秒），上游没给时为 null。
   *                它跟着 Opening 而不是跟着每一帧：同一条流里它是不变的，
   *                而客户端（含官方 SDK）在每一帧上都读它 —— 少给它会让 SDK 拿到 0
   */
  record Opening(String id, String model, Long created) implements CanonicalStreamEvent {
  }

  /**
   * 一段文本增量。
   *
   * @param index 候选序号，与上游给出的一致
   * @param text 这一帧带来的文本。它只是增量，拼接是消费方的事；
   *             网关不替客户端拼接，因为不同的客户端对「拼接」有不同需求（展示、脱敏、计费）
   */
  record TextDelta(int index, String text) implements CanonicalStreamEvent {
  }

  /**
   * 用量帧。
   *
   * <p>它单独成帧是因为厂商常在流末尾（或按 {@code stream_options} 的约定）单独发一帧纯用量，
   * 那一帧里没有内容增量。把它当成内容帧处理，会得到一个 content 为空的假增量。
   *
   * @param usage 用量，不为 null —— 没有用量的帧不构成这个事件
   */
  record Usage(CanonicalUsage usage) implements CanonicalStreamEvent {
  }

  /**
   * 保活帧。
   *
   * <p>上游定期发送的注释行在语义上不是内容，也**不是终态判断的依据**：
   * 一个只发注释行的上游是活的但没说话，它与「连接已断」是两回事，
   * 后者由空闲超时判定，前者不该触发它。
   *
   * @param comment 注释原文，不含前导的冒号
   */
  record Ping(String comment) implements CanonicalStreamEvent {
  }

  /**
   * 正常终态。
   *
   * <p>它的出现意味着：这条流说完了它要说的话，并且**说明了自己说完了**。
   * 消费方在它之后不会再收到任何内容帧。
   *
   * @param reason 停止的原因，允许为 null（上游没给）
   * @param usage 整条流的用量，上游没给时为 null
   */
  record Terminal(CanonicalFinishReason reason, CanonicalUsage usage) implements CanonicalStreamEvent {
  }

  /**
   * 异常终态。
   *
   * <p>包括上游报错、上游超时、上游在发出终态之前关闭连接 —— 三种的对外形态都是它，
   * 区别在 {@link GatewayError} 的 {@code type} 上。
   *
   * @param error 这一条流为什么没能正常结束
   */
  record Failed(GatewayError error) implements CanonicalStreamEvent {
  }
}
