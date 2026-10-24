package io.github.airouter.gateway.transport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 正在对着上游打开的流有几条。
 *
 * <p>它存在的理由只有一个：**「调用方中途断开之后资源有没有收回来」这件事必须能被看见。**
 * 一条流在客户端已经走了之后还开着，表现为连接与线程两头都挂着，
 * 而这类残留不会体现在任何一次响应的内容里 —— 取消的那次调用连结果都不会有人看。
 * 因此它只能靠一个计数来暴露：开出去的条数减去收回来的条数。
 *
 * <p>计数放在 transport 而不是别处，是因为只有这里知道流的开与收发生在什么时刻：
 * 订阅开始即开出，{@code doFinally} 的三种信号（完成、出错、取消）任何一种到达即收回。
 * 交给调用方去记，就会漏掉取消那一种 —— 而那正是要盯的那一种。
 *
 * <p>它是**在途条数**而不是「连接数」：底层连接池的复用与回收不归本类管，
 * 也不该由本类推断。断言「没有随取消次数增长的残留」用的是它，
 * 而这条断言保护的是「每一次订阅都对应一次收回」，不是「socket 没泄漏」。
 */
public final class InFlightStreams {

  private final AtomicLong opened = new AtomicLong();

  private final AtomicLong closed = new AtomicLong();

  /** 一条流开出了。 */
  public void streamOpened() {
    opened.incrementAndGet();
  }

  /** 一条流收回来了。完成、出错、取消三种信号都会走到这里，不许漏掉任何一种。 */
  public void streamClosed() {
    closed.incrementAndGet();
  }

  /** 累计开出过多少条。 */
  public long opened() {
    return opened.get();
  }

  /** 累计收回了多少条。 */
  public long closed() {
    return closed.get();
  }

  /** 此刻还开着的条数。稳定状态下它应当回到零，且**不随调用次数增长**。 */
  public long inFlight() {
    return opened.get() - closed.get();
  }
}
