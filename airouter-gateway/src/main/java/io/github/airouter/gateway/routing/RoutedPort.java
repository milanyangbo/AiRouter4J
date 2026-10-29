package io.github.airouter.gateway.routing;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.StreamingCompletionPort;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;

/**
 * Face 看到的出网端口：core 那两个端口，加上「这一次的调用方是谁」。
 *
 * <p>为什么不直接用 core 的两个端口：第 ① 步要拿调用方标识，
 * 而 {@link CompletionPort#complete(CanonicalRequest)} 的签名里只有请求 ——
 * 上下文必须有一个合法的去处。剩下的选项只有线程局部量，
 * 而流式路径上线程是会换的：写进去的那一次与读出来的那一次未必在同一个线程上。
 *
 * <p>它**继承** core 的两个端口而不是另起一套：Face 因此仍然只认识 core 定义的形状，
 * 只是多要了一个入口；具体由谁出网仍由 {@code config} 装配决定（R3 / ADR-0004 第 1 节）。
 *
 * <p>实现必须是**同一个对象**同时担两个接口（见 {@code config.RoutedUpstream}）：
 * 容器按运行期类型匹配候选，拆成两个 bean 会让注入任何一侧都匹配到两个候选。
 */
public interface RoutedPort extends CompletionPort, StreamingCompletionPort {

  /**
   * 完成一次调用。
   *
   * @param request Canonical 请求
   * @param context 选择链的输入：调用方标识与要求的标签
   * @return 异步的 Canonical 响应
   * @throws NoUsableInstanceException 六步走完仍选不出实例时抛出；此时**不出网、也不结算**
   */
  CompletionStage<CanonicalResponse> complete(CanonicalRequest request, SelectionContext context);

  /**
   * 开始一次流式调用。
   *
   * <p>**六步链的解析发生在本方法被调用时，不在订阅时**（票 12 的设计决定）：
   * 实现不得把解析推迟进返回的 Publisher —— {@code defer} 的边界在选择之后，
   * 只包住真正会读上游字节的那一段。于是本方法同步抛出的拒绝意味着
   * **响应尚未提交，状态码还定得下来**：Face 在边界上把它收口成错误体
   * （与非流式同一份），而不是 200 + {@code text/event-stream} 之后的断流。
   * 第一帧写出之后的上游失败不受这条契约管辖 —— 那时只能作为帧上的错误载荷。
   *
   * @param request Canonical 请求
   * @param context 选择链的输入：调用方标识与要求的标签
   * @return 事件流
   * @throws NoUsableInstanceException 六步走完仍选不出实例时抛出；此时**不出网、也不结算**
   */
  Publisher<CanonicalStreamEvent> stream(CanonicalRequest request, SelectionContext context);
}
