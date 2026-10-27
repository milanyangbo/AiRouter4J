package io.github.airouter.gateway.config;

import io.github.airouter.gateway.providers.UpstreamShape;
import java.time.Duration;

/**
 * 一个实例**出网那一侧**的身份：决定了它那个 Provider 长什么样的全部字段。
 *
 * <p>热重载按它判定「沿用还是重建」（{@link InstanceAssembler}）：这几个值没变，
 * 出网对象就没必要换；换了一个，旧的那个对象讲的已经是另一个端点的话。
 *
 * <p>**不在这里面的字段**（权重、启用标志、标签）改变的是「怎么挑它」，不是「它怎么出网」：
 * 改权重与把实例关掉再打开，都不该换掉出网对象 —— 挂在出网对象上的运行时状态
 * （熔断计数、延迟样本，ADR-0010 第 2 节）正是靠这一点在配置变更里活下来。
 * 一次配置变更等于一次全站状态清零，那是重启，不是热重载（ADR-0010 第 5 节）。
 *
 * @param shape 形态。同一种地址在不同形态下讲的不是同一种话
 * @param baseUrl 端点根地址
 * @param apiKey 实例自己的凭证。凭证轮换是**会改变出网对象**的那类变更
 * @param model 默认模型名。它烧进出网对象的请求体里
 * @param requestTimeout 单次调用的总上限。它烧进出站口，共用一个出站口就等于共用同一个超时
 */
public record OutboundIdentity(
    UpstreamShape shape,
    String baseUrl,
    String apiKey,
    String model,
    Duration requestTimeout) {
}
