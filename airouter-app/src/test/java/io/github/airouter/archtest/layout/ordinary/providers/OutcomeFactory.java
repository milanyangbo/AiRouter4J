package io.github.airouter.archtest.layout.ordinary.providers;

import io.github.airouter.archtest.Outcome;

/**
  普通样例（R9 不该判它）：Provider 就是产生终态的那一处，造 {@code Outcome} 是它的本职。
 *
 * <p>它是「规则不误伤」那一半的证据：没有它，R9 的例外可能因为谓词写错而变成
 * 「谁都不许造」，而那种规则跑在真实模块上会立刻红 —— 也就是说，少了这个样例，
 * 一条写错的规则看起来会和一条写对的规则一样安静。
 */
public final class OutcomeFactory {

  public Outcome settled(String requestId) {
    return new Outcome(requestId, true);
  }
}
