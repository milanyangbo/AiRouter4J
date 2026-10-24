package io.github.airouter.core;

import java.util.concurrent.Flow;

/**
 * Face 与 Provider 之间的流式端口。
 *
 * <p>返回类型是 JDK 的 {@link Flow.Publisher} 而不是响应式类型，理由与
 * {@link CompletionPort} 用 {@link java.util.concurrent.CompletionStage} 相同：
 * 把 Reactor 带到这里等于让框架进入 core 的依赖树，那条边界由 core 自己的 enforcer 判红。
 * JDK 9 起 {@code Flow} 就是标准的异步流契约，两侧各用 {@code FlowAdapters} 一次转换即可接上。
 *
 * <p>契约由实现方保证，消费方可以依赖它：
 * <ul>
 *   <li>一条流**有且只有一个**终态（{@link CanonicalStreamEvent.Terminal} 或
 *       {@link CanonicalStreamEvent.Failed}），且它是最后一个事件；
 *   <li>终态是显式发出的，不由「流如何结束」推断 ——
 *       上游在未发出结束哨兵时就关闭连接时，实现方补发 {@code Failed}；
 *   <li>流不以 error 信号结束：所有的失败都先在事件层变成 {@code Failed}，
 *       这样消费方只需要处理一种结束方式。
 * </ul>
 */
public interface StreamingCompletionPort {

  /**
   * 开始一次流式调用。
   *
   * @param request Canonical 请求，其 {@code stream} 应当为 true
   * @return 流式事件；订阅才开始发出，且以恰好一个终态收尾
   */
  Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request);
}
