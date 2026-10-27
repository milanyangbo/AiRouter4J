package io.github.airouter.gateway.routing;

import java.util.Set;

/**
 * 一个**可挑选的候选**：第 ③ 步筛完之后剩下的那一个实例，在这一个包里的形态。
 *
 * <p>它是候选而不是注册表里的实例，理由是这一层的可测性：
 * 注册表里的实例带着一个**能出网的对象**，而「判选择对不对」不该先造出一个能出网的东西。
 * 于是这里只留选择要用到的三项：它是谁、它多重、它带哪些标签。
 * 选中之后由调用方（{@code config}）按 id 取回实例 —— 那是唯一一处知道「实例是什么」的地方。
 *
 * @param id 实例 id。它是这一层与注册表之间唯一的接口：选中之后按它取回实例
 * @param weight 权重。它是第 ④ 步加权展开的除数，所以进来之前已经验过是正数
 * @param tags 标签。第 ② 步按它筛；进来之前已被封成不可变集合
 */
public record Candidate(String id, int weight, Set<String> tags) {

  /**
   * @throws IllegalArgumentException id 缺失或权重不是正数时抛出。
   *     权重为 0 或负数在加权展开那一步会表现为「这个实例永远选不到」，
   *     而那种表现与「它坏了」在观测上无法区分 —— 所以在这里就不是一个合法候选
   */
  public Candidate {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("候选必须有 id：选中之后要按它取回实例，没有 id 的候选选不出来");
    }
    if (weight <= 0) {
      throw new IllegalArgumentException(
          "候选 " + id + " 的权重必须为正数，收到的是 " + weight + "：它是加权展开的除数");
    }
    tags = tags == null ? Set.of() : Set.copyOf(tags);
  }

  /**
   * 是否带齐给定标签。
   *
   * @param required 要哪些标签；空集表示「不筛」
   * @return 带齐了就是 true
   */
  public boolean carriesAll(Set<String> required) {
    return tags.containsAll(required);
  }
}
