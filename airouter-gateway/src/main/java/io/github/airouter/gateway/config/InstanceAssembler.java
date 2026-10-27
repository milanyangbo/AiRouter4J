package io.github.airouter.gateway.config;

import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.gateway.providers.ProviderInputs;
import io.github.airouter.gateway.providers.UpstreamProvider;
import io.github.airouter.gateway.providers.UpstreamProviderFactory;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.ServiceView;
import io.github.airouter.gateway.transport.OutboundWire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 把配置里的实例变成注册表里的一项，并**记住上一回给出去的是哪一个出网对象**。
 *
 * <p>它是热重载里唯一需要判断「沿用还是重建」的地方（ADR-0010 第 5 节）。判断的依据是
 * {@link OutboundIdentity}：出网那一侧的身份没变，就把上一回那个 Provider 原样带过去；
 * 变了才造一个新的。于是：
 *
 * <ul>
 *   <li>改权重、把实例关掉再打开、改标签 —— 出网对象沿用，挂在它上面的运行时状态
 *       （熔断计数、延迟样本）跟着活下来
 *   <li>改地址、凭证、模型或超时 —— 换一个新的，因为旧的那一个讲的已经是另一个端点的话
 * </ul>
 *
 * <p>「记住上一回」这一份记忆放在这里而不是调用方：调用方（{@link RegistryReloader}）只管
 * 「换哪一版配置」，它不该同时知道「上一次每个实例用的是哪个对象」——
 * 两件事放一处时，回滚那条路很容易漏掉沿用表，表现为回滚之后每个实例都被无谓地重建一遍。
 *
 * <p>装配失败时**什么都不留下**：先算出完整的一版再整体替换记忆。
 * 中途炸掉就换掉一半记忆的实现，会让下一次变更沿用一份从来没生效过的对象。
 */
public final class InstanceAssembler {

  private final StreamProperties stream;

  private final UpstreamProviderFactory factory;

  private final Consumer<Outcome> settle;

  /** 上一次给出去的出网对象，按「服务 + 实例 id」索引。只有它被整体替换，不会半途改一半。 */
  private final Map<InstanceKey, Carried> carried = new LinkedHashMap<>();

  /**
   * @param stream 流式的块间上限、累积内容上限与字节缓冲
   * @param factory 形态工厂
   * @param observer 结算的唯一收口点
   */
  public InstanceAssembler(StreamProperties stream, UpstreamProviderFactory factory,
      OutcomeObserver observer) {
    this.stream = stream;
    this.factory = factory;
    // 结算在这里被降成一个动作交给 Provider：它需要知道的只是「把结论递出去」——
    // 于是每一种形态的构造器也不必认识「结算器」这个角色。
    this.settle = observer::settle;
  }

  /**
   * 把一整份服务配置装配成注册表的内容。
   *
   * <p>实例一个个来，但**记忆整体换**：先算完，再把新的一版整体换进去。
   *
   * @param services 三级配置模型，进来时已经过启动期校验
   * @return 服务名 → 服务
   */
  public synchronized Map<String, ServiceView> assemble(ServicesProperties services) {
    Map<String, ServiceView> views = new LinkedHashMap<>();
    Map<InstanceKey, Carried> nextCarried = new LinkedHashMap<>();
    services.services().forEach((serviceId, definition) -> {
      List<RegisteredInstance> instances = new ArrayList<>();
      for (InstanceDefinition instance : definition.instances()) {
        instances.add(assemble(serviceId, instance, nextCarried));
      }
      views.put(serviceId, new ServiceView(serviceId, instances));
    });
    // 被新配置删掉的实例也从这个记忆里消失：之后再加回来是一个新的实例，
    // 沿用一份「配置里早已没有它」的对象，比重建更难解释。
    carried.clear();
    carried.putAll(nextCarried);
    return Map.copyOf(views);
  }

  /**
   * 装配一个实例：出网对象能沿用就沿用。
   *
   * @param serviceId 所属服务名。实例 id 只在同一个服务内唯一，所以键里必须有服务名
   * @param instance 实例配置
   * @param nextCarried 这一次装配的成果，边装边记
   * @return 注册表里的一项
   */
  private RegisteredInstance assemble(String serviceId, InstanceDefinition instance,
      Map<InstanceKey, Carried> nextCarried) {
    InstanceKey key = new InstanceKey(serviceId, instance.id());
    OutboundIdentity identity = instance.outboundIdentity();
    Carried previous = carried.get(key);
    UpstreamProvider provider =
        previous != null && previous.identity().equals(identity) ? previous.provider() : build(instance);
    nextCarried.put(key, new Carried(identity, provider));
    return new RegisteredInstance(instance.id(), instance.shape(), instance.vendor(),
        instance.weight(), instance.enabled(), Set.copyOf(instance.tags()), provider);
  }

  /**
   * 造一个实例的出网能力：**它带自己的出站口**。
   *
   * <p>每一个实例得到自己的 {@link OutboundWire}：地址与总超时是实例自己的属性，
   * 共用一条出站口就意味着两个实例被迫共用同一个超时 ——
   * 而「给慢的那个实例单独配一个大超时」恰恰是多实例存在的理由之一。
   *
   * @param instance 实例配置，进来时已经过了它与形态共同的检查
   * @return 这个实例自己的出站能力
   */
  private UpstreamProvider build(InstanceDefinition instance) {
    OutboundWire wire = new OutboundWire(instance.baseUrl(), instance.requestTimeout(),
        stream.outboundBufferBytes(), stream.maxFrameBytes());
    ProviderInputs inputs = new ProviderInputs(wire, instance.shape(), instance.apiKey(),
        instance.model(), instance.id(), stream.idleTimeout(), stream.maxContentChars(), settle);
    return factory.create(inputs);
  }

  /** 一个实例在注册表里的位置：服务名 + 实例 id。实例 id 只在同一个服务内唯一。 */
  private record InstanceKey(String serviceId, String instanceId) {
  }

  /** 上一次给这个实例的出网对象，连同它是按什么身份造出来的。 */
  private record Carried(OutboundIdentity identity, UpstreamProvider provider) {
  }
}
