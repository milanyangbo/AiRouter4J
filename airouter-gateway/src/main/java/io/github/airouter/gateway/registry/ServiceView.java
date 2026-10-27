package io.github.airouter.gateway.registry;

import java.util.List;
import java.util.Optional;

/**
 * 一个 Service 在运行时的形态：一组已经就绪的实例。
 *
 * <p>名字（id）在这一层才出现 —— 配置那一层的服务名是 Map 的键，没有 {@code name} 字段，
 * 于是「服务叫什么」从头到尾只有一处答案。
 *
 * @param id 服务名
 * @param instances 实例清单，保持配置里的顺序：选择阶段把顺序当成稳定的输入，
 *                  而「顺序不确定」会让同一份配置在不同进程里挑出不同的实例。
 *                  至少有一个实例：空服务已经在这一层之前挡掉
 */
public record ServiceView(String id, List<RegisteredInstance> instances) {

  /**
   * @throws IllegalArgumentException 实例清单为空时抛出：一个没有实例的服务在选择时无处可去，
   *     而这个「无处可去」必须是启动期就看得出来的事
   */
  public ServiceView {
    instances = List.copyOf(instances);
    if (instances.isEmpty()) {
      throw new IllegalArgumentException("一个 Service 至少要有一个实例，否则它的请求无处可去");
    }
  }

  /** 启用着的实例。 */
  public List<RegisteredInstance> enabledInstances() {
    return instances.stream().filter(RegisteredInstance::enabled).toList();
  }

  /**
   * 按 id 取一个实例。
   *
   * @param instanceId 实例 id
   * @return 有的那一个；没有就是 {@link Optional#empty()} ——
   *     「不存在」与「存在但被关闭」是两种不同的事，这个区别留给调用方去判
   */
  public Optional<RegisteredInstance> instance(String instanceId) {
    return instances.stream()
        .filter(instance -> instance.id().equals(instanceId))
        .findFirst();
  }
}
