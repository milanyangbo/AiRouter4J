package io.github.airouter.gateway.providers;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游帧被 JSON 解析的次数。
 *
 * <p>它存在的唯一理由是 ADR-0005 第 3 节的 CHUNK_PARSE_COUNT=1：同一份上游字节
 * 在一次处理流程中只允许被解析一次。没有计数的时候，「解析了几次」这件事
 * 只能靠读代码判断，而它偏偏是那种改一个需求就会被悄悄改成两次的东西
 * （新增一个消费者，各自 readTree 一次，两处都只取自己需要的几个字段）。
 *
 * <p>计数放在解析发生的那一侧而不是事后统计：事后统计不出来 ——
 * 解析是一次函数调用，不留下任何可供观察的痕迹。
 */
public final class ChunkParseCounter {

  private final AtomicLong parsed = new AtomicLong();

  /** 记一次解析。调用它的一方必须刚刚解析完一份字节，且这份字节此后不再被解析。 */
  public void countParsedFrame() {
    parsed.incrementAndGet();
  }

  /** 到目前为止被解析过的帧数。 */
  public long parsedFrames() {
    return parsed.get();
  }
}
