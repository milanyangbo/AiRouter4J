package io.github.airouter.gateway.providers.openai;

import io.github.airouter.core.CanonicalChoice;
import io.github.airouter.core.CanonicalFinishReason;
import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.CanonicalTool;
import io.github.airouter.core.CanonicalUsage;
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
 * Canonical 与 OpenAI 兼容 wire format 之间的互转。
 *
 * <p>纯函数集合：进出都是字符串与对象，没有任何 IO。因此它可以在没有容器、没有网络的情况下被单测 ——
 * 这也是把它从 Provider 里分出来的理由。
 *
 * <p>两侧「认识哪些字段」写在 {@link #CONSUMED_ON_WRITE}、{@link #CONSUMED_ON_READ} 与
 * {@link #CONSUMED_IN_CHOICE} 里，其余字段一律走 passthrough 原样搬运：
 * 网关不为自己不理解的字段做解释，也不把它们丢掉。
 */
public final class OpenAiUpstreamCodec {

  /** 请求路径。 */
  public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

  /**
   * 上游用来表示「我说完了」的哨兵载荷。
   *
   * <p>它是 wire format 的一部分而不是协议之外的约定：认出它的责任在这里，
   * 而「认出它之后要发出终态」是 Provider 的事（ADR-0006：终态必须显式发出）。
   */
  public static final String DONE_SENTINEL = "[DONE]";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 写向上游时会被显式建模的入站字段键；其余键进 passthrough 原样转发。 */
  private static final Set<String> CONSUMED_ON_WRITE =
      Set.of("model", "messages", "tools", "temperature", "top_p", "max_tokens", "stop", "stream");

  /** 读回来时被显式建模的上游字段键；其余键进 passthrough 原样返回给客户端。 */
  private static final Set<String> CONSUMED_ON_READ = Set.of("id", "object", "model", "choices", "usage");

  /** 单个候选里被显式建模的键。 */
  private static final Set<String> CONSUMED_IN_CHOICE = Set.of("index", "message", "finish_reason");

  /** 单个消息里被显式建模的键。 */
  private static final Set<String> CONSUMED_IN_MESSAGE = Set.of("role", "content");

  /** usage 里被显式建模的键；其余明细原样带回。 */
  private static final Set<String> CONSUMED_IN_USAGE =
      Set.of("prompt_tokens", "completion_tokens", "total_tokens");

  private static final String NO_DETAIL = "上游没有给出进一步的说明";

  private OpenAiUpstreamCodec() {
  }

  /**
   * Canonical 请求 → 上游 JSON。
   *
   * @param request Canonical 请求
   * @return 可以直接发给上游的请求体
   */
  public static String encodeRequest(CanonicalRequest request) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("model", request.model());
    root.put("stream", request.stream());
    root.set("messages", encodeMessages(request.messages()));
    if (!request.tools().isEmpty()) {
      root.set("tools", encodeTools(request.tools()));
    }
    if (request.params().temperature() != null) {
      root.put("temperature", request.params().temperature().doubleValue());
    }
    if (request.params().topP() != null) {
      root.put("top_p", request.params().topP().doubleValue());
    }
    if (request.params().maxTokens() != null) {
      root.put("max_tokens", request.params().maxTokens().intValue());
    }
    if (!request.params().stop().isEmpty()) {
      ArrayNode stop = MAPPER.createArrayNode();
      request.params().stop().forEach(stop::add);
      root.set("stop", stop);
    }
    request.passthrough().forEach((key, rawJson) -> root.set(key, parseRaw(key, rawJson)));
    return root.toString();
  }

  /**
   * 上游响应 JSON → Canonical 响应。
   *
   * <p>整份字节在这里被解析一次，此后所有消费者操作的都是已经成形的对象（CHUNK_PARSE_COUNT=1）。
   *
   * @param upstreamBody 上游响应体原文
   * @return Canonical 响应
   * @throws IllegalArgumentException 响应体不是合法 JSON 时抛出
   */
  public static CanonicalResponse decodeResponse(String upstreamBody) {
    if (upstreamBody == null || upstreamBody.isBlank()) {
      return new CanonicalResponse("", "", List.of(), null, Map.of());
    }
    JsonNode root = readTreeOrThrow(upstreamBody);
    List<CanonicalChoice> choices = new ArrayList<>();
    JsonNode choicesNode = root.path("choices");
    if (choicesNode.isArray()) {
      for (JsonNode choice : choicesNode) {
        choices.add(decodeChoice(choice));
      }
    }
    return new CanonicalResponse(
        root.path("id").asString(""),
        root.path("model").asString(""),
        List.copyOf(choices),
        decodeUsage(root.path("usage")),
        passthroughOf(root, CONSUMED_ON_READ));
  }

  /**
   * 一个流式载荷里被建模出来的内容。
   *
   * <p>它不是一个事件：同一个载荷可能同时带来增量、停止原因与用量，
   * 而「这些该怎么变成事件、什么时候发终态」是 Provider 的判断，codec 只负责把字节变成值。
   *
   * @param id 流标识，这一帧没给时为 null
   * @param model 模型名，这一帧没给时为 null
   * @param created 创建时间（秒），这一帧没给时为 null
   * @param deltas 这一帧带来的文本增量。空串的增量不出现在这里 ——
   *               上游大量发送 {@code delta:{}} 的帧（首帧带角色、末帧带停止原因），
   *               把它们也变成增量会让消费方收到一串没有内容的事件
   * @param finishReason 停止原因，这一帧没给时为 null
   * @param usage 用量，这一帧没给时为 null
   */
  public record StreamChunk(String id,
                            String model,
                            Long created,
                            List<Delta> deltas,
                            CanonicalFinishReason finishReason,
                            CanonicalUsage usage) {

    /** 复制一份：这个对象会被交给调用方保存，而调用方没有理由相信传进来的那个列表不会再变。 */
    public StreamChunk {
      deltas = deltas == null ? List.of() : List.copyOf(deltas);
    }
  }

  /**
   * 一个候选在这一帧里的文本增量。
   *
   * @param index 候选序号
   * @param content 增量文本，不是空串
   */
  public record Delta(int index, String content) {
  }

  /**
   * 一个流式载荷 → 被建模出来的内容。
   *
   * <p>整份字节在这里被解析一次，此后所有消费者操作的都是已经成形的值（CHUNK_PARSE_COUNT=1）。
   * 调用方在调用前后各记一次数，就能断言「同一份字节没有被解析第二次」。
   *
   * @param payload 一个 SSE 事件的载荷，可能是 {@link #DONE_SENTINEL} 之外的任何东西
   * @return 这一帧的内容；三种字段都可能是空的（上游发了一帧什么都没带的保活帧）
   * @throws IllegalArgumentException 载荷不是合法 JSON 时抛出
   */
  public static StreamChunk decodeStreamChunk(String payload) {
    JsonNode root = readTreeOrThrow(payload == null ? "" : payload);
    List<Delta> deltas = new ArrayList<>();
    CanonicalFinishReason finishReason = null;
    JsonNode choicesNode = root.path("choices");
    if (choicesNode.isArray()) {
      for (JsonNode choice : choicesNode) {
        String content = choice.path("delta").path("content").asString("");
        if (!content.isEmpty()) {
          deltas.add(new Delta(choice.path("index").asInt(0), content));
        }
        if (finishReason == null && choice.hasNonNull("finish_reason")) {
          finishReason = CanonicalFinishReason.of(choice.path("finish_reason").asString(""));
        }
      }
    }
    return new StreamChunk(
        root.hasNonNull("id") ? root.path("id").asString("") : null,
        root.hasNonNull("model") ? root.path("model").asString("") : null,
        root.hasNonNull("created") ? root.path("created").asLong(0L) : null,
        List.copyOf(deltas),
        finishReason,
        decodeUsage(root.path("usage")));
  }

  /**
   * 从上游的错误响应里抽出可读信息。
   *
   * <p>拿不到结构化错误时不凑合，也不把整份响应体交给客户端 ——
   * 上游的错误体里可能带有客户端不该看到的内部细节。
   *
   * @param upstreamBody 上游错误响应体
   * @return 可以返回给客户端的错误说明
   */
  public static String errorMessageFrom(String upstreamBody) {
    if (upstreamBody == null || upstreamBody.isBlank()) {
      return NO_DETAIL;
    }
    try {
      JsonNode root = MAPPER.readTree(upstreamBody);
      JsonNode error = root.path("error");
      String message = error.isObject() ? error.path("message").asString("") : root.path("message").asString("");
      return message.isBlank() ? NO_DETAIL : message;
    } catch (JacksonException expected) {
      return "上游的错误响应不是可解析的 JSON";
    }
  }

  private static CanonicalChoice decodeChoice(JsonNode node) {
    JsonNode message = node.path("message");
    return new CanonicalChoice(
        node.path("index").asInt(0),
        new CanonicalMessage(
            decodeRole(message.path("role").asString("")),
            message.path("content").asString(""),
            passthroughOf(message, CONSUMED_IN_MESSAGE)),
        CanonicalFinishReason.of(node.path("finish_reason").asString("")),
        passthroughOf(node, CONSUMED_IN_CHOICE));
  }

  /**
   * 角色容错。
   *
   * <p>遇到未知取值时选择**保留正文**并标记为 ASSISTANT，而不是丢掉整条消息或抛异常 ——
   * 认错角色的代价远小于丢消息：后者在客户端表现为「模型突然不说话了」而无从追查。
   *
   * @param raw 上游角色原文
   * @return Canonical 角色
   */
  private static CanonicalRole decodeRole(String raw) {
    switch (raw.toLowerCase(Locale.ROOT)) {
      case "assistant":
        return CanonicalRole.ASSISTANT;
      case "user":
        return CanonicalRole.USER;
      case "system":
        return CanonicalRole.SYSTEM;
      case "tool":
        return CanonicalRole.TOOL;
      default:
        return CanonicalRole.ASSISTANT;
    }
  }

  /**
   * 用量翻译。
   *
   * <p>{@code usage} 节点缺失时返回 null —— 那是「上游没有给」。
   * 它与「给了但某个子字段缺失」不是一回事：后者按 0 处理，且不推导合计值。
   *
   * @param usage 上游 usage 节点
   * @return Canonical 用量，缺失时为 null
   */
  private static CanonicalUsage decodeUsage(JsonNode usage) {
    if (usage.isMissingNode() || usage.isNull()) {
      return null;
    }
    return new CanonicalUsage(
        usage.path("prompt_tokens").asLong(0L),
        usage.path("completion_tokens").asLong(0L),
        usage.path("total_tokens").asLong(0L),
        passthroughOf(usage, CONSUMED_IN_USAGE));
  }

  private static ArrayNode encodeMessages(List<CanonicalMessage> messages) {
    ArrayNode array = MAPPER.createArrayNode();
    for (CanonicalMessage message : messages) {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("role", message.role().name().toLowerCase(Locale.ROOT));
      node.put("content", message.content());
      message.passthrough().forEach((key, rawJson) -> node.set(key, parseRaw(key, rawJson)));
      array.add(node);
    }
    return array;
  }

  private static ArrayNode encodeTools(List<CanonicalTool> tools) {
    ArrayNode array = MAPPER.createArrayNode();
    for (CanonicalTool tool : tools) {
      ObjectNode function = MAPPER.createObjectNode();
      function.put("name", tool.name());
      function.put("description", tool.description() == null ? "" : tool.description());
      function.set("parameters", parseRaw("parameters", tool.jsonSchema()));
      ObjectNode node = MAPPER.createObjectNode();
      node.put("type", "function");
      node.set("function", function);
      array.add(node);
    }
    return array;
  }

  /** passthrough 里的值必须是合法 JSON。解析不出来的场合也要能看出来，而不是静默丢掉。 */
  private static JsonNode parseRaw(String key, String rawJson) {
    try {
      return MAPPER.readTree(rawJson);
    } catch (JacksonException expected) {
      return MAPPER.createObjectNode().put(key + "_unparsed", rawJson);
    }
  }

  private static JsonNode readTreeOrThrow(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (JacksonException failure) {
      throw new IllegalArgumentException("上游响应不是合法 JSON", failure);
    }
  }

  /** 把节点里没被建模的键原样搬进 passthrough，值是 JSON 原文。 */
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
