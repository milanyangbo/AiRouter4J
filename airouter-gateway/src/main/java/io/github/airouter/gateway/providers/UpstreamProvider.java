package io.github.airouter.gateway.providers;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.StreamingCompletionPort;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;

/**
 * 一个实例的**出站能力**：非流式与流式两个端口。
 *
 * <p>它自己就同时是这两个端口 —— 委派给内部那两个对象，而不是让 {@code faces} 去分别持有。
 * 这一点在 Spring 里不是风格问题：容器按**运行期类型**匹配注入候选，
 * 而这两个端口当前由同一个对象实现。若把它拆成两个 bean（各声明一个接口），
 * 注入任何一侧都会同时匹配上两个候选，装配当场变成二选一（票 02 踩过一次）。
 * 一个 bean 同时担两个接口，注入点看到的就仍然只有接口。
 *
 * <p>现阶段两个端口装着同一个对象（{@code OpenAiCompatibleProvider} 同时实现两个端口），
 * 但它们不必永远如此：形态不同可以只支持其中一个，届时这里装的是两份不同的实现，
 * 而「能不能流式」也就不必变成一个可为 null 的可选项。
 *
 * @param completion 非流式端口
 * @param streaming 流式端口
 */
public record UpstreamProvider(CompletionPort completion, StreamingCompletionPort streaming)
    implements CompletionPort, StreamingCompletionPort {

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    return completion.complete(request);
  }

  @Override
  public Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    return streaming.stream(request);
  }
}
