package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轮询：光标在加权区间上一步步往前走，走到尽头回到起点。
 *
 * <p>光标是**进程级共享状态** —— 它是这个 bean 的一个字段，不是某一次调用的局部变量。
 * 因此断言必须写成与起点无关：1:1 看交替，3:1 取八次 = 两整轮。
 * 「第一次一定打给第一个实例」是一条与执行顺序有关的断言，换个执行顺序就红。
 *
 * <p>为什么装配期默认它而不是随机：轮询可预测，于是「权重真的生效」能被**严格**断言；
 * 随机只能断言比例，而比例对的实现也可能根本没看权重（spec 的「⑥」）。
 */
public final class RoundRobinSelector implements InstanceSelector {

  private final AtomicLong cursor = new AtomicLong();

  @Override
  public String select(WeightedCandidates candidates, SelectionContext context) {
    return candidates.pick(cursor.getAndIncrement()).id();
  }
}
