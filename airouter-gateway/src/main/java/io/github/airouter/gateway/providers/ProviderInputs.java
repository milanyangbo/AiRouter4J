package io.github.airouter.gateway.providers;

import io.github.airouter.core.Outcome;
import io.github.airouter.gateway.transport.OutboundWire;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 造一个 Provider 要用到的东西。
 *
 * <p>它们都已经**不是配置**：地址变成了 {@link OutboundWire}，形态提供的是它给出去的分帧器
 * （经由 {@link UpstreamShape#framerFactory()}），凭证与模型名是被选中实例自己的那一份。
 * 翻译这一步发生在外面那一层（{@code config}），于是 {@code providers} 不需要认识配置类 ——
 * 换一套配置来源时动的是外面那一层，不是每一种形态的构造器。
 *
 * @param wire 这个实例自己的出站口。它带自己的地址与超时：两个实例的超时可以不同
 * @param shape 形态。同一种 wire 在不同形态下组装方式不同，所以构造器得知道自己在服务哪一种
 * @param apiKey 实例自己的凭证。出网用的就是它，不是调用方带来的那把
 * @param model 客户端没指定模型时用的默认值
 * @param instanceId 实例 id。它进结算记录，少了它事后分不清一次失败属于哪个实例
 * @param idleTimeout 块间静默上限
 * @param maxContentChars 单条流累积内容的字符上限
 * @param settle 结算动作。它是**一个动作**而不是「结算器」那个角色 ——
 *               Provider 需要的只有「把结论递出去」这一件事，知道背后有几位消费者没有用
 */
public record ProviderInputs(
    OutboundWire wire,
    UpstreamShape shape,
    String apiKey,
    String model,
    String instanceId,
    Duration idleTimeout,
    int maxContentChars,
    Consumer<Outcome> settle) {
}
