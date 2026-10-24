package io.github.airouter.gateway.providers.ollama;

import io.github.airouter.core.CanonicalChoice;
import io.github.airouter.core.CanonicalFinishReason;
import io.github.airouter.core.CanonicalMessage;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalRole;
import io.github.airouter.core.CanonicalTool;
import io.github.airouter.core.CanonicalUsage;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
 * Canonical 与 Ollama **原生** wire format 之间的互转。
 *
 * <p>它与 {@code OpenAiUpstreamCodec} 是并列的两个 Knowns，谁也不引用谁，理由与那边一样：
 * 一种形态的字段知识不该出现在另一种形态的翻译里，否则改一处要同时读懂两处。
 *
 * <p>两种形态的差异不是命名习惯，是结构性的。以下每一条都是本机真实响应里看出来的，不是推测：
 *
 * <table>
 *   <caption>与 OpenAI 兼容形态的差异（均为本机 Ollama 实测）</caption>
 *   <tr><td></td><td>OpenAI 兼容</td><td>Ollama 原生</td></tr>
 *   <tr><td>路径</td><td>{@code /chat/completions}</td><td>{@code /chat}</td></tr>
 *   <tr><td>地址形态</td><td>以 {@code /v1} 结尾</td><td>以 {@code /api} 结尾</td></tr>
 *   <tr><td>生成参数</td><td>顶层 {@code temperature} 等</td>
 *       <td>嵌在 {@code options} 里，且 {@code max_tokens} 叫 {@code num_predict}</td></tr>
 *   <tr><td>凭证</td><td>{@code Authorization: Bearer}</td><td>无</td></tr>
 *   <tr><td>内容位置</td><td>{@code choices[0].message.content}</td><td>{@code message.content}，没有 choices 这一层</td></tr>
 *   <tr><td>停止原因</td><td>{@code finish_reason}</td><td>{@code done_reason}</td></tr>
 *   <tr><td>用量</td><td>{@code usage{prompt_tokens, completion_tokens, total_tokens}}</td>
 *       <td>顶层 {@code prompt_eval_count} / {@code eval_count}，没有合计</td></tr>
 *   <tr><td>流标识</td><td>{@code chatcmpl-...}</td><td>没有这个字段</td></tr>
 *   <tr><td>创建时间</td><td>Unix 秒</td><td>RFC 3339 字符串（{@code created_at}）</td></tr>
 *   <tr><td>流式分帧</td><td>SSE，{@code data:} 前缀 + 空行 + {@code [DONE]}</td>
 *       <td>逐行 JSON，没有前缀、没有空行、没有哨兵</td></tr>
 *   <tr><td>流怎么结束</td><td>上游发 {@code [DONE]}</td><td>最后一帧带 {@code done:true}</td></tr>
 *   <tr><td>错误体</td><td>{@code error.message}</td><td>{@code error} 直接是个字符串</td></tr>
 * </table>
 *
 * <p>最后两条是这份 codec 存在的主要理由：它们决定了「Provider 什么时候发终态」，
 * 而这正是 ADR-0006 里最容易被写错的一处 —— 少了哨兵这一说，判断「说完了」的依据
 * 就只能是帧的内容本身。
 */
public final class OllamaUpstreamCodec {

  /** 请求路径，相对于 {@code /api}。 */
  public static final String CHAT_PATH = "/chat";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 写向上游时会被显式建模的入站字段键；其余键进 passthrough 原样转发。 */
  private static final Set<String> CONSUMED_ON_WRITE =
      Set.of("model", "messages", "tools", "temperature", "top_p", "max_tokens", "stop", "stream");

  /** 读回来时被显式建模的上游字段键；其余键进 passthrough 原样返回给客户端。 */
  private static final Set<String> CONSUMED_ON_READ = Set.of(
      "model", "created_at", "message", "done", "done_reason",
      "prompt_eval_count", "eval_count", "prompt_eval_cached_count",
      "total_duration", "load_duration", "prompt_eval_duration", "eval_duration");

  /** 单个消息里被显式建模的键。 */
  private static final Set<String> CONSUMED_IN_MESSAGE = Set.of("role", "content");

  /** 用量里被显式建模的键；其余计数原样带回 —— 计费侧关心的往往正是明细。 */
  private static final Set<String> CONSUMED_IN_USAGE = Set.of("prompt_eval_count", "eval_count");

  /** 用量明细的键。它们不是 token 数而是耗时与缓存命中，被带出境而不是被解释。 */
  private static final Set<String> USAGE_DETAIL_KEYS = Set.of(
      "prompt_eval_cached_count", "total_duration", "load_duration",
      "prompt_eval_duration", "eval_duration");

