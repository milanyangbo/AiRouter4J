package io.github.airouter.gateway.registry;

import java.util.Map;
import java.util.Set;

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
 */
public final class UpstreamRegistry {

  /** 服务名 → 服务。对象换来换去（热重载），Map 本身不变，所以它是一次性封好的。 */
  private final Map<String, ServiceView> services;

  /**
   * @param services 服务清单，键是服务名。至少一个：空清单说明配置层没有把好关
   */
  public UpstreamRegistry(Map<String, ServiceView> services) {
    if (services == null || services.isEmpty()) {
      throw new IllegalArgumentException("注册表不能为空：没有任何服务时，请求无处可去");
    }
    this.services = Map.copyOf(services);
  }

  /** 已注册的服务名。 */
  public Set<String> serviceIds() {
    return services.keySet();
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
    ServiceView found = services.get(serviceId);
    if (found == null) {
      throw new IllegalArgumentException(
          "没有名为 " + serviceId + " 的服务；已注册的是 " + String.join("、", services.keySet()));
    }
    return found;
  }
}
