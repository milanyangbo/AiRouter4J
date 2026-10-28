package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 一致性哈希：**同一个调用方落在同一个实例上，而且实例的增减只搬动它必须搬动的那一部分键**。
 *
 * <p>做法是键环 + 虚拟节点：每个候选在环上占若干个点，一个键落到它顺时针方向的第一个点上。
 * 加权在这里是**内建的** —— 虚拟节点数与权重成正比，权重 3 的那个候选在环上有三倍的点，
 * 于是它接到约三倍的键。**它因此不再走第 ④ 步的区间**：两者都是加权，叠加就是双重加权，
 * 而「已经加过权了」这件事叠完之后就再也检查不出来（spec 的「⑥」)。
 *
 * <p>环会被**缓存**，键是这一次候选的「id : 权重」串。**每次请求重建一个环**才是不该有的代价：
 * 一次选择建几千个点，而候选平常一动不动 ——
 * 候选集合与上次相同就复用，变了才重建。这个缓存不影响结果的可复现性：
 * 同一个候选集合永远得到同一棵树。
 *
 * <p>稳定性靠两件事，它们合起来才是「一致性」这个名字的含义：
 *
 * <ul>
 *   <li>**移除**一个候选时，只有原本落在它上面的键会迁移 —— 别的键不受牵连
 *   <li>**新增**一个候选时，迁移的键全部落到新增的那一个上；
 *       已有的候选之间不会因为新增而互相让出键
 * </ul>
 *
 * <p>**拿不到调用方标识时它报错**，与 {@code ip-hash} 同一个理由、同一个异常类型。
 */
public final class ConsistentHashSelector implements InstanceSelector {

  /** 策略在配置里的名字。 */
  public static final String STRATEGY_ID = "consistent-hash";

  /**
   * 单个候选在环上最多占多少个虚拟节点。
   *
   * <p>上限不是随口设的：虚拟节点数与**权重**成正比，而权重是给人写 3:1 这种话用的，
   * 有人写 1000:1 的时候该丑陋地被它挡住 ——
   * 「一个候选 40 万个点」不会因为节点多了而更准，只会让每次重建变得很难看。
   */
  private static final int MAX_NODES_PER_CANDIDATE = 10_000;

  private final int virtualNodesPerWeight;

  /** 上一次算出来的环。它是缓存：丢掉重算不影响任何结果。 */
  private volatile Ring cached;

  /**
   * @param virtualNodesPerWeight 每个**单位权重**在环上占多少个虚拟节点。
   *                              它决定键分布的均匀程度：太少时键会成堆落在少数点上，
   *                              于是「按权重比例分键」变成一句空话。
   *                              **它是显式、可配的常量**，不接受藏在实现里
   * @throws IllegalArgumentException 它不是正数时抛出
   */
  public ConsistentHashSelector(int virtualNodesPerWeight) {
    if (virtualNodesPerWeight <= 0) {
      throw new IllegalArgumentException("每个单位权重的虚拟节点数必须是正整数，收到的是 "
          + virtualNodesPerWeight + "：为零的环上一个点都没有，键无处可落");
    }
    this.virtualNodesPerWeight = virtualNodesPerWeight;
  }

  /**
   * @param candidates 第 ④ 步交出来的加权候选。**它的权重在这里「被实现」为虚拟节点数**，
   *                   因此本策略不讲 {@code candidates.total()} 那一套区间
   * @param context 第 ① ② 步的产物；只有第 ① 步的调用方标识进去了
   * @return 这个键顺时针遇到的第一个点属于哪个候选
   */
  @Override
  public String select(WeightedCandidates candidates, SelectionContext context) {
    String callerId = context.requireCallerId(STRATEGY_ID);
    Ring ring = ringFor(candidates);
    return ring.pointFor(callerId);
  }

  /**
   * 取这一批候选对应的环：候选没变就复用。
   *
   * <p>两个线程同时发现环过期时会各建一棵，结果一致，谁先落地谁先被用 ——
   * 这里不需要锁，因为树的形状只取决于候选集合本身。
   */
  private Ring ringFor(WeightedCandidates candidates) {
    Ring current = cached;
    if (current != null && current.matches(candidates)) {
      return current;
    }
    Ring built = build(candidates);
    cached = built;
    return built;
  }

  private Ring build(WeightedCandidates candidates) {
    NavigableMap<Long, String> points = new TreeMap<>();
    List<String> shape = new ArrayList<>();
    for (Candidate candidate : candidates.candidates()) {
      shape.add(candidate.id() + ":" + candidate.weight());
      int nodes = Math.min(MAX_NODES_PER_CANDIDATE, virtualNodesPerWeight * candidate.weight());
      for (int index = 0; index < nodes; index++) {
        points.put(StableHash.of(candidate.id() + "#" + index), candidate.id());
      }
    }
    return new Ring(List.copyOf(shape), points);
  }

  /**
   * 一棵键环：**它由哪些候选构成** 与 **环上的点**。
   *
   * <p>存下来「由哪些候选构成」是为了回答「候选变了吗」 ——
   * 只比那一串 id 与权重，而不是去比树的内容：后者要把整棵树走一遍。
   */
  private record Ring(List<String> shape, NavigableMap<Long, String> points) {

    private Ring {
      // 在构造器里留一份自己的复本：这一份不许被别人改。
      points = new TreeMap<>(Collections.unmodifiableNavigableMap(points));
    }

    private boolean matches(WeightedCandidates candidates) {
      List<String> theirs = candidates.candidates().stream()
          .map(candidate -> candidate.id() + ":" + candidate.weight())
          .toList();
      return shape.equals(theirs);
    }

    /**
     * 这个键落在哪个候选上：顺时针遇到的第一个点；走到尽头就绕回第一个点，因此环**不会**有空位。
     */
    private String pointFor(String key) {
      Map.Entry<Long, String> point = points.ceilingEntry(StableHash.of(key));
      if (point == null) {
        point = points.firstEntry();
      }
      return point.getValue();
    }
  }
}
