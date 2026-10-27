package io.github.airouter.core;

import java.util.concurrent.CompletionStage;

/**
 * Face 与 Provider 之间的非流式端口。
 *
 * <p>Face 只认识这个接口，Provider 实现它。正因为如此，
 * 「新增一个 Provider」是一次实现，而不是对 Face 的一次改写（ADR-0004 第 1 节）。
 *
 * <p>返回类型是 JDK 的 {@link CompletionStage} 而不是响应式类型：网关内部确实是异步的，
 * 但把 Reactor 带到这里等于让框架进入 core 的依赖树，那条边界由 core 自己的 enforcer 判红。
 * 用 JDK 类型表达「异步结果」，把选择留到具体实现一侧。
 */
public interface CompletionPort {

  /**
   * 完成一次调用。
   *
   * @param request Canonical 请求
   * @return 异步的 Canonical 响应
   * @throws UpstreamFailure 在组合子内部抛出并以异常完成的形态出现，携带 {@link GatewayError}
   */
  CompletionStage<CanonicalResponse> complete(CanonicalRequest request);
}
