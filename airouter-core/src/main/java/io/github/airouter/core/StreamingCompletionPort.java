package io.github.airouter.core;

import java.util.concurrent.Flow;

/**
 * Face 与 Provider 之间的流式端口。
 *
 * <p>与 {@link CompletionPort} 是一对：同一件事的两个形态，**同一个 Provider 实现两个端口**。
 * 分成两个接口而不是一个带 {@code stream} 开关的方法，是因为「一条流恰好结算一次」
 * 那类纪律只存在于流式这一侧，合并会让非流式那一侧也得假装自己有终态。
 *
 * <p>返回 JDK 的 {@link Flow.Publisher} 而不是响应式类型：理由与 {@link CompletionPort} 相同 ——
 * Reactor 进 core 的依赖树会被 enforcer 判红。两侧各用一次 {@code JdkFlowAdapter} 接上。
 */
public interface StreamingCompletionPort {

  /**
   * 开始一次流式调用。
   *
   * <p>返回的流**必须**以 {@link CanonicalStreamEvent.Terminal} 或
   * {@link CanonicalStreamEvent.Failed} 收尾，且终态之后不再有事件。
   * 这是 ADR-0005 第 1 节：终态由 Provider 显式发出，不允许靠流自己结束去推断。
   *
   * @param request Canonical 请求
   * @return 事件流
   */
  Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request);
}
