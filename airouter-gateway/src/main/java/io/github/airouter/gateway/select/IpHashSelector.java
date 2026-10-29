package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;

/**
 * 按调用方标识哈希：**同一个调用方落在同一个实例上**（ADR-0011 第 2 节 (a)）。
 *
 * <p>落点是 {@code hash(callerId)} 归一化到第 ④ 步的那个区间里 ——
 * 它**不自建一套加权**，而是与随机抽签、轮询光标共用同一次加权。
 * 来源项目里每个策略各有一份加权算法，而实测只有轮询真的接了那一份 ——
 * 「配了权重却根本没生效」就是这样长出来的。
 *
 * <p>共用加权的另一个好处：**权重在这个策略里也是真的**。
 * 权重 3:1 时，足够多的调用方里有约 3/4 落在权重为 3 的那一个上 ——
 * 一条可以被断言的性质，而不用诉诸「哈希嘛差不多就行」。
 *
 * <p>**拿不到调用方标识时它报错**（{@code CallerIdentityMissingException}），
 * 不退回随机、也不退回第一个候选：这两种退化的后果是「同一个调用方被拆到若干实例上」，
 * 而在表面上它一路表现得像是正常工作 —— 日志干净，只是会话缓存在几台机器上各建各的。
 */
public final class IpHashSelector implements InstanceSelector {

  /**
   * @param candidates 第 ④ 步交出来的加权候选
   * @param context 第 ① ② 步的产物；只有第 ① 步的调用方标识进去了
   * @return 落点所在的那个候选
   */
  @Override
  public String select(WeightedCandidates candidates, SelectionContext context) {
    // 名字取自唯一的定义处（SelectionStrategy）：第二个字符串来源会让「改了名字只改一边」无人发现。
    String callerId = context.requireCallerId(SelectionStrategy.IP_HASH.id());
    return candidates.pick(StableHash.of(callerId)).id();
  }
}
