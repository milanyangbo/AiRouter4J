package io.github.airouter.gateway.config;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一个**服务**（GLOSSARY 的 {@code Service}）：一组提供同一件事的实例。
 *
 * <p>它的名字不在自己身上 —— 名字是 {@link ServicesProperties} 里的 Map 键。
 * 于是「服务叫什么」只有一处答案，不存在「结构里的 name 字段与 Map 键不一致」这种可能。
 *
 * <p>这里判的就是 ADR-0010 第 4 节那一条：**同一 Service 下实例 id 重复，启动失败**。
 * 缺了这条，两个实例里有一个会在 Map/覆盖里被静默丢掉，
 * 表现为「配了两个实例，实际只有一个在干活」—— 而它在流量上看起来只是负载偏高。
 *
 * @param instances 实例清单。至少一个：一个没有实例的服务在选择时无处可去，
 *                  而「无处可去」应该发生在启动期，不是第一次调用时
 */
public record ServiceDefinition(List<InstanceDefinition> instances) {

  /**
   * @param instances 实例清单
   * @throws IllegalArgumentException 清单为空，或其中有重复 id 时抛出，并**指名重复的那个 id**
   */
  public ServiceDefinition {
    if (instances == null || instances.isEmpty()) {
      throw new IllegalArgumentException("一个 Service 至少要有一个实例，否则它的请求无处可去");
    }
    instances = List.copyOf(instances);
    Set<String> seen = new HashSet<>();
    Set<String> duplicated = instances.stream()
        .map(InstanceDefinition::id)
        .filter(id -> !seen.add(id))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!duplicated.isEmpty()) {
      throw new IllegalArgumentException(
          "同一个 Service 下出现了重复的实例 id：" + String.join("、", duplicated)
              + "。重复的那一个会被静默丢掉，这是启动期就该报出来的配置错误");
    }
  }

  /** 启用着的实例。关闭的实例不参与选择，但它仍然是配置的一部分。 */
  public List<InstanceDefinition> enabledInstances() {
    return instances.stream().filter(InstanceDefinition::enabled).toList();
  }
}
