package io.github.airouter.archtest;

/**
  样例用的结算结论类型。

  <p>真实项目里它是 `io.github.airouter.core.Outcome`（阶段 1）。这里先立一个同名类型，
  是为了让 R9 —— 「只有产生终态的那一处可以构造它」 —— 在 core 落地之前就有东西可守：
  规则按类型名认它（见 StructuralRules#settlementHasOneExit），所以两者可以互换而不必改规则。
 */
public final class Outcome {

  private final String requestId;

  private final boolean settled;

  public Outcome(String requestId, boolean settled) {
    this.requestId = requestId;
    this.settled = settled;
  }

  public String requestId() {
    return requestId;
  }

  public boolean settled() {
    return settled;
  }
}
