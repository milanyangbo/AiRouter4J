package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;
import io.github.airouter.gateway.routing.CallerIdentityMissingException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 第 ⑤ 步：粘滞 —— **包在策略之外的一个包装器**，不是第七种策略（ADR-0011 第 2 节 (c)）。
 *
 * <p>它只做一件很窄的事：问一次「这个键上次落在哪个实例上」，
 * 命中、而且那个实例还在候选里，就直接用；否则交给被包的那个策略重挑，并把新结果记回去。
 *
 * <p>做成包装器而不是第七种策略，换来的是「粘滞可与任意策略组合」：
 * 六个策略不必各写一遍粘滞，也不必回答「粘滞 + 一致性哈希」算第几种策略这种问题。
 * 它的来源形态（{@code StickyLoadBalancer}）本身就是包装器，
 * 搬的时候形状必须保持一致 —— 搬成策略会当场丢掉这个性质。
 *
 * <p>**认不出这次调用算谁的时候它拒绝，而不是退化成每次换一个实例。**
 * 后者正是票 07 抓出来的那类失败：配了的开关在一部分调用上没有兑现，
 * 系统照跑、日志干净，只有一个会话被拆到了若干实例上才看得出来。
 *
 * <p>表是**有界**的：键的生命周期由调用方决定，网关无从知道一个调用方是不是再也不来了。
 * 一张只增不减的表迟早会被撑爆，而一张永不清理的表会让「为什么粘在这个实例上」变得无从解释。
 * 满容量时挤掉**最久没有被用过的那个键**（访问序 {@link LinkedHashMap}）；
 * 顺手清理的还有另一件事 —— 发现粘着的实例已经不在候选里时，当场把那条记录丢掉。
 *
 * <p>整张表由自己那把锁守着：读取上次的位置与记回这次的结果是**一个不可分割的动作**，
 * 分两步走会让两个并发的同键请求各自重挑一次，于是「同一个调用方粘在同一个实例上」
 * 变成一个只对慢一点的那个成立的性质。锁里没有 IO，只有几次哈希查找。
 */
public final class StickySelector implements InstanceSelector {

  private final InstanceSelector delegate;

  private final StickyKeyResolver keys;

  private final int maxEntries;

  private final Object lock = new Object();

  /** 键 → 上次落到哪个实例。访问序，队首即最久没有被用过的那个键。 */
  private final Map<String, String> bindings = new LinkedHashMap<>(16, 0.75f, true);

  /**
   * @param delegate 被包的那一个策略
   * @param keys 键从哪来
   * @param maxEntries 表的容量上限，至少为 1
   */
  public StickySelector(InstanceSelector delegate, StickyKeyResolver keys, int maxEntries) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.keys = Objects.requireNonNull(keys, "keys");
    if (maxEntries <= 0) {
      throw new IllegalArgumentException(
          "粘滞表的容量上限必须是正整数，收到的是 " + maxEntries + "：它为零时每一次选择都会触发一次淘汰");
    }
    this.maxEntries = maxEntries;
  }

  /**
   * 这次调用粘在哪个实例上。
   *
   * @param candidates 第 ④ 步交出来的加权候选
   * @param context 第 ① ② 步的产物
   * @return 实例 id
   * @throws CallerIdentityMissingException 认不出这次调用算谁时抛出。**不退化** ——
   *     退化会让粘滞在缺失标识的那一部分调用上静默失效
   */
  @Override
  public String select(WeightedCandidates candidates, SelectionContext context) {
    String key = keys.keyOf(context)
        .map(String::trim)
        .filter(id -> !id.isEmpty())
        .orElseThrow(CallerIdentityMissingException::forStickySelection);
    synchronized (lock) {
      String remembered = bindings.get(key);
      if (remembered != null && candidates.contains(remembered)) {
        return remembered;
      }
      if (remembered != null) {
        // 惰性清理：那个实例已经不在这批候选里了（被关掉 / 被标签筛掉 / 被第 ③ 步排空）。
        // 不清理的话，一个下线的实例会占着这张表直到它被 LRU 挤掉。
        bindings.remove(key);
      }
      String chosen = delegate.select(candidates, context);
      bindings.put(key, chosen);
      evictLeastRecentlyUsedIfOverCapacity();
      return chosen;
    }
  }

  /** 挤掉最久没有被用过的键，直到容量回到上限之内。 */
  private void evictLeastRecentlyUsedIfOverCapacity() {
    while (bindings.size() > maxEntries) {
      Iterator<String> oldestFirst = bindings.keySet().iterator();
      oldestFirst.next();
      oldestFirst.remove();
    }
  }
}