  private static final String NO_DETAIL = "上游没有给出进一步的说明";

  private OllamaUpstreamCodec() {
  }

  /**
   * Canonical 请求 → 上游 JSON。
   *
   * <p>生成参数进 {@code options} 而不是顶层：这是原生接口的规定位置，
   * 写在顶层会被静默忽略 —— 于是「客户端要求 temperature=0」变成一句没人听见的话，
   * 而这类偏差不会报错，只会让调用方觉得模型不听话。
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
    ObjectNode options = encodeOptions(request);
    if (!options.isEmpty()) {
      root.set("options", options);
    }
    request.passthrough().forEach((key, rawJson) -> root.set(key, parseRaw(key, rawJson)));
    return root.toString();
  }

  /**
   * 上游响应 JSON → Canonical 响应。
   *
   * <p>没有 {@code choices} 这一层，因此只有一个候选，序号固定为 0 ——
   * 原生接口不支持一次请求返回多个候选，把它补成一个数组只是为了经过同一个 Canonical。
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
    return new CanonicalResponse(
        "",
        root.path("model").asString(""),
        List.of(new CanonicalChoice(
            0,
            decodeMessage(root.path("message")),
            CanonicalFinishReason.of(root.path("done_reason").asString("")),
            Map.of())),
        decodeUsage(root),
        passthroughWithCreated(root));
  }

  /**
   * 未被建模的字段 + 一个被**换算过**的 {@code created}。
   *
   * <p>原生接口给的是 {@code created_at}（RFC 3339 字符串），客户端那一侧要的是 {@code created}
   * （Unix 秒）。Canononical 的非流式响应里没有创建时间这个字段，而本票的验收条件之一是
   * {@code core} 零改动 —— 因此这里不去动 core，而是把换算结果以客户端那一侧的形态
   * 放进 passthrough。OpenAI 兼容形态走的是同一条路（那边的 {@code created} 本来就靠 passthrough 带出境），
   * 所以两种形态在客户端看来是一致的。
   *
   * <p>换算不了时不放这个键：客户端少一个字段，好过给它一个自己算出来的时间。
   */
  private static Map<String, String> passthroughWithCreated(JsonNode root) {
    Map<String, String> passthrough = new LinkedHashMap<>(passthroughOf(root, CONSUMED_ON_READ));
    Long created = epochSecondsOf(root.path("created_at"));
    if (created != null) {
      passthrough.put("created", String.valueOf(created));
    }
    return Map.copyOf(passthrough);
  }

  /**
   * 一个流式载荷里被建模出来的内容。
   *
   * <p>与 OpenAI 那一侧一样，它不是一个事件：一帧可能同时带来增量、结束标志与用量，
   * 而「这些该怎么变成事件、什么时候发终态」是 Provider 的判断，codec 只负责把字节变成值。
   *
   * @param model 模型名，这一帧没给时为 null
   * @param created 创建时间（秒），由 {@code created_at} 换算而来，没有或不可解析时为 null
   * @param content 这一帧带来的文本。末帧的 content 是空串，因此空串**不代表没有内容帧**，
   *                 而是「这一帧没有带来新内容」；是否为 null 才表示这一帧没有 message
   * @param done 上游有没有宣告说完了。它是这一份 wire 形态的终态依据 —— 没有哨兵可认
   * @param doneReason 停止原因，这一帧没给时为 null
   * @param usage 用量，这一帧没给时为 null
   */
  public record StreamFrame(String model,
                             Long created,
                             String content,
                             boolean done,
                             CanonicalFinishReason doneReason,
                             CanonicalUsage usage) {
  }

  /**
   * 一个流式载荷 → 被建模出来的内容。
   *
   * <p>整份字节在这里被解析一次，此后所有消费者操作的都是已经成形的值（CHUNK_PARSE_COUNT=1）。
   *
   * @param payload 一整行 JSON
   * @return 这一帧的内容
   * @throws IllegalArgumentException 载荷不是合法 JSON 时抛出
   */
  public static StreamFrame decodeStreamFrame(String payload) {
    JsonNode root = readTreeOrThrow(payload == null ? "" : payload);
    JsonNode message = root.path("message");
    return new StreamFrame(
        root.hasNonNull("model") ? root.path("model").asString("") : null,
        epochSecondsOf(root.path("created_at")),
        message.isMissingNode() ? null : message.path("content").asString(""),
        root.path("done").asBoolean(false),
        root.hasNonNull("done_reason")
            ? CanonicalFinishReason.of(root.path("done_reason").asString(""))
            : null,
        decodeUsage(root));
  }

