package io.github.airouter.gateway.select;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 第 ④ 步：权重 → 区间，取值一次二分查找。
 *
 * <p>这里守的是**两段之间的边界**。权重 3 : 1 的中段（落在第二个候选里但还没到边界）
 * 谁都会写对，写错的是边界：区间是「含下界、不含上界」，
 * 于是正好等于某个上界的取值属于**下一段**。`Arrays.binarySearch` 命中时返回的是下标本身，
 * 忘了 `+ 1` 的表现是权重为 3 的那一段只拿到 2/4 —— 流量看起来只是稍微不均，
 * 而它是彻底的、可复现的错。第一条断言就是冲它去的。
 */
@Tag("unit")
final class WeightedCandidatesTest {

  @Test
  @DisplayName("正好等于某个上界的取值属于下一段：区间不含上界（这一处曾经写错过）")
  void upperBoundBelongsToTheNextSegment() {
    WeightedCandidates weighted = WeightedCandidates.of(List.of(
        new Candidate("a", 3, Set.of()), new Candidate("b", 1, Set.of())));

    assertThat(weighted.total()).isEqualTo(4L);
    assertThat(ids(weighted, 0, 1, 2, 3, 4, 5, 6, 7))
        .as("八次 = 两整轮，权重 3:1 应当恰好是 6:2")
        .containsExactly("a", "a", "a", "b", "a", "a", "a", "b");
  }

  @Test
  @DisplayName("极大的权重不会被展开成列表：1000:1 仍然是两个候选、总长 1001")
  void largeWeightsAreNotExpandedIntoLists() {
    WeightedCandidates weighted = WeightedCandidates.of(List.of(
        new Candidate("big", 1000, Set.of()), new Candidate("small", 1, Set.of())));

    assertThat(weighted.candidates()).hasSize(2);
    assertThat(weighted.total()).isEqualTo(1001L);
    assertThat(weighted.pick(1000L).id()).isEqualTo("small");
    assertThat(weighted.pick(999L).id()).isEqualTo("big");
  }

  @Test
  @DisplayName("极大的权重相加不溢出也不截断：两个 int 上限之和仍然是准的那个数")
  void extremeWeightsAreNotTruncated() {
    WeightedCandidates weighted = WeightedCandidates.of(List.of(
        new Candidate("huge", Integer.MAX_VALUE, Set.of()),
        new Candidate("also-huge", Integer.MAX_VALUE, Set.of())));

    // 截断成 int 的话这里会变成 -2；溢出成别的值则根本对不上。
    assertThat(weighted.total()).isEqualTo(2L * Integer.MAX_VALUE);
    assertThat(weighted.pick(Integer.MAX_VALUE - 1L).id()).isEqualTo("huge");
    assertThat(weighted.pick((long) Integer.MAX_VALUE).id()).isEqualTo("also-huge");
  }

  @Test
  @DisplayName("候选为空时报错：这里拿着长度为 0 的区间继续走只会除零")
  void emptyCandidatesAreRejected() {
    assertThatThrownBy(() -> WeightedCandidates.of(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("候选不能为空");
  }

  @Test
  @DisplayName("只有一个候选时，任何取值都落在它身上（含负数 —— 归一化到区间内）")
  void singleCandidateWinsEveryDraw() {
    WeightedCandidates weighted = WeightedCandidates.of(List.of(new Candidate("only", 7, Set.of())));

    for (long draw : new long[] {0L, 6L, 7L, 13L, -1L, -7L}) {
      assertThat(weighted.pick(draw).id()).as("取值为 %d 时", draw).isEqualTo("only");
    }
  }

  @Test
  @DisplayName("标签原样带上：它是第 ② 步的筛子，候选不解释它")
  void tagsAreCarriedAsTheyAre() {
    Candidate candidate = new Candidate("a", 1, Set.of("gpu:a100"));

    assertThat(candidate.tags()).containsExactly("gpu:a100");
  }

  private static List<String> ids(WeightedCandidates weighted, long... draws) {
    List<String> picked = new ArrayList<>();
    for (long draw : draws) {
      picked.add(weighted.pick(draw).id());
    }
    return picked;
  }
}
