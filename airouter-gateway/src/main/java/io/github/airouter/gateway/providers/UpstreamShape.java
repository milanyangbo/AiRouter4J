package io.github.airouter.gateway.providers;

import io.github.airouter.gateway.transport.SseFramer;
import io.github.airouter.gateway.transport.UpstreamFramer;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * 上游说哪一种**协议**（GLOSSARY「形态」）。它不是厂商：形态相同而厂商不同是配置的事，不是类的事
 * （ADR-0006 第 1 节 —— legacy 里 9 个厂商长出 9 个类，其中 6 个是同一套 OpenAI 兼容代码只改了默认地址）。
 *
 * <p>每一个取值自己回答三件事，别处不得替它回答：
 *
 * <ol>
 *   <li>**地址该长什么样** —— 且是**启动期校验**，不是约定。Spring AI 2.x 不再自动补 {@code /v1} 后缀，
 *       少了它不在启动期报错，只会变成一次深入运行期的 404
 *   <li>**这个协议要不要凭证** —— Ollama 原生不需要，其余需要
 *   <li>**流怎么结束** —— 经由各自的**分帧器**：SSE（{@code data:} + 空行 + {@code [DONE]} 哨兵）
 *       还是逐行 JSON（末帧 {@code done:true}）
 * </ol>
 *
 * <p>第 3 条以「怎么造一个分帧器」的形式给出，而不是返回一个形态枚举让管道自己挑：
 * 分帧器住在 {@code transport}，而结构规则 R5 不允许 {@code transport} 认识 {@code providers} ——
 * 于是形态把构造器交出去，管道那边仍然只看见一个函数。
 *
 * <p>措辞（错误文案、以及「什么算异常结束」的解释）同样归形态，
 * 不写在共享的错误映射里：历史上为了改一处文案动了共享映射，
 * 结果另一个形态的 golden 基线一起变红（ADR-0006 第 4 节）。
 *
 * <p>取值**只能由配置显式给出**。同一台本机 Ollama 上 {@code /v1}（OpenAI 兼容）与
 * {@code /api}（原生）两种地址都在，按地址猜必然猜错 —— 这也正是形态这一位必须由人说出来的理由。
 */
public enum UpstreamShape {

  /**
   * OpenAI 兼容。所有 OpenAI 兼容厂商共用这一个取值，差别写在配置里。
   *
   * <p>地址以 {@code /v1} 结尾且不带尾斜杠；需要凭证；流式是 SSE，以 {@code data: [DONE]} 结束。
   */
  OPENAI("/v1", true, SseFramer::new, "以 data: [DONE] 哨兵结束；上游没发哨兵就收了连接不算说完");

  private final String baseUrlSuffix;

  private final boolean credentialRequired;

  private final IntFunction<UpstreamFramer> framerFactory;

  private final String streamEnding;

  UpstreamShape(String baseUrlSuffix, boolean credentialRequired,
      IntFunction<UpstreamFramer> framerFactory, String streamEnding) {
    this.baseUrlSuffix = baseUrlSuffix;
    this.credentialRequired = credentialRequired;
    this.framerFactory = framerFactory;
    this.streamEnding = streamEnding;
  }

  /** 这个形态的地址必须以什么结尾。带不带尾斜杠由 {@link #requireValidBaseUrl} 一起判。 */
  public String baseUrlSuffix() {
    return baseUrlSuffix;
  }

  /** 这个协议要不要凭证。不需要的那一种（Ollama 原生）就不该逼着运维去申请一个用不上的密钥。 */
  public boolean requiresCredential() {
    return credentialRequired;
  }

  /** 按单帧上限造一个分帧器。它是「流怎么结束」这一问的答案，也是唯一该存在形态差异的地方。 */
  public IntFunction<UpstreamFramer> framerFactory() {
    return framerFactory;
  }

  /** 「什么算异常结束」的解释。它面向运维与诊断，不是面向客户端的文案。 */
  public String describeStreamEnding() {
    return streamEnding;
  }

  /**
   * 地址形态的**启动期校验**。
   *
   * @param baseUrl 配置给出来的地址
   * @throws IllegalArgumentException 地址不以 {@link #baseUrlSuffix()} 结尾、或以斜杠结尾时抛出。
   *     出错信息**指名收到的那个值**：运维看的是环境，一句「地址不合法」要让人再去猜一遍自己配了什么
   */
  public void requireValidBaseUrl(String baseUrl) {
    if (baseUrl.endsWith("/")) {
      throw new IllegalArgumentException("形态 " + id() + " 的地址不能以斜杠结尾，收到的是 " + baseUrl);
    }
    if (!baseUrl.endsWith(baseUrlSuffix)) {
      throw new IllegalArgumentException("形态 " + id() + " 的地址必须以 " + baseUrlSuffix
          + " 结尾，收到的是 " + baseUrl + "。少了这个后缀不会在启动期报错，只会变成一次运行期的 404");
    }
  }

  /** 配置里写这个名字。小写，与 {@code application.yml} 里的取值一致。 */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * 按配置里给出的名字取值。**不认的地址一律报错**，不做任何猜测或兜底 ——
   * 猜错形态的代价是一整条流一条事件都产不出来，而不是报错。
   *
   * @param raw 配置里的原文
   * @return 对应的形态
   * @throws IllegalArgumentException 名字缺失或不认识时抛出，并列出合法取值
   */
  public static UpstreamShape of(String raw) {
    String trimmed = raw == null ? "" : raw.trim();
    for (UpstreamShape shape : values()) {
      if (shape.id().equals(trimmed.toLowerCase(Locale.ROOT))) {
        return shape;
      }
    }
    throw new IllegalArgumentException("不认识的形态：" + (trimmed.isEmpty() ? "(空)" : trimmed)
        + "；本阶段支持的取值是 " + Arrays.stream(values())
            .map(UpstreamShape::id)
            .collect(Collectors.joining(" / ")));
  }
}
