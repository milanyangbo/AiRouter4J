package io.github.airouter.gateway.providers;

import io.github.airouter.gateway.providers.openai.OpenAiCompatibleProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * **形态工厂**：形态 → 构造器的登记处（ADR-0006 第 6 节第 4 项）。
 *
 * <p>它存在的唯一理由是让「按形态挑实现」这件事**只发生在一个地方**，而且不在调用路径上。
 * 调用路径（出网那一刻）看见的只是一个已经造好的 {@link StreamingCompletionPort}；
 * 没有任何 {@code switch (shape)} 或 {@code if (shape == ...)} 散落在管道里。
 *
 * <p>登记是一次性的，在构造时完成并**立刻自检**：一种形态忘了登记构造器，这里是启动期就炸，
 * 而不是等到某次请求选中那种形态才炸。后者是很贵的一类失败 ——
 * 它在测试里几乎撞不到，因为测试用的总是已经登记的那一种。
 *
 * <p>新增一种形态要在这里加一行。这处改动点是有意保留的：它的替代方案（反射扫描、SPI）
 * 会把「形态与构造器的对应关系」藏进类路径，那样一来「一共有几种形态」就没有一处能直接回答。
 */
public final class UpstreamProviderFactory {

  /** 形态 → 构造器的映射。缺一个就起不来，见 {@link #UpstreamProviderFactory()}。 */
  private final Map<UpstreamShape, ProviderConstructor> constructors;

  /**
   * @throws IllegalStateException 有形态没有登记构造器时抛出。
   *     宁肯起不来，也不要让请求走到一半才发现那种形态没法出网
   */
  public UpstreamProviderFactory() {
    Map<UpstreamShape, ProviderConstructor> registered = new HashMap<>();
    registered.put(UpstreamShape.OPENAI, UpstreamProviderFactory::openAiCompatible);
    for (UpstreamShape shape : UpstreamShape.values()) {
      if (!registered.containsKey(shape)) {
        throw new IllegalStateException("形态 " + shape.id() + " 没有登记构造器：请在本类补一行");
      }
    }
    this.constructors = Map.copyOf(registered);
  }

  /**
   * 按实例的形态造一个 Provider。**每实例一个**（ADR-0009 第 4 节的 B 路）：
   * 实例自己的地址、凭证与超时都烧进了这个对象，于是 {@code core} 的端口签名
   * 与 {@code faces} 都不必为「多实例」改动一行。
   *
   * @param inputs 这一次要造的东西，见 {@link ProviderInputs}
   * @return 这个实例的出站能力，只认 {@code Canonical} 进、{@code Canonical} 出
   */
  public UpstreamProvider create(ProviderInputs inputs) {
    ProviderConstructor constructor = constructors.get(inputs.shape());
    if (constructor == null) {
      // 构造时的自检已经放过这一关，能走到这里说明有人绕过了工厂。
      // 出错信息仍然指名形态：让它退化成一个不带形态名的空指针，最难排查。
      throw new IllegalStateException("形态 " + inputs.shape().id() + " 没有对应的构造器");
    }
    return constructor.create(inputs);
  }

  /** 本阶段登记的形态。它回答「现在有几种形态可用」，不必去遍历枚举再猜哪些已经落地。 */
  public String describeSupportedShapes() {
    return constructors.keySet().stream()
        .map(UpstreamShape::id)
        .collect(Collectors.joining(" / "));
  }

  /** OpenAI 兼容那一种：分帧器由形态给出来，其余都是这个实例自己的那一份。 */
  private static UpstreamProvider openAiCompatible(ProviderInputs inputs) {
    OpenAiCompatibleProvider provider =
        new OpenAiCompatibleProvider(inputs.wire(), inputs.shape().framerFactory(),
            inputs.apiKey(), inputs.model(), inputs.instanceId(), inputs.idleTimeout(),
            inputs.maxContentChars(), inputs.settle());
    // 同一个对象同时实现两个端口：这一条决定是否分流式只看形态，不看因人而异的实现细节。
    return new UpstreamProvider(provider, provider);
  }
}
