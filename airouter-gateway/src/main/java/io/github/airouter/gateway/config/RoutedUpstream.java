package io.github.airouter.gateway.config;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.gateway.providers.UpstreamProvider;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.ServiceView;
import io.github.airouter.gateway.routing.NoUsableInstanceException;
import io.github.airouter.gateway.routing.RoutedPort;
import io.github.airouter.gateway.routing.SelectionChain;
import io.github.airouter.gateway.routing.SelectionContext;
import io.github.airouter.gateway.select.Candidate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Function;

/**
 * 「请求打给谁」的**唯一**入口：非流式与流式两个端口调**同一个**解析入口。
 *
 * <p>它取代了原先装配期就固定下来的那个 `upstreamProvider`：那时挑实例发生在容器启动的时候，
 * 取的是「默认服务的第一个启用着的实例」，于是票 07 加进配置模型的
 * **权重 / 标签 / 启用标志全是有配置、无消费者的字段** —— 配了权重却不生效，
 * 系统照跑、日志干净、只是流量均分，一种典型的静默失效。
 *
 * <p>两个端口**来自同一个对象**，而且都调 {@link #resolve}：
 * 「流式绕过选择」在形状上就没有第二条路可走。这一点值得单列，
 * 因为来源项目最贵的那个缺陷正是从「挑实例散在若干 handler 里、流式那条自己抄了一遍」长出来的
 * （ADR-0011 第 5 节末尾那两条）。
 *
 * <p>解析发生在**每次请求**，不是装配期：将来配置变更能落到新请求上（ADR-0010 第 5 节，阶段 3）。
 *
 * <p>它按 id 从注册表取回那个**能出网的对象**：链只产出 id。
 * 于是「选得对不对」不需要先造出一个能出网的东西就能断言。
 *
 * <p>存的是「按名取服务」这个**动作**（{@link Function}）而不是注册表本身：
 * 动作绑定在实例上，将来热重载换掉注册表里的内容，这里读到的仍然是新的那一份。
 *
 * <p>它是 public 的：app 的测试要在不起容器时**真实装配**它 ——
 * 三类选择拒绝在容器的装配里造不出来（启动闸保证候选非空、标签恒空、闸门恒开），
 * 而「客户端收到什么」只能拿真链与真端口来回答。
 */
public final class RoutedUpstream implements RoutedPort {

  private final Function<String, ServiceView> services;

  private final String defaultService;

  private final SelectionChain chain;

  /**
   * @param services 按名取服务视图的动作。它每次请求都被调用一次 —— 这就是「每次请求解析」
   * @param defaultService 默认服务名
   * @param chain 六步链
   */
  public RoutedUpstream(Function<String, ServiceView> services, String defaultService,
      SelectionChain chain) {
    this.services = Objects.requireNonNull(services, "services");
    this.defaultService = Objects.requireNonNull(defaultService, "defaultService");
    this.chain = Objects.requireNonNull(chain, "chain");
  }

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request,
      SelectionContext context) {
    return resolve(context).completion().complete(request);
  }

  @Override
  public Publisher<CanonicalStreamEvent> stream(CanonicalRequest request,
      SelectionContext context) {
    return resolve(context).streaming().stream(request);
  }

  /**
   * 没有入站上下文时的入口（core 那两个端口的签名里只有请求）。
   *
   * <p>它同样过 {@link #resolve}，只是没有调用方标识 ——
   * 于是粘滞与两个 hash 策略走到的是「标识缺失」那一条显式路径，而不是悄悄分岔出去。
   */
  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    return complete(request, SelectionContext.withoutCaller());
  }

  /** 同上：没有入站上下文时的流式入口。 */
  @Override
  public Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    return stream(request, SelectionContext.withoutCaller());
  }

  /**
   * 两个端口共用的解析入口：跑完六步，再按 id 把那个能出网的对象取回来。
   *
   * @param context 这一次调用交给链的输入
   * @return 选中实例自己的出站能力
   * @throws NoUsableInstanceException 选不出实例时抛出：不出网、也不结算
   */
  private UpstreamProvider resolve(SelectionContext context) {
    ServiceView view = services.apply(defaultService);
    List<Candidate> candidates = view.enabledInstances().stream()
        .map(RoutedUpstream::asCandidate)
        .toList();
    String instanceId = chain.select(defaultService, candidates, context);
    return view.instance(instanceId)
        .map(RegisteredInstance::provider)
        .orElseThrow(() -> new IllegalStateException(
            "链选出的实例 " + instanceId + " 不在服务 " + defaultService + " 的注册表里："
                + "候选来自这个服务，因此这是实现错误，不是配置错误"));
  }

  /**
   * 注册表里的实例 → 候选：只留下选择要用到的三项。
   *
   * <p>为什么不直接把 {@link RegisteredInstance} 交给链：它带着能出网的对象，
   * 而「选得对不对」与那个对象毫无关系 —— 带进来只会让每条断言都先造一次出站管道。
   */
  private static Candidate asCandidate(RegisteredInstance instance) {
    return new Candidate(instance.id(), instance.weight(), instance.tags());
  }
}
