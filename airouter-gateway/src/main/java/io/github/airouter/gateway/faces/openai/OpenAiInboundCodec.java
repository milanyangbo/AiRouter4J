package io.github.airouter.gateway.faces.openai;

import io.github.airouter.core.CanonicalChoice;
import io.github.airouter.core.CanonicalFinishReason;
import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.CanonicalStreamEvent;
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
 */
public final class OpenAiInboundCodec {

  /** 这一票只打通一个 Service，它是入站路径决定的，不来自请求。 */
  public static final String SERVICE_ID = "chat";

  /**
   * 告诉客户端「这条流结束了」的哨兵载荷。
   *
   * <p>它与上游那一个同名但不是同一个东西：上游的哨兵是上游说的话，
   * 这一个是我们对客户端说的话。中间隔着一整套终态判断（ADR-0006），
   * 因此它不是「把上游的哨兵转过来」，而是「在我们确认有终态之后才发出」。
   */
  public static final String DONE_SENTINEL = "[DONE]";

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
   * 一个流式事件 → 客户端那一帧的 {@code data} 载荷。
   *
   * <p>{@code id / model / created} 由调用方给出而不是从事件里取：它们属于整条流，
   * 只有 {@code Opening} 带着它们，而后续的每一帧都要带上。
   * 这个分工让事件模型不必在每一个事件上重复这三个字段。
   *
   * <p>返回 null 表示这一帧不产生任何输出。两种情况：
   * <ul>
   *   <li>{@code Opening} —— 它只是把身份交给调用方，本身不构成客户端看到的一帧；
   *   <li>{@code Ping} —— 上游的保活注释**不转发**。它被转发之后就不再是可以忽略的注释了：
   *       SSE 的写出把这一种帧与数据帧区分开，而客户端没有理由去解析它。
   *       上游保活的意义在于维持它自己那一条连接，那条连接的生死由网关的空闲超时判断，
   *       不需要客户端参与。
   * </ul>
   *
   * <p>分发写成穷尽的模式匹配而不是一串 {@code instanceof}：漏掉一个事件类型的后果
   * 是「某一条流少了一帧」，而那一类缺陷不会报错。穷尽匹配让它变成编译错误。
   *
   * @param event 流式事件
   * @param id 流标识
   * @param model 模型名
   * @param created 创建时间，上游没给时为 null
   * @return 那一帧的载荷；null 表示这一帧不产出
   */
  public static String encodeStreamEvent(CanonicalStreamEvent event,
                                         String id,
                                         String model,
                                         Long created) {
    return switch (event) {
      case CanonicalStreamEvent.Opening opening -> null;
      case CanonicalStreamEvent.Ping ping -> null;
      case CanonicalStreamEvent.TextDelta delta ->
          chunk(id, model, created, delta.index(), delta.text(), null, null);
      case CanonicalStreamEvent.Usage usage -> usageFrame(id, model, created, usage.usage());
      case CanonicalStreamEvent.Terminal terminal ->
          chunk(id, model, created, 0, null, terminal.reason(), terminal.usage());
      case CanonicalStreamEvent.Failed failed -> encodeError(failed.error());
    };
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

  /**
   * 一个增量帧或终态帧。
   *
   * <p>{@code finish_reason} 在非终态帧上是显式的 null 而不是缺席：客户端（含官方 SDK）
   * 按这个字段判断一条流是否结束，缺席与 null 在它们那里的处理并不一致。
   */
  private static String chunk(String id,
                              String model,
                              Long created,
                              int index,
                              String content,
                              CanonicalFinishReason reason,
                              CanonicalUsage usage) {
    ObjectNode root = frame(id, model, created);
    ObjectNode delta = MAPPER.createObjectNode();
    if (content != null) {
      delta.put("content", content);
    }
    ObjectNode choice = MAPPER.createObjectNode();
    choice.put("index", index);
    choice.set("delta", delta);
    if (reason == null) {
      choice.putNull("finish_reason");
    } else {
      choice.put("finish_reason", reason.raw());
    }
    ArrayNode choices = MAPPER.createArrayNode();
    choices.add(choice);
    root.set("choices", choices);
    if (usage != null) {
      root.set("usage", encodeUsage(usage));
    }
    return root.toString();
  }

  /**
   * 纯用量帧：{@code choices} 是空的。
   *
   * <p>它对应上游在流末尾单独发一帧用量的做法。形态上必须与「带内容的帧」区分开，
   * 否则客户端会把一个没有增量的帧当成一次内容更新。
   */
  private static String usageFrame(String id, String model, Long created, CanonicalUsage usage) {
    ObjectNode root = frame(id, model, created);
    root.set("choices", MAPPER.createArrayNode());
    root.set("usage", encodeUsage(usage));
    return root.toString();
  }

  private static ObjectNode frame(String id, String model, Long created) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("id", id == null ? "" : id);
    root.put("object", "chat.completion.chunk");
    if (created != null) {
      root.put("created", created.longValue());
    }
    root.put("model", model == null ? "" : model);
    return root;
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
      stop.forEach(element -> stops.add(element.asString()));
    } else if (stop.isTextual()) {
      stops.add(stop.asString());
    }
    return new GenerationParams(
        numberOrNull(root.path("temperature")),
        numberOrNull(root.path("top_p")),
        root.has("max_tokens") ? root.path("max_tokens").asInt() : null,
        List.copyOf(stops));
  }

  private static Double numberOrNull(JsonNode node) {
    return node.isNumber() ? node.asDouble() : null;
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