  /**
   * 从上游的错误响应里抽出可读信息。
   *
   * <p>原生接口的错误体是 {@code {"error":"..."}} —— **字符串而不是对象**，
   * 这是与 OpenAI 的 {@code {"error":{"message":"..."}}} 又一处不同。
   * 两种写法都认，是因为我们不能决定上游用哪一种；认不出来时给一句固定的话，
   * 不把整份响应体交给客户端（里面可能有客户端不该看到的内部细节）。
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
      String message = error.isObject() ? error.path("message").asString("") : error.asString("");
      return message.isBlank() ? NO_DETAIL : message;
    } catch (JacksonException expected) {
      return "上游的错误响应不是可解析的 JSON";
    }
  }

  private static ObjectNode encodeOptions(CanonicalRequest request) {
    ObjectNode options = MAPPER.createObjectNode();
    if (request.params().temperature() != null) {
      options.put("temperature", request.params().temperature().doubleValue());
    }
    if (request.params().topP() != null) {
      options.put("top_p", request.params().topP().doubleValue());
    }
    if (request.params().maxTokens() != null) {
      // 名字不同且不是别名关系：num_predict 是「最多生成多少个 token」，
      // 与 max_tokens 语义相同，但写成 max_tokens 会被忽略 —— 忽略不报错，只是不生效。
      options.put("num_predict", request.params().maxTokens().intValue());
    }
    if (!request.params().stop().isEmpty()) {
      ArrayNode stop = MAPPER.createArrayNode();
      request.params().stop().forEach(stop::add);
      options.set("stop", stop);
    }
    return options;
  }

  private static CanonicalMessage decodeMessage(JsonNode message) {
    return new CanonicalMessage(
        decodeRole(message.path("role").asString("")),
        message.path("content").asString(""),
        passthroughOf(message, CONSUMED_IN_MESSAGE));
  }

  /**
   * 角色容错。
   *
   * <p>遇到未知取值时保留正文并标记为 ASSISTANT，理由与 OpenAI 那一侧相同：
   * 认错角色的代价远小于丢消息 —— 后者在客户端表现为「模型突然不说话了」而无从追查。
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
   * <p>原生接口给两个计数：{@code prompt_eval_count} 与 {@code eval_count}。
   * **合计不在这里相加。** 上游没有给这个数，而一个被算出来的合计会被拿去做成本核销 ——
   * 事后没人能区分它是上游给的还是网关加出来的。因此 {@code totalTokens} 是 0，
   * 含义是「上游没有给」，与「给了但是 0」在 Canonical 里是同一个值，
   * 区别只在于明细里带着两个真实计数，要看的人自己加。
   *
   * @param root 上游响应的根节点；原生接口没有 usage 这一层，计数就在顶层
   * @return Canonical 用量，两个计数都不存在时为 null
   */
  private static CanonicalUsage decodeUsage(JsonNode root) {
    boolean hasPrompt = root.hasNonNull("prompt_eval_count");
    boolean hasCompletion = root.hasNonNull("eval_count");
    if (!hasPrompt && !hasCompletion) {
      return null;
    }
    Map<String, String> detail = new LinkedHashMap<>();
    for (String field : root.propertyNames()) {
      if (USAGE_DETAIL_KEYS.contains(field)) {
        detail.put(field, root.path(field).toString());
      }
    }
    return new CanonicalUsage(
        root.path("prompt_eval_count").asLong(0L),
        root.path("eval_count").asLong(0L),
        0L,
        detail);
  }

  /**
   * {@code created_at} → Unix 秒。
   *
   * <p>它是 RFC 3339 字符串而不是数字，因此要换算；换算不了时给 null 而不是抛：
   * 创建时间缺失的后果是客户端这一帧少一个字段，而抛异常的后果是整条流变成一次失败 ——
   * 用后者去换前者不值得。
   */
  private static Long epochSecondsOf(JsonNode createdAt) {
    if (createdAt.isMissingNode() || !createdAt.isTextual()) {
      return null;
    }
    try {
      return Instant.parse(createdAt.asString()).getEpochSecond();
    } catch (DateTimeParseException expected) {
      return null;
    }
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

  /**
   * 工具声明。
   *
   * <p>形态与 OpenAI 兼容端点一致（{@code type:function} + {@code function{name,description,parameters}}），
   * 因此编码方式与那边相同。**它没有真机样本**：本机 0.5B 模型不支持工具调用，
   * 这段编码的依据是原生接口的文档形态，不是一次实测。这一条刻意写在这里 ——
   * 没有样本却声称验证过，正是本项目要避开的那种声明。
   */
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
