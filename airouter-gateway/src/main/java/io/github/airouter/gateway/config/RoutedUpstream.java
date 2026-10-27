package io.github.airouter.gateway.config;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.providers.UpstreamProvider;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.ServiceView;
import io.github.airouter.gateway.routing.Candidate;
import io.github.airouter.gateway.routing.NoUsableInstanceException;
import io.github.airouter.gateway.routing.SelectionChain;
import io.github.airouter.gateway.routing.SelectionContext;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Function;

/**
 * Face 那两个端口的实现：**每一次调用都先走一遍选择链**（ADR-0011）。
 *
 * <p>它取代的是「默认服务的第一个实例」那一个写死的答案。
 * 那一个答案在只有一个实例的时候看不出问题，而多实例一出现，
 * 「请求打给谁」就变成一个没有测试、没有记录、也没有人能改的决定。
 *
 * <p>**流式与非流式走同一条链**：两个方法都调同一个 {@link #chosen(CanonicalRequest)}。
 * 这一点是被断言过的（端到端那一条），因为来源项目正是从这里分岔的 ——
 * 流式那条路绕过了选择、也绕过了治理，于是「同样的请求，流式与非流式打给不同的实例」
 * 成为一个没人会去查的行为差异。
 *
 * <p>解析发生在**一次请求开始的那一刻**，之后这一次请求用的就是那一刻拿到的对象：
 * 在途的流不受下一次变更影响，正是因为这里只在开头问了一次（ADR-0010 第 5 节）。
 *
 * <p>它自己就同时是两个端口，理由与 {@link UpstreamProvider} 相同：
 * 容器按运行期类型匹配候选，拆成两个 bean 会让注入任何一侧都同时匹配上两个。
 */
public final class RoutedUpstream implements CompletionPort, StreamingCompletionPort {

  /** 「没有可用实例」对外呈现的错误形态：503，因为它会随实例恢复而消失，不是客户端的错。 */
  public static final String NO_USABLE_INSTANCE = "no_usable_instance";

  private static final int NO_USABLE_INSTANCE_STATUS = 503;

  /**
   * **按服务名取回服务**的那一个动作，而不是注册表本身。
   *
   * <p>只存这一个动作而不是整个注册表，是因为这里需要的只是「现在这一个服务长什么样」；
   * 持有注册表会让这个类看起来拥有一切，而「它其实只看一个服务」这件事就从签名里消失了。
   * 顺带也避开了 SpotBugs 的 {@code EI_EXPOSE_REP2}：注册表内部持有可变的引用，
   * 按 ADR-0008 第 7 节一律改代码而不是加抑制，于是这里收窄成 JDK 的 {@link Function}。
   */
  private final Function<String, ServiceView> serviceLookup;

  private final ConfigurationLedger ledger;

  private final SelectionChain chain;

  /**
   * @param serviceLookup 按服务名取回当前那一版服务
   * @param ledger 版本账本：默认服务名取自**当前生效的那一版**，不是启动时那一版
   * @param chain 六步选择链
   */
  public RoutedUpstream(Function<String, ServiceView> serviceLookup, ConfigurationLedger ledger,
      SelectionChain chain) {
    this.serviceLookup = serviceLookup;
    this.ledger = ledger;
    this.chain = chain;
  }

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    return chosen().completion().complete(request);
  }

  @Override
  public Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    return chosen().streaming().stream(request);
  }

  /**
   * 走一遍链，取回那一个实例的出网能力。
   *
   * <p>「启用」这一层筛在这里而不在链里：链不知道实例有没有被关掉 ——
   * 那是配置的事，而配置只有这一处看得见。
   *
   * <p>服务名取自账本里的当前那一版而不是请求：请求上的那一个是入站路径自己写的
   * （现阶段只有一个 Service，Face 直接写死了它），把它当成目标会让
   * 「默认服务配成了另一个」这种配置变成一件没人会去看的事。
   *
   * @return 选中实例自己的出网能力
   */
  private UpstreamProvider chosen() {
    String serviceId = ledger.current().services().defaultService();
    ServiceView service = serviceLookup.apply(serviceId);
    List<Candidate> candidates = service.enabledInstances().stream()
        .map(RoutedUpstream::asCandidate)
        .toList();
    try {
      Candidate chosen = chain.select(serviceId, candidates, SelectionContext.ANONYMOUS);
      // 从**同一个** ServiceView 上取回实例：它是那个时刻的一张快照，
      // 换一处再查一次就可能在两次查询之间被热重载换掉。
      return service.instance(chosen.id())
          .orElseThrow(() -> new IllegalStateException(
              "链选中了实例 " + chosen.id() + "，但服务 " + serviceId + " 里没有它"))
          .provider();
    } catch (NoUsableInstanceException rejected) {
      // 拒绝也是一种结论，但它不是**上游**的结论：没有实例被联系过，
      // 所以它不带任何上游该有的形态，只带「为什么一个都没挑出来」。
      throw new UpstreamFailure(new GatewayError(NO_USABLE_INSTANCE, rejected.reason(),
          NO_USABLE_INSTANCE_STATUS), rejected);
    }
  }

  /** 注册表里的一项 → 链要的那一份候选。链认识的是这三项，不是「能出网的实例」。 */
  private static Candidate asCandidate(RegisteredInstance instance) {
    return new Candidate(instance.id(), instance.weight(), instance.tags());
  }
}
