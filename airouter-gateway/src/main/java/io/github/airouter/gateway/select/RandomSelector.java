package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * 随机：在加权区间上均匀抽一个值，落点所在的候选即选中项。
 *
 * <p>随机源**从外面注入**，注入的形状是「给定上界，返回 {@code [0, 上界)} 里的一个值」这个函数。
 * 这样做只有一条理由：测试里把它换成固定值之后结果可复现 ——
 * 否则「随机对不对」只能断言比例，而比例对的实现也可能压根没看权重。
 *
 * <p>本票的默认装配不是它（轮询可预测，权重能被严格断言），
 * 但它在工厂里登记着，配置写 {@code random} 即可换过来（票 09）。
 */
public final class RandomSelector implements InstanceSelector {

  private final LongUnaryOperator randomSource;

  /**
   * @param randomSource 给定上界返回 {@code [0, 上界)} 的一个值
   */
  public RandomSelector(LongUnaryOperator randomSource) {
    this.randomSource = Objects.requireNonNull(randomSource, "randomSource");
  }

  /** 用进程级随机源的那一份。 */
  public static RandomSelector usingSharedRandom() {
    return new RandomSelector(bound -> ThreadLocalRandom.current().nextLong(bound));
  }

  @Override
  public String select(WeightedCandidates candidates, SelectionContext context) {
    return candidates.pick(randomSource.applyAsLong(candidates.total())).id();
  }
}
