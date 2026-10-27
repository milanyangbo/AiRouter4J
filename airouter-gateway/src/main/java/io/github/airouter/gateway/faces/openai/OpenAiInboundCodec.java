package io.github.airouter.gateway.faces.openai;

import io.github.airouter.core.CanonicalChoice;
import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.CanonicalTool;
import io.github.airouter.core.CanonicalUsage;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.GenerationParams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 入站 wire format 与 Canonical 之间的互转。
 *
 * <p>与 Provider 侧的 codec 是一对生而成对的 Knowns：两边各自维护「自己这一侧认识哪些字段」，
 * 谁也不引用谁。这样新增一个 Provider 时，这一份不受影响；反之亦然。
 *
 * <p>阶段 2 只有非流式这一半。流式事件的编码随票 03 进来 —— 那时它同样只回答
 * 「一个事件变成哪一帧的载荷」，不参与终态的判断。
 */
public final class OpenAiInboundCodec {

  /** 这一票只打通一个 Service，它是入站路径决定的，不来自请求。 */
  public static final String SERVICE_ID = "chat";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 入站请求里被显式建模的键；其余键进 passthrough 原样带到上游。 */
  private static final Set<String> CONSUMED_ON_READ =
      Set.of("model", "messages", "temperature", "top_p", "max_tokens", "stop", "stream", "tools");

  /** 单个消息里被显式建模的键。 */
  private static final Set<String> CONSUMED_IN_MESSAGE = Set.of("role", "content");

  private OpenAiInboundCodec() {
  }

  /**
   * 入站请求 → Canonical 请求。
   *
   * @param body 入站请求体
   * @param requestId 这一次请求的标识，由 Face 生成
   * @return Canonical 请求
   * @throws IllegalArgumentException 请求体不是合法 JSON 时抛出
   */
  public static CanonicalRequest decodeRequest(String body, String requestId) {
    JsonNode root = readTreeOrThrow(body);
    return new CanonicalRequest(
        requestId,
        SERVICE_ID,
        root.path("model").asString(""),
        decodeMessages(root.path("messages")),
        decodeTools(root.path("tools")),
        decodeParams(root),
        root.path("stream").asBoolean(false),
        passthroughOf(root, CONSUMED_ON_READ));
  }

