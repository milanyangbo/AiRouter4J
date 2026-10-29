package io.github.airouter.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 流式的 SLO 三角（ADR-0005 第 4 节与票 03）。
 *
 * <p>三条腿缺一条，流式就是「看起来能用、出事时说不清」：
 *
 * <ul>
 *   <li>**时间**：{@code idleTimeout} —— 两块之间最多静默多久
 *   <li>**内容**：{@code maxContentChars} —— 一条流最多累积多少内容
 *   <li>**字节缓冲**：{@code maxFrameBytes} / {@code inboundBufferBytes} / {@code outboundBufferBytes}
 *       —— 一帧、入站请求体、出站响应体各自最多允许多少字节进内存
 * </ul>
 *
 * <p>三者**全部显式配置**，一个都不掉进默认值：留空让它掉进某个默认值，
 * 等于把「一条失控的流最多能吃掉多少」变成一件只有看过实现才知道的事。
 * 校验放在构造器里 —— 与配置模型其余各处（{@link ServicesProperties}、
 * {@link io.github.airouter.gateway.config.InstanceDefinition InstanceDefinition}）同一理由：
 * 配错属于「能不能起来」的问题，等第一次调用才炸就太晚了；报错指名环境变量，
 * 运维看的是环境，不是属性树。
 *
 * @param idleTimeout **块间**时长，不是总时长。一条想很久但一直在说话的流不该被误杀，
 *                    而一条收了连接就不再出声的流该被判超时
 * @param maxContentChars 单条流累积内容的字符上限。超出之后不再往下发内容，但流继续走到终态 ——
 *                        截断只影响展示，用量取上游回报（ADR-0005 第 4 节）
 * @param maxFrameBytes 单帧上限：一个换行之前最多允许多少字节。它是唯一能在解码之前
 *                      拦住「一个不发换行的上游」的东西
 * @param inboundBufferBytes 入站缓冲区上限：客户端请求体最多允许多少字节进内存
 * @param outboundBufferBytes 出站缓冲区上限：一次上游响应（非流式那一条路）最多允许多少字节进内存。
 *                            流式不聚合，因此不受它约束 —— 那正是流式该有的样子
 */
@ConfigurationProperties(prefix = StreamProperties.PREFIX)
public record StreamProperties(
    Duration idleTimeout,
    int maxContentChars,
    int maxFrameBytes,
    int inboundBufferBytes,
    int outboundBufferBytes) {

  /** 属性前缀。写在常量里，让 yml、测试与出错信息引用的是同一个字符串。 */
  public static final String PREFIX = "airouter.stream";

  /** idle-timeout 的环境变量名。 */
  public static final String IDLE_TIMEOUT_ENV = "AIROUTER_STREAM_IDLE_TIMEOUT";

  /** max-content-chars 的环境变量名。 */
  public static final String MAX_CONTENT_ENV = "AIROUTER_STREAM_MAX_CONTENT_CHARS";

  /** max-frame-bytes 的环境变量名。 */
  public static final String MAX_FRAME_ENV = "AIROUTER_STREAM_MAX_FRAME_BYTES";

  /** inbound-buffer-bytes 的环境变量名。 */
  public static final String INBOUND_BUFFER_ENV = "AIROUTER_STREAM_INBOUND_BUFFER_BYTES";

  /** outbound-buffer-bytes 的环境变量名。 */
  public static final String OUTBOUND_BUFFER_ENV = "AIROUTER_STREAM_OUTBOUND_BUFFER_BYTES";

  /**
   * @throws IllegalArgumentException 任一项缺失或不是正数时抛出。后果是**启动失败**：
   *     一条没有上限的流是「将来会出事」而不是「现在能用」，
   *     让它起来等于把一个 SLO 决定推迟到故障现场去做
   */
  public StreamProperties {
    requirePositiveDuration(idleTimeout, IDLE_TIMEOUT_ENV);
    requirePositive(maxContentChars, MAX_CONTENT_ENV);
    requirePositive(maxFrameBytes, MAX_FRAME_ENV);
    requirePositive(inboundBufferBytes, INBOUND_BUFFER_ENV);
    requirePositive(outboundBufferBytes, OUTBOUND_BUFFER_ENV);
  }

  private static void requirePositiveDuration(Duration value, String env) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException("环境变量 " + env + " 必须是正数，收到的是 " + value);
    }
  }

  private static void requirePositive(int value, String env) {
    if (value <= 0) {
      throw new IllegalArgumentException("环境变量 " + env + " 必须是正整数，收到的是 " + value);
    }
  }
}
