package io.github.airouter.gateway.routing;

import io.github.airouter.gateway.governance.CircuitState;

/**
 * 一个实例此刻的可用性，只读。
 *
 * <p>它是运维查询面返回的东西，也是「异常实例与正常实例可以被区分」的载体：
 * 三种字段的组合要能回答两个不同的问题 ——
 * 「它有没有被纳管」（配置的口径）与「它现在打不打得开」（熔断的口径）。
 * 只有后者的系统会在配置漏项时安静地把流量送进一个不存在的地方。
 *
 * @param instanceId 实例标识
 * @param managed 是否被纳入健康管理。false 时其余字段都没有意义
 * @param circuitState 熔断状态。未纳管时为 null；已纳管但从未失败过是 {@link CircuitState#CLOSED}
 * @param available 这一刻是否接收流量
 * @param lastTransitionRequestId 最近一次熔断状态迁移由哪次请求触发，从未迁移过时为 null
 */
public record AvailabilitySnapshot(String instanceId,
                                   boolean managed,
                                   CircuitState circuitState,
                                   boolean available,
                                   String lastTransitionRequestId) {
}
