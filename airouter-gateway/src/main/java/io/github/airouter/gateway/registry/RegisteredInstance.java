package io.github.airouter.gateway.registry;

import io.github.airouter.gateway.providers.UpstreamProvider;
import io.github.airouter.gateway.providers.UpstreamShape;
import java.util.Set;

/**
 * 注册表里的一项：一个实例**连同它已经就绪的出站能力**。
 *
 * <p>它对着三件事：配置怎么说的（id / 形态 / 厂商 / 权重 / 启用标志 / 标签），
 * 以及出去要用的那个对象（{@link UpstreamProvider}）。
 * 运行时长出来的东西（熔断状态、延迟样本、健康登记）随后也挂在这里 ——
 * **它们挂在这里，而不是挂在别处属于自己的缓存里**：一旦有两处持有，热重载必然漏改一处。
 *
 * @param id 实例 id。同一服务下唯一
 * @param shape 形态
 * @param vendor 引用的厂商声明名；没有引用就是 null
 * @param weight 权重。它是加权选择的除数，所以进来之前已经验过是正数
 * @param enabled 启用标志。关闭的实例仍在注册表里（热重载可能把它打开），但不参与选择
 * @param tags 标签。规则匹配按它挑目标；进来之前已被封成不可变集合
 * @param provider 这个实例自己的出站能力。每实例一个，不共享
 */
public record RegisteredInstance(
    String id,
    UpstreamShape shape,
    String vendor,
    int weight,
    boolean enabled,
    Set<String> tags,
    UpstreamProvider provider) {
  /**
   * 标签在这里封口成不可变集合。
   *
   * <p>它会被交给规则匹配与限流作用域，两处都只该读、不该改同一份集合。
   * 另一层理由写在丑陋处：SpotBugs 的 {@code EI_EXPOSE_REP2} 按「有没有在构造器里把字段封死」
   * 判定这一类问题 —— 在 compact constructor 里做这件事，同时也是让那条规则通过的写法。
   */
  public RegisteredInstance {
    tags = Set.copyOf(tags);
  }
}
