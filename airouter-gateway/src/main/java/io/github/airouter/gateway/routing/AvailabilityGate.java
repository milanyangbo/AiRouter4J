package io.github.airouter.gateway.routing;

import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CompletionPort;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.StreamingCompletionPort;
import io.github.airouter.core.UpstreamFailure;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/**
 * 判闸：不放行的请求**在这里就结束**，不产生出网调用。
 *
 * <p>它同时是两个端口的实现，两条路径共用同一道闸门。这不是为了少写一个类：
 * 「流式请求绕过熔断」正是来源项目那个三跳缺陷的跳点之一 ——
 * 只要两条路径各判一次，其中一条迟早会被改动漏掉，而漏掉的那一侧恰恰是更难被发现的流式。
 *
 * <p>被拒的请求**不产生结算**。一次本地拒绝不是一次上游调用，把它算作失败会让它继续喂熔断器，
 * 于是打开的熔断器永远等不到冷却结束之后的那次成功探测：
 * 拒绝 → 失败 → 继续打开，构成一个自己维持自己的循环。
 *
 * <p>实例标识先由构造参数给定：现阶段只有一个实例，选择就是「它接不接」。
 * 等有多个实例时，选谁发生在这里，下游那一行都不用改。
 */
public final class AvailabilityGate implements CompletionPort, StreamingCompletionPort {

  /** 不放行时对客户端给出的状态码。503 而不是 502：故障不在上游，是网关自己收住了流量。 */
  private static final int SERVICE_UNAVAILABLE = 503;

  private final CompletionPort sync;

  private final StreamingCompletionPort streams;

  private final InstanceAvailability availability;

  private final String instanceId;

  /**
   * @param sync 非流式的完成能力
   * @param streams 流式的完成能力。现阶段与 {@code sync} 是同一个对象，
   *                但这里仍然按两个端口收：哪一天它们不是同一个了，这个类不用改
   * @param availability 唯一那道闸门
   * @param instanceId 这个闸门守护的实例
   */
  public AvailabilityGate(CompletionPort sync,
                           StreamingCompletionPort streams,
                           InstanceAvailability availability,
                           String instanceId) {
    this.sync = Objects.requireNonNull(sync, "sync");
    this.streams = Objects.requireNonNull(streams, "streams");
    this.availability = Objects.requireNonNull(availability, "availability");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
  }

  @Override
  public CompletionStage<CanonicalResponse> complete(CanonicalRequest request) {
    if (!accepts(request)) {
      return CompletableFuture.failedFuture(new UpstreamFailure(rejected(request.serviceId()), null));
    }
    return sync.complete(request);
  }

  @Override
  public Flow.Publisher<CanonicalStreamEvent> stream(CanonicalRequest request) {
    if (!accepts(request)) {
      // 拒绝也要给一个终态：这条流在消费方看来必须有结局，
      // 而「一个事件都没有就结束了」与「成功了但什么都没说」在这条边界上无法区分。
      //
      // 这里不用 JDK 的 SubmissionPublisher：它只把订阅**之后**提交的条目排给订阅者，
      // 先提交再订阅的话条目被静默丢弃，只补一个 onComplete —— 表现为一条空流且不报错。
      return JdkFlowAdapter.publisherToFlowPublisher(
          Flux.just(new CanonicalStreamEvent.Failed(rejected(request.serviceId()))));
    }
    return streams.stream(request);
  }

  private boolean accepts(CanonicalRequest request) {
    return availability.accepts(instanceId, request.serviceId());
  }

  private GatewayError rejected(String serviceId) {
    return new GatewayError(
        "instance_unavailable",
        "Service " + serviceId + " 的这一实例此刻不接收流量："
            + "它没有被纳入健康管理，或者它的熔断器是打开的",
        SERVICE_UNAVAILABLE);
  }
}
