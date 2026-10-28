package io.github.airouter.gateway.select;

/**
 * 某个候选**当前压着几个请求**这件事查不到，而这个策略不能没有它。
 *
 * <p>它跟「知道是零」不是一回事：后者是一个可用的答案，前者是说这件事无解。
 * 由 {@code select} 里的策略抛出时，它说的是「装配把在途计数接丢了一半」——
 * 候选来自注册表，而在途数来自 transport，这两处的键一一对应是装配的结果。
 *
 * <p>为什么不当成 0：当成 0 之后，所有实例看起来一样闲，
 * 于是 {@code least-connections} 变成了一个有人记账版的多实例随机。
 * 它不会被任何人发现 —— 系统在跑、日志干净，只是负载不再均衡。
 */
public final class ActiveCountMissingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param instanceId 查不到在途数的那个实例
   */
  public ActiveCountMissingException(String instanceId) {
    super("查不到实例 " + instanceId + " 当前压着几个请求：它的出站口没有登记在途计数，"
        + "于是 least-connections 拿不到自己的输入。按 0 处理会让这个策略退化成随机，"
        + "所以这里直接失败");
  }
}
