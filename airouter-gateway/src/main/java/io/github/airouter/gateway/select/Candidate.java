package io.github.airouter.gateway.select;

import java.util.Set;

/**
 * 一个候选实例：链挑的那一种东西。
 *
 * <p>它是**候选**，不是注册表里的 {@code RegisteredInstance}：后者带着能出网的对象，
 * 而判「选择对不对」不该先造出一个能出网的东西 —— 那一步与选择毫无关系，
 * 却会让每一条断言都得先搭一次出站管道。
 *
 * <p>它也不等于 {@code ModelInstance}（GLOSSARY 的那个域词）：后者是配置与运行时共同的身份，
 * 带着地址、凭证、形态；这里只留下选择要用到的三项。
 *
 * @param id 实例 id
 * @param weight 权重。第 ④ 步拿它做除数，所以进来之前已经验过是正数
 * @param tags 标签。第 ② 步按它筛
 */
public record Candidate(String id, int weight, Set<String> tags) {

  /**
   * @throws IllegalArgumentException id 为空，或权重不是正数时抛出，并**指名收到的那个值**
   */
  public Candidate {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("候选的 id 不能为空：它是区分同一个 Service 下两个候选的唯一东西");
    }
    if (weight <= 0) {
      throw new IllegalArgumentException(
          "候选 " + id + " 的权重必须为正数，收到的是 " + weight + "：它是加权区间长度的除数");
    }
    // 标签会被第 ② 步反复读，封成不可变集合。
    tags = tags == null ? Set.of() : Set.copyOf(tags);
  }

  /** 只带 id 与权重的候选，没有标签。 */
  public static Candidate of(String id, int weight) {
    return new Candidate(id, weight, Set.of());
  }
}
