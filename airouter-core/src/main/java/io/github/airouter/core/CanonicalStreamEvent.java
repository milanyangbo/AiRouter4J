package io.github.airouter.core;

import java.util.Map;

/**
 * 一条流上传出的事件（ADR-0005 第 1 节）。
 *
 * <p>它是 sealed：**形态封闭是刻意的**。收到一个没有对应分支的事件时，编译器就说话，
 * 而不是让它在某个 {@code default} 里被静默丢掉 —— 后者在一次流式调用上的表现是
 * 「这条流没有终态」，而那不会报错，只会一直悬着。
 *
 * <p>六型覆盖一条流的全部事情：开场、内容增量、用量、保活、正常结束、异常结束。
 * **没有 {@code ToolCallDelta}**：工具调用的增量形态取决于上游真实怎么发，
 * 现阶段没有能稳定复现它的端点，凭想象加一型等于承诺一件没被验证过的事。
 *
 * <p>{@link Terminal} 与 {@link Failed} 是终态的**唯一**形态，由 Provider 显式发出。
 * 终态是这条流的最后一个事件 —— 不允许靠 {@code onComplete} / {@code onError} 去推断，
 * 那正是来源项目「流式恒为成功」的成因。
 */
public sealed interface CanonicalStreamEvent {

  /**
   * 开场：上游给出了这次生成自己的标识与真正处理它的模型。
   *
   * <p>它是流上第一件事，让调用方在收到第一个字之前就能把这次生成与一个 id 对上。
   *
   * @param id 上游给出的响应标识，上游没给时为空串
   * @param model 实际处理请求的模型名，可能与请求里指定的不同
   * @param passthrough 这一帧里未被建模的上游字段，值是 JSON 原文。
   *                    厂商私有字段与 {@code created} 这类「每一帧都要带、但网关不理解」的键走这条路
   *                    回到客户端 —— 丢掉它们的代价不发生在网关身上，而发生在调用方身上
   */
  record Opening(String id, String model, Map<String, String> passthrough) implements CanonicalStreamEvent {

    /** 缺字段在上游一侧是常态，这里统一成空映射。 */
    public Opening {
      passthrough = passthrough == null ? Map.of() : Map.copyOf(passthrough);
    }
  }

  /**
   * 内容增量。
   *
   * <p>它是流上唯一携带正文的事件。拼接顺序即字节到达顺序 ——
   * 网关不重排、不去重、不合并，因为「上游怎么把它切成块」不是网关该替调用方决定的事。
   *
   * @param text 这一次增量带来的正文
   */
  record TextDelta(String text) implements CanonicalStreamEvent {
  }

  /**
   * 用量。上游把用量单独发一块时才出现（OpenAI 兼容端点在
   * {@code stream_options.include_usage} 打开时会这样做）。
   *
   * <p>它与 {@link Terminal} 分开存在，是因为两者在真实的流上并不总是一起来：
   * 有时用量先到，有时终态先到。把它们合成一个事件等于替上游决定顺序。
   *
   * @param usage 上游给出的用量
   */
  record Usage(CanonicalUsage usage) implements CanonicalStreamEvent {
  }

  /**
   * 保活：上游在两块之间发的注释（SSE 的 {@code :} 行）。
   *
   * <p>它**不是内容也不影响终态判断**，但必须能被认出来 ——
   * 认不出来时它会被当成正文（响应里出现脏字符）或被当成结束（流提前收尾）。
   *
   * @param comment 注释原文
   */
  record Ping(String comment) implements CanonicalStreamEvent {
  }

  /**
   * 正常结束：上游把话说完了。
   *
   * <p>{@code reason} 允许为 null —— 有的上游不发停止原因。
   * 那与「发了一个空串」是不同的两件事，因此这里不替它补 {@link CanonicalFinishReason#STOP}。
   *
   * @param reason 停止原因，上游没给时为 null
   */
  record Terminal(CanonicalFinishReason reason) implements CanonicalStreamEvent {
  }

  /**
   * 异常结束：上游没能把话说完，或者它明确地拒绝了。
   *
   * <p>它是**终态**而不是「流上的一个错误」：失败之后这条流就结束了，
   * 后面不会再有事件，也不允许再补一个成功。
   *
   * @param error 错误形态，必不为空 —— 「失败了但没说为什么」不被允许
   */
  record Failed(GatewayError error) implements CanonicalStreamEvent {
  }

  /**
   * 这是不是终态。
   *
   * <p>放在这里而不是让每个消费方各自 {@code instanceof} 两次：
   * 「终态有哪几种」是契约的一部分，写在一处，加一型时只有一处要改。
   *
   * @param event 待判定的事件，允许为 null
   * @return 是 {@link Terminal} 或 {@link Failed} 时返回 true
   */
  static boolean isTerminal(CanonicalStreamEvent event) {
    return event instanceof Terminal || event instanceof Failed;
  }
}
