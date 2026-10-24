package io.github.airouter.gateway.observe;

import io.github.airouter.gateway.routing.AvailabilitySnapshot;
import io.github.airouter.gateway.routing.InstanceAvailability;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读的运维查询面：每个实例的熔断状态与是否可用。
 *
 * <p>它不在原 spec 的用户故事列表里，是为了让「熔断器真的开了」这件事能被**机器**判定才引入的：
 * 否则验证熔断闭环只能靠读日志，而日志里的结论与运行中的状态之间隔着一整个序列化层。
 * 它后续也是运维层要消费的同一份数据 —— 因此做成只读查询而不是测试专用的后门。
 *
 * <p>为什么它不是 actuator 端点：actuator 的基础设施在 app 模块，而这份数据的归属在 gateway。
 * 等有了实例注册表，这一层仍然是同一份只读视图。
 *
 * <p>现阶段它没有鉴权，与管理端点一样默认只在内网可达 —— 这一条在接入认证那一票之前是已知状态。
 */
@RestController
public final class AvailabilityEndpoint {

  /** 查询路径。 */
  public static final String PATH = "/ops/instances";

  private final InstanceAvailability availability;

  public AvailabilityEndpoint(InstanceAvailability availability) {
    this.availability = Objects.requireNonNull(availability, "availability");
  }

  /**
   * 全部被纳入健康管理的实例此刻的可用性。
   *
   * <p>没被纳入的实例**不出现在名单上**，而不是以「不可用」的身份出现：
   * 名单的语义是「配置里有谁」，可用性是名单之上的一列。混在一起会让
   * 「这个实例该有但没有」与「这个实例有但坏了」变成同一行。
   *
   * @return 快照列表，按实例标识排序
   */
  @GetMapping(PATH)
  public List<AvailabilitySnapshot> instances() {
    return availability.all();
  }
}
