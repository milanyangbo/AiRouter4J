package io.github.airouter.gateway.routing;

import java.util.Set;

/**
 * 一次调用交给选择链的那份输入：**① 调用方标识** 与 **② 要求的标签**。
 *
 * <p>它是 gateway 的概念，不在 core 里：`CanonicalRequest` 描述的是「要生成什么」，
 * 而这里描述的是「这次调用算到谁头上」—— 后者是治理的输入，不是模型调用的输入。
 *
 * <p>**它不含「是否流式」**：那一项一旦进来，「流式走另一条链」就有了一个合法的入口，
 * 而那正是来源项目最贵的缺陷的成因（ADR-0011 第 5 节末尾那两条）。
 *
 * @param callerId 调用方标识，第 ① 步的产物。**可以为空**：来源用尽时就是空，
 *                 链**只取不校验** —— 认证与租户属阶段 6（ADR-0018），
 *                 接上之后来源换成认证结果，链不变。粘滞与两个 hash 策略读它，
 *                 缺失时它们的行为是显式的、被断言的（票 09 / 票 10）
 * @param requiredTags 候选必须**同时**带齐的标签。为空表示不筛。
 *                     阶段 8 之前它来自服务级配置，不是规则匹配的结果（ADR-0012）
 */
public record SelectionContext(String callerId, Set<String> requiredTags) {

  /**
   * 标签在这里封成不可变集合：它会被第 ② 步反复读，谁都不该改得动同一份。
   */
  public SelectionContext {
    requiredTags = requiredTags == null ? Set.of() : Set.copyOf(requiredTags);
  }

  /** 交出去的那一份同样是不可变的：读它的人不该拿到一个能改的集合。 */
  @Override
  public Set<String> requiredTags() {
    return Set.copyOf(requiredTags);
  }

  /** 调用方标识**必须存在**时走这里：缺了就拒绝，不退回到「当它不存在」。
   *
   * <p>两种要用标识的东西（粘滞与两个 hash 策略）对缺标识的处置完全不同：
   * 粘滞会说「带上标识，或者把粘滞关掉」，hash 会说「带上标识，或者换一个策略」。
   * 因此**措辞由调用方给**（{@code consumer}），这里只负责判有没有、并把结论交给
   * {@link CallerIdentityMissingException}。
   *
   * @param consumer 谁要这个标识：通常是策略在配置里的名字
   * @return 调用方标识，非空白
   * @throws CallerIdentityMissingException 标识缺失或全是空白时抛出。
   *     这是**显式失败**，不是退化：退化会让本该稳定的东西静默地不再稳定
   */
  public String requireCallerId(String consumer) {
    if (callerId == null || callerId.isBlank()) {
      throw CallerIdentityMissingException.requiredBy(consumer);
    }
    return callerId;
  }

  /** 没有调用方标识、不要求标签的那一份。 */
  public static SelectionContext withoutCaller() {
    return new SelectionContext(null, Set.of());
  }

  /** 只要调用方标识，不筛标签。 */
  public static SelectionContext of(String callerId) {
    return new SelectionContext(callerId, Set.of());
  }

  /** 调用方标识加上要求带齐的标签。 */
  public static SelectionContext of(String callerId, Set<String> requiredTags) {
    return new SelectionContext(callerId, requiredTags);
  }
}
