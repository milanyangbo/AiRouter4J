package io.github.airouter.core;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次调用的结算结果（ADR-0006 第 2 节）。
 *
 * <p>它是全链路**唯一**的结果形态：熔断器、限流回放、健康检查、指标、配额结算都只消费它。
 * 任何组件不得自行判断一次调用是成功还是失败 —— 那条约束由依赖方向规则兜底（ADR-0003）。
 *
 * @param requestId 这一次请求的标识
 * @param serviceId 目标 Service 名
 * @param instanceId 实例标识。现阶段没有实例注册表，端点地址即身份；注册表出现后由它提供 id
 * @param kind 结果类别
 * @param latency **到收到终态为止**的耗时。它让流式与非流式的延迟具有可比性：
 *                来源项目在响应头写出时就结算，流式样本因此恒为零，
 *                延迟感知负载均衡拿到的 EWMA 输入是假的
 * @param usage token 用量，上游没给时为 null，网关不估算
 * @param error 错误形态，{@code SUCCESS} 时为 null
 */
public record Outcome(
    String requestId,
    String serviceId,
    String instanceId,
    OutcomeKind kind,
    Duration latency,
    CanonicalUsage usage,
    GatewayError error) {

  /**
   * @throws IllegalArgumentException 必填字段缺失，或 SUCCESS 之外没有错误形态时抛出。
   *     这两处不宽容：一个缺了 error 的失败会让下游要么静默当作成功，要么各自补一个默认值，
   *     两种情形都会把「结算点唯一」这件事破坏掉
   */
  public Outcome {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(latency, "latency");
    if (kind != OutcomeKind.SUCCESS && error == null) {
      throw new IllegalArgumentException(kind + " 必须携带 error");
    }
  }
}
