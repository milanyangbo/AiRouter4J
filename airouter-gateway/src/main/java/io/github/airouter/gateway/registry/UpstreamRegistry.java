package io.github.airouter.gateway.registry;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * **注册表**：「配置 → 运行时对象」的**唯一**持有者（ADR-0010 第 2 节）。
 *
 * <p>它持有服务、实例，以及每个实例已经就绪的 Provider。其它包不自己缓存这些东西 ——
 * 一旦有两处持有，热重载必然漏改一处，而那表现为「改了配置，有一部分实例还在用旧的那份」。
 *
 * <p>它不做选择：挑哪个实例是选择链（ADR-0011）的事，那一层是一组可以确定性断言的纯函数。
 * 把选择混进来，注册表就只有在真的存在一个上游实例时才能被测试。
 *
 * <p>构造即就绪：这里的每一项都已经是一个能出网的对象 ——
 * 「配置有效」的判断发生在进入这里之前（那一层会**启动失败**），而不是在取用时的那一刻。
 *
 * <p>**内容可以被整体换掉**（{@link #replace}），而它自己这个对象不变 —— 这是热重载不重建
 * Spring 上下文的前提（ADR-0010 第 5 节）：换的是注册表里的东西，不是注册表本身，
 * 于是持有它的那些组件（Face、选择链、治理）一个都不必被重新装配。
 *
 * <p>换的是**整个 Map 的一次引用赋值**：读的一方要么看到换之前那一版，要么看到换之后那一版，
 * 不存在「服务换了一半」的中间状态。在途的请求早已把它要用的那一份对象取走了 ——
 * 它们拿到的是具体的 Provider，不是这张 Map，所以换 Map 对它们没有影响。
 */
public final class UpstreamRegistry {

  /**
   * 服务名 → 服务。
   *
   * <p>用 {@link AtomicReference} 而不是 {@code volatile Map}：换的是整张表，
   * 而「换」这个动作本身要在多线程下一次生效。存进去之前一律先封成不可变 Map ——
   * 调用方之后再改自己那个 Map，不会改到注册表里的这一份。
   */
  private final AtomicReference<Map<String, ServiceView>> services;

  /**
   * @param services 服务清单，键是服务名。至少一个：空清单说明配置层没有把好关
   */
  public UpstreamRegistry(Map<String, ServiceView> services) {
    this.services = new AtomicReference<>(sealed(services));
  }

  /**
   * 换掉注册表里的全部内容。
   *
   * <p>它是热重载的**唯一**写入口：一次配置变更最终落到这一行。
   * 之所以整体换而不是逐个服务增删：逐个改的过程里，注册表会短暂地处于
   * 「新服务已经进来、旧服务还没出去」的状态，那正好是「同一个服务名在两版配置里都有、
   * 但内容不同」时最容易出错的时刻 —— 而那恰恰是热重载最常见的形状。
   *
   * @param next 新的服务清单，至少一个
   * @throws IllegalArgumentException 清单为空时抛出：把注册表清空等于让全部请求无处可去
   */
  public void replace(Map<String, ServiceView> next) {
    this.services.set(sealed(next));
  }

  /** 已注册的服务名。 */
  public Set<String> serviceIds() {
    return services.get().keySet();
  }

  /**
   * 按名取一个服务。
   *
   * @param serviceId 服务名
   * @return 有的那一个
   */
  public Optional<ServiceView> service(String serviceId) {
    return Optional.ofNullable(services.get().get(serviceId));
  }

  /**
   * 按名取一个服务，**必须有**。
   *
   * @param serviceId 服务名
   * @return 那个服务
   * @throws IllegalArgumentException 没有这个服务时抛出，并**列出已有的服务名**。
   *     「服务不存在」在运行期出现时，最贵的是不知道自己配错了哪一个字 ——
   *     所以出错信息要把可选项摆出来
   */
  public ServiceView requireService(String serviceId) {
    Map<String, ServiceView> current = services.get();
    ServiceView found = current.get(serviceId);
    if (found == null) {
      throw new IllegalArgumentException(
          "没有名为 " + serviceId + " 的服务；已注册的是 " + String.join("、", current.keySet()));
    }
    return found;
  }

  private static Map<String, ServiceView> sealed(Map<String, ServiceView> services) {
    if (services == null || services.isEmpty()) {
      throw new IllegalArgumentException("注册表不能为空：没有任何服务时，请求无处可去");
    }
    return Map.copyOf(services);
  }
}
