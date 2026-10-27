package io.github.airouter.gateway.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 三级配置模型的根：哪些 Service、各自哪些实例，以及「默认送到哪个 Service」。
 *
 * <p>绑定前缀是 {@code airouter}，所以 `{@code services}` 成员接管的是
 * `{@code airouter.services.<service>}` 这一棵子树，而流式那五个 SLO 仍在
 * `{@code airouter.stream}` 下由 {@link StreamProperties} 认领 —— 两者互不重叠。
 *
 * <p>服务名是 Map 的键，不是 {@link ServiceDefinition} 里的字段：
 * 「服务叫什么」因此只有一处答案。代价是无法在服务自己的结构里引用这个名字 ——
 * 那正是想要的结果：能不一致的地方被去掉了，而不是被校验重新找回来。
 *
 * <p>**「默认服务」是一个阶段性的东西**：路由规则（ADR-0012）进来之后，
 * 请求落到哪个 Service 由规则决定，届时这一位要么消失、要么降级为「没命中规则时的兜底」。
 * 现阶段没有路由器，Face 必须知道该打给谁，于是它显式存在并被校验，
 * 而不是藏在一句「取第一个服务」里变成一个隐含约定。
 *
 * @param defaultService 默认服务名。必须 {@link #services()} 里有这一项，否则起不来
 * @param services 服务清单。至少一个
 */
@ConfigurationProperties(prefix = ServicesProperties.PREFIX)
public record ServicesProperties(String defaultService, Map<String, ServiceDefinition> services) {

  /** 属性前缀。写在常量里，让 yml、测试与出错信息引用的是同一个字符串。 */
  public static final String PREFIX = "airouter";

  /** default-service 的属性名。 */
  public static final String DEFAULT_SERVICE_KEY = PREFIX + ".default-service";

  /**
   * @throws IllegalArgumentException 没有配置任何服务、某个服务没有启用着的实例、
   *     或默认服务缺失 / 不在服务清单里时抛出，并**指名收到的那个名字**
   */
  public ServicesProperties {
    // 先把外部给的 Map 封成不可变的，再校验。这一步同时也是让「内部不应被外部改」
    // 这件事成立 —— 它在源头上替掉了「返回一个被别人持有的 Map」那一种写法。
    services = services == null ? Map.of() : Map.copyOf(services);
    if (services.isEmpty()) {
      throw new IllegalArgumentException(
          "没有配置任何 Service：请在 " + PREFIX + ".services 下至少声明一个");
    }
    services.forEach((id, definition) -> {
      if (definition.enabledInstances().isEmpty()) {
        throw new IllegalArgumentException(
            "服务 " + id + " 声明了实例但全部是关闭状态：请至少启用一个");
      }
    });
    if (defaultService == null || defaultService.isBlank()) {
      throw new IllegalArgumentException(
          "缺少默认服务名：请在 " + DEFAULT_SERVICE_KEY + " 里给出");
    }
    if (!services.containsKey(defaultService)) {
      throw new IllegalArgumentException(
          "默认服务 " + defaultService + " 不在服务清单里；已声明的是 "
              + String.join("、", services.keySet()));
    }
  }

  /** 默认服务。它的名字已经验过存在于清单里，所以这里拿到的不会是空的。 */
  public ServiceDefinition defaultServiceDefinition() {
    return services.get(defaultService);
  }
}