  /**
   * Canonical 响应 → 客户端看到的 JSON。
   *
   * <p>{@code usage} 只在非 null 时出现。上游没有给用量时不补零 ——
   * 一个被编造出来的 0 会被拿去做成本核销，而事后没人能区分它是上游算的还是网关补的。
   *
   * @param response Canonical 响应
   * @return 响应体
   */
  public static String encodeResponse(CanonicalResponse response) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("id", response.id());
    root.put("object", "chat.completion");
    root.put("model", response.model());
    root.set("choices", encodeChoices(response.choices()));
    if (response.usage() != null) {
      root.set("usage", encodeUsage(response.usage()));
    }
    response.passthrough().forEach((key, rawJson) -> root.set(key, parseRaw(key, rawJson)));
    return root.toString();
  }

  /**
   * 错误的可携带形态，与 OpenAI 的错误结构一致。
   *
   * <p>出错时与成功时给出同一个形状，客户端就不需要两套错误处理路径 ——
   * 这是适配器层存在的意义：把内部的多样统一成客户端认识的一种。
   *
   * @param error 网关自己的错误
   * @return 响应体
   */
  public static String encodeError(GatewayError error) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("message", error.message());
    payload.put("type", error.type());
    payload.putNull("code");
    ObjectNode root = MAPPER.createObjectNode();
    root.set("error", payload);
    return root.toString();
  }

  private static ObjectNode encodeUsage(CanonicalUsage usage) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("prompt_tokens", usage.promptTokens());
    node.put("completion_tokens", usage.completionTokens());
    node.put("total_tokens", usage.totalTokens());
    usage.passthrough().forEach((key, rawJson) -> node.set(key, parseRaw(key, rawJson)));
    return node;
  }

  private static ArrayNode encodeChoices(List<CanonicalChoice> choices) {
    ArrayNode array = MAPPER.createArrayNode();
    for (CanonicalChoice choice : choices) {
      ObjectNode message = MAPPER.createObjectNode();
      message.put("role", choice.message().role().name().toLowerCase(Locale.ROOT));
      message.put("content", choice.message().content());
      ObjectNode node = MAPPER.createObjectNode();
      node.put("index", choice.index());
      node.set("message", message);
      node.put("finish_reason", choice.finishReason() == null ? null : choice.finishReason().raw());
      choice.passthrough().forEach((key, rawJson) -> node.set(key, parseRaw(key, rawJson)));
      array.add(node);
    }
    return array;
  }

  private static List<CanonicalMessage> decodeMessages(JsonNode node) {
    List<CanonicalMessage> messages = new ArrayList<>();
    if (!node.isArray()) {
      return List.of();
    }
    for (JsonNode element : node) {
      messages.add(new CanonicalMessage(
          decodeRole(element.path("role").asString("")),
          element.path("content").asString(""),
          passthroughOf(element, CONSUMED_IN_MESSAGE)));
    }
    return List.copyOf(messages);
  }

  private static List<CanonicalTool> decodeTools(JsonNode node) {
    List<CanonicalTool> tools = new ArrayList<>();
    if (!node.isArray()) {
      return List.of();
    }
    for (JsonNode element : node) {
      JsonNode function = element.path("function");
      JsonNode target = function.isObject() ? function : element;
      tools.add(new CanonicalTool(
          target.path("name").asString(""),
          target.path("description").asString(""),
          target.path("parameters").toString()));
    }
    return List.copyOf(tools);
  }

  /**
   * 角色容错：客户端送来未知角色时按 USER 处理。
   *
   * <p>网关不是 validator。客户端写错角色的代价应当由上游的响应来体现，
   * 而不是在这里被翻译成一个 400 —— 后者会把「上游不支持这种用法」变成「这个网关很挑剔」。
   *
   * @param raw 客户端给出的角色
   * @return Canonical 角色
   */
  private static CanonicalRole decodeRole(String raw) {
    switch (raw.toLowerCase(Locale.ROOT)) {
      case "assistant":
        return CanonicalRole.ASSISTANT;
      case "system":
        return CanonicalRole.SYSTEM;
      case "tool":
        return CanonicalRole.TOOL;
      case "user":
        return CanonicalRole.USER;
      default:
        return CanonicalRole.USER;
    }
  }

  private static GenerationParams decodeParams(JsonNode root) {
    JsonNode stop = root.path("stop");
    List<String> stops = new ArrayList<>();
    if (stop.isArray()) {
      stop.forEach(element -> stops.add(element.asString("")));
    } else if (stop.isString()) {
      stops.add(stop.asString());
    }
    return new GenerationParams(
        numberOrNull(root.path("temperature")),
        numberOrNull(root.path("top_p")),
        root.has("max_tokens") ? root.path("max_tokens").asInt(0) : null,
        List.copyOf(stops));
  }

  private static Double numberOrNull(JsonNode node) {
    return node.isNumber() ? node.asDouble(0.0d) : null;
  }

  private static JsonNode parseRaw(String key, String rawJson) {
    try {
      return MAPPER.readTree(rawJson);
    } catch (JacksonException expected) {
      return MAPPER.createObjectNode().put(key + "_unparsed", rawJson);
    }
  }

  private static JsonNode readTreeOrThrow(String body) {
    try {
      return MAPPER.readTree(body == null ? "" : body);
    } catch (JacksonException failure) {
      throw new IllegalArgumentException("请求体不是合法 JSON", failure);
    }
  }

  private static Map<String, String> passthroughOf(JsonNode node, Set<String> consumed) {
    Map<String, String> passthrough = new LinkedHashMap<>();
    for (String field : node.propertyNames()) {
      if (!consumed.contains(field)) {
        passthrough.put(field, node.path(field).toString());
      }
    }
    return Map.copyOf(passthrough);
  }
}
