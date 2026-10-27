package io.github.airouter.gateway.transport;

import java.util.List;

/**
 * 一种「把上游字节切成帧」的读法。
 *
 * <p>SSE 与逐行 JSON 是**并列的两种读法**，不是同一个读法的两种参数：前者以空行分隔、
 * 以哨兵结束；后者一行一个完整 JSON、靠末帧内容宣告说完。用 SSE 的读法去读逐行 JSON，
 * 一条事件都产不出来（ADR-0006 第 3 节）。所以多一种读法就是多一个实现，
 * 不是给某一个实现加一个开关。
 *
 * <p>接口放在 {@code transport}，实现由**形态**挑选（{@code providers} 的 {@code UpstreamShape}
 * 自述「流怎么结束」）。方向只能是这样：结构规则 R5 不允许 {@code transport} 回头去认识
 * {@code providers}，所以形态把「怎么造一个分帧器」交给调用方，而不是让管道自己去问形态是谁。
 *
 * <p>实例是**一次订阅一个**：它带着「上一行没攒完」的状态，两次调用共享一个实例会互相污染对方的半行。
 */
public interface UpstreamFramer {

  /**
   * 喂入一块字节。块的边界与帧的边界没有任何关系，因此一块可能带来零帧、一帧或多帧。
   *
   * @param chunk 这一块字节，长度可以为 0
   * @return 这一块之后**已经完整**的帧
   */
  List<UpstreamFrame> push(byte[] chunk);

  /**
   * 流结束了。
   *
   * @return 残留在缓存里的、仍然完整的帧。没有以分隔符收尾的那一段**不被分派**：
   *         SSE 里一个事件要等到空行才成立，连接在中途断掉时那段内容是不完整的，
   *         把它当成一帧等于把半截响应当成完整的
   */
  List<UpstreamFrame> finish();
}
