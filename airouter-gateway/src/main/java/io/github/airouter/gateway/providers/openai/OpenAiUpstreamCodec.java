package io.github.airouter.gateway.providers.openai;

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
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.transport.UpstreamFrame;
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
 * <p>两侧「认识哪些字段」写在 {@link #CONSUMED_ON_WRITE}、{@link #CONSUMED_ON_READ}、
 * {@link #CONSUMED_IN_CHOICE}、{@link #CONSUMED_IN_MESSAGE} 与 {@link #CONSUMED_IN_USAGE} 里，
 * 其余字段一律走 passthrough 原样搬运：网关不为自己不理解的字段做解释，也不把它们丢掉。
 *
 * <p>非流式那一半是纯函数集合（请求编码与响应解码）。流式那一半 —— 一帧字节变成哪些事件 ——
 * 是一个按流创建的小对象（{@link StreamDecoder}）：哪些字段只在开场出现一次，
 * 是这条流自己的事。判断（什么算结束、什么算失败）仍然留给 Provider，codec 只翻译。
 */
public final class OpenAiUpstreamCodec {

  /** 请求路径。它是相对路径：基地址（含 {@code /v1}）由 {@code Endpoint} 给出。 */
  public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 写向上游时会被显式建模的字段键；其余键进 passthrough 原样转发。 */
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

  /** 流式一帧里被显式建模的键；其余键（含 {@code created}）进开场的 passthrough。 */
  private static final Set<String> CONSUMED_IN_CHUNK = Set.of("id", "object", "model", "choices", "usage");

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
   * 一帧字节变成哪些事件。
   *
   * <p>它是**有状态**的，因此是一个按流创建的对象而不是一个静态方法：
   * 「哪一块是开场」只有这条流自己知道，而开场那一帧要带的东西（id、模型、以及
   * {@code created} 这类每一帧都要重复的键）之后的帧里不再重复 ——
   * 把它做成无状态函数，就只能每一帧都发一次开场，或者干脆不发。
   *
   * <p>{@code data: [DONE]} 在这里被认成终态：哨兵是**这一家 wire format** 的说法，
   * 认它属于 codec 而不是传输层（ADR-0006 第 2 节）。
   * 认到之后发 {@link CanonicalStreamEvent.Terminal}，且**不再解释后面的帧** ——
   * 「终态之后的帧不出现在客户端」由调用方的截断保证，这里只负责不再产生事件。
   *
   * <p>空 {@code data:} 帧被忽略而不是被当成内容：上游用它做保活，
   * 当成内容会在客户端的正文里冒出空行。
   */
  public static final class StreamDecoder {

    /** 结束哨兵。它是不是「结束」由这一家 wire format 说了算，因此写在这里。 */
    public static final String DONE_SENTINEL = "[DONE]";

    private boolean opened;

    /**
     * 一帧 → 事件。
     *
     * @param frame 分帧器给出的帧
     * @return 这一帧带来的事件，可能为空（保活、空帧、终态之后的帧）
     * @throws IllegalArgumentException 载荷不是合法 JSON 时抛出
     */
    public List<CanonicalStreamEvent> decode(UpstreamFrame frame) {
      if (frame instanceof UpstreamFrame.Comment comment) {
        return List.of(new CanonicalStreamEvent.Ping(comment.text()));
      }
      String payload = ((UpstreamFrame.Data) frame).payload();
      if (payload.isBlank()) {
        return List.of();
      }
      if (DONE_SENTINEL.equals(payload.strip())) {
        return List.of(new CanonicalStreamEvent.Terminal(null));
      }
      return decodeChunk(payload);
    }

    private List<CanonicalStreamEvent> decodeChunk(String payload) {
      JsonNode root = readTreeOrThrow(payload);
      List<CanonicalStreamEvent> events = new ArrayList<>();
      if (!opened) {
        opened = true;
        events.add(new CanonicalStreamEvent.Opening(
            root.path("id").asString(""),
            root.path("model").asString(""),
            passthroughOf(root, CONSUMED_IN_CHUNK)));
      }
      JsonNode delta = root.path("choices").path(0).path("delta");
      String content = delta.path("content").asString("");
      if (!content.isEmpty()) {
        events.add(new CanonicalStreamEvent.TextDelta(content));
      }
      CanonicalUsage usage = decodeUsage(root.path("usage"));
      if (usage != null) {
        events.add(new CanonicalStreamEvent.Usage(usage));
      }
      JsonNode finishReason = root.path("choices").path(0).path("finish_reason");
      if (!finishReason.isMissingNode() && !finishReason.isNull()) {
        events.add(new CanonicalStreamEvent.Terminal(CanonicalFinishReason.of(finishReason.asString(""))));
      }
      return List.copyOf(events);
    }
  }

  /** 开始一条流的解码。 */
  public static StreamDecoder newStreamDecoder() {
    return new StreamDecoder();
  }

  /**
   * 上游的非 2xx 变成哪一种失败。
   *
   * <p>4xx 的原样透传、5xx 收敛到 502，理由是这两类错误的**归因对象不同**：
   * 4xx 是调用方与上游之间的问题（换一个模型名就好），把 400 变成 502 会让调用方去查网关；
   * 5xx 是上游自己的问题，把具体状态码交给客户端等于把上游的内部状态暴露出去。
   *
   * <p>措辞留在这里而不是共享的错误映射里：历史上为了改一处文案动了共享映射，
   * 结果另一个形态的 golden 基线一起变红（ADR-0006 第 3 节）。
   *
   * @param status 上游给出的状态码
   * @param upstreamBody 上游错误响应体
   * @return 带 {@link GatewayError} 的失败
   */
  public static UpstreamFailure rejection(int status, String upstreamBody) {
    String detail = errorMessageFrom(upstreamBody);
    if (status >= 400 && status < 500) {
      return new UpstreamFailure(
          new GatewayError("upstream_4xx", "上游拒绝了这次请求（" + status + "）：" + detail, status), null);
    }
    return new UpstreamFailure(
        new GatewayError("upstream_5xx", "上游处理本次请求时失败（" + status + "）：" + detail, 502), null);
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
      String message = error.isObject()
          ? error.path("message").asString("")
          : root.path("message").asString("");
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
      function.put("description", tool.description());
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
      throw new IllegalArgumentException("上游给出的不是合法 JSON", failure);
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
