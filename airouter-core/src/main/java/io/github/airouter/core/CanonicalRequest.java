package io.github.airouter.core;

import java.util.List;
import java.util.Map;

/**
 * 网关内部的请求中间表示（ADR-0005）。
 *
 * <p>所有 Face 都要先译到这里，所有 Provider 只接受这里的形状，翻译路径因此是 N+M 而不是 N×M。
 *
 * @param requestId 这一次请求的标识，用于排查。由 Face 生成，成功与失败两条路径都会带着它出境
 * @param serviceId 目标 Service 名，例如 chat
 * @param model 请求指定的模型名
 * @param messages 对话消息
 * @param tools 可用工具声明
 * @param params 网关要理解的那部分生成参数
 * @param stream 是否请求流式响应
 * @param passthrough 未被建模的入站字段，值是 JSON 原文。
 *                    它存在的理由是厂商协议里总有网关不必理解但必须转发的字段；
 *                    没有这个入口，这些字段会被塞进某个意思不符的字段里，然后再也取不出来
 */
public record CanonicalRequest(
    String requestId,
    String serviceId,
    String model,
    List<CanonicalMessage> messages,
    List<CanonicalTool> tools,
    GenerationParams params,
    boolean stream,
    Map<String, String> passthrough) {

  /**
   * 容错归一化。
   *
   * <p>上游与客户端都不保证字段齐全。缺字段在这里收敛成空的列表与映射，
   * 而不是让每一个消费者各自判断一次 null。
   */
  public CanonicalRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    tools = tools == null ? List.of() : List.copyOf(tools);
    params = params == null ? GenerationParams.EMPTY : params;
    passthrough = passthrough == null ? Map.of() : Map.copyOf(passthrough);
  }
}
