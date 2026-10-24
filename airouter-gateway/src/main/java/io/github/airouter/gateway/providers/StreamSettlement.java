package io.github.airouter.gateway.providers;

import io.github.airouter.core.CanonicalFinishReason;
import io.github.airouter.core.CanonicalRequest;
import io.github.airouter.core.CanonicalResponse;
import io.github.airouter.core.CanonicalStreamEvent;
import io.github.airouter.core.CanonicalUsage;
import io.github.airouter.core.GatewayError;
import io.github.airouter.core.Outcome;
import io.github.airouter.core.OutcomeKind;
import io.github.airouter.core.OutcomeObserver;
import io.github.airouter.core.UpstreamFailure;
import io.github.airouter.gateway.transport.UpstreamRejection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import reactor.core.publisher.Flux;

/**
 * 流式与非流式两条路径共用的结算纪律。
 *
 * <p>它原先只存在于 OpenAI 兼容 Provider 内部。第二个 Provider 出现时被抽出来，
 * 抽的理由不是「两处代码长得像」，而是**这条纪律只有一份才成立**：
 * ADR-0006 断言「任何一条流恰好结算一次」，而这条断言一旦在两个 Provider 里各写一遍，
 * 就等于承认它可以有两份实现 —— 那么将来「某一个 Provider 少结算一次」不会与任何东西冲突，
 * 它只是另一份实现里的一个选择。抽出来之后，两种 wire 形态走的是同一段代码，
 * 差别只剩下「一帧字节怎么变成事件」，那正是形态差异应当存在的唯一位置。
 *
 * <p>它不认识任何一种 wire 形态：帧怎么切在 transport（{@code SseFrameReader} / {@code LineFrameReader}），
 * 一帧长什么样在各自的 codec。这里的输入是**已经切好的一帧文本**，输出是事件与一次结算。
 *
 * <p>错误文案的读法由 {@link ErrorExplainer} 提供，是构造参数而不是写死在这里的实现：
 * 「上游的错误体里哪一段是可读信息」是 wire 形态的一部分（OpenAI 放在 {@code error.message}，
 * Ollama 放在 {@code error}），而「读出来之后怎么分类」不是。
 * 分得开，新增形态才用不着改这一段。
 */
public final class StreamSettlement {

  /** 状态码在 400 到 499 之间时，把上游的状态原样交给客户端。 */
  private static final int CLIENT_ERROR_FLOOR = 400;

  private static final int SERVER_ERROR_FLOOR = 500;

  /** 上游故障时对客户端给出的状态码：这是网关这一层的事，不该让客户端去猜上游发生了什么。 */
  private static final int BAD_GATEWAY = 502;

  private final OutcomeObserver observer;

  private final String instanceId;

  private final ErrorExplainer errors;

  /**
   * @param observer 结果结算点，由 {@code config} 装配；本类不解释结算结果，只负责上报
   * @param instanceId 实例标识，出现在 {@link Outcome} 里
   * @param errors 从上游错误体里取可读信息的读法，由形态给出
   */
  public StreamSettlement(OutcomeObserver observer, String instanceId, ErrorExplainer errors) {
    this.observer = observer;
    this.instanceId = instanceId;
    this.errors = errors;
  }

  /** 一帧字节 → 零个或多个事件。形态差异的全部内容都在这一个方法里。 */
  public interface FrameTranslator {

    /**
     * @param payload 已经切好的一帧文本
     * @param state 这条流累积到的状态，用于「是不是第一次见到身份」「终态之前攒下了什么」
     * @return 这一帧产生的事件，可以为空
     */
    List<CanonicalStreamEvent> translate(String payload, State state);
  }

  /**
   * 这一类形态**怎么把话说清楚**：错误体里哪一段是可读信息，以及「话没说完就断了」该怎么讲。
   *
   * <p>第二项看起来是措辞，其实是协议差别：有的形态用一个结束哨兵宣告说完，
   * 有的形态由最后一帧的内容宣告。因此「在结束哨兵之前就断了」这句话
   * 对后者不成立 —— 它没有哨兵。让形态自己给出这句话，
   * 而不是在这里写一句两者都不太准的话，也不是为了迁就其中一个去改另一个的基线。
   *
   * @param messageFrom 上游错误体 → 可读信息
   * @param prematureCloseMessage 上游在讲完之前就关闭连接时，对客户端说的那句话
   */
  public record ErrorExplainer(java.util.function.Function<String, String> messageFrom,
                               String prematureCloseMessage) {
  }

  /**
   * 把帧流变成事件流，并**保证这条流恰好结算一次**。
   *
   * <p>{@code defer} 是刻意的：每一份累积状态都属于**一次订阅**。少了它，第二次订阅
   * 会接着上一次的「是否已发终态」往下走，而那一次的流已经结束了 ——
   * 表现为第二次订阅永久挂起，且不报错。
   *
   * <p>{@code takeUntil} 断言的是「终态是这条流的最后一个事件」：上游在结束哨兵之后
   * 又发了帧（合规的客户端不会这样，但字节层面的东西不受客户端约束），
   * 少了它那些帧会变成**终态之后的内容帧**，而消费方按「终态之后不会再有内容」写代码。
   * 终态唯一与终态最后不是同一件事，这一行管的是后者。
   *
   * @param frames 已经切好的帧，来源由调用方决定（SSE 或逐行 JSON）
   * @param request 这一次调用的请求，结算时带上它的标识
   * @param translator 一帧 → 事件，由形态给出
   * @return 事件流；无论上游表现如何，它以 {@code Terminal} 或 {@code Failed} 收尾
   */
  public Flux<CanonicalStreamEvent> assemble(Flux<String> frames,
                                              CanonicalRequest request,
                                              FrameTranslator translator) {
    return Flux.defer(() -> {
      State state = new State(errors.prematureCloseMessage());
      return frames
          .concatMap(payload -> Flux.fromIterable(translator.translate(payload, state)))
          .concatWith(Flux.defer(state::closeWithoutTerminal))
          .onErrorResume(failure -> Flux.just(failedEvent(state, failure)))
          .takeUntil(CanonicalStreamEvent.Terminal.class::isInstance)
          .doOnNext(event -> observe(request, state, event))
          .doOnCancel(() -> observeCancelled(request, state));
    });
  }

  /**
   * 非流式路径的结算，与流式共用同一个 {@link OutcomeObserver}：两条路径的结果口径必须是一个。
   *
   * @param request 这一次调用的请求
   * @param start 发出请求的时刻
   * @param response 成功时拿到的响应
   * @param failure 失败时的异常；成功为 null
   */
  public void report(CanonicalRequest request,
                     Instant start,
                     CanonicalResponse response,
                     Throwable failure) {
    Duration latency = Duration.between(start, Instant.now());
    if (failure == null) {
      observer.onTerminal(new Outcome(request.requestId(), request.serviceId(), instanceId,
          OutcomeKind.SUCCESS, latency, response.usage(), null));
      return;
    }
    Throwable cause = unwrap(failure);
    if (cause instanceof UpstreamFailure upstream) {
      observer.onTerminal(new Outcome(request.requestId(), request.serviceId(), instanceId,
          upstream.kind(), latency, null, upstream.error()));
      return;
    }
    observer.onTerminal(new Outcome(request.requestId(), request.serviceId(), instanceId,
        OutcomeKind.UPSTREAM_FAILURE, latency, null,
        new GatewayError("internal", "网关处理本次请求时失败", BAD_GATEWAY)));
  }

  /**
   * 上游用非 2xx 回答了：非流式路径把它变成一次失败。
   *
   * <p>分类与流式路径上的 {@link #failedEvent} 是同一份，不出现第二个口径。
   *
   * @param status 上游状态码
   * @param body 上游响应体
   * @return 失败，由调用方抛出
   */
  public UpstreamFailure rejection(int status, String body) {
    return new UpstreamFailure(clientError(status, body), null);
  }

  /**
   * 模型名的取用顺序：请求里指定的优先，没给才回落到配置的默认值。
   *
   * <p>两个 Provider 共用，因为它是**网关这一侧的策略**而不是某一种 wire 形态的事。
   * 不把请求里的模型名改写成配置里的名字：那样会让「客户端指定了一个不存在的模型」
   * 变成一次静默的成功，而这个错误应当在上游被看见。
   *
   * @param request 入站请求
   * @param defaultModel 配置给出的默认值
   * @return 模型名已确定的请求
   */
  public static CanonicalRequest resolveModel(CanonicalRequest request, String defaultModel) {
    if (request.model() != null && !request.model().isBlank()) {
      return request;
    }
    return new CanonicalRequest(
        request.requestId(),
        request.serviceId(),
        defaultModel,
        request.messages(),
        request.tools(),
        request.params(),
        request.stream(),
        request.passthrough());
  }

  /**
   * 终态事件到达时的结算。
   *
   * <p>取消没有对应的事件：**事件的接收方已经不在了**，往一个已经断开的下游发事件
   * 既不成立也没有意义。但结算仍然要发生 —— 取消是一次调用的一种结局，
   * 而「少记一次」与「记成成功」对下游的熔断器是同一种误导。见 {@link #observeCancelled}。
   */
  private void observe(CanonicalRequest request, State state, CanonicalStreamEvent event) {
    if (event instanceof CanonicalStreamEvent.Terminal terminal) {
      settle(request, state, OutcomeKind.SUCCESS, terminal.usage(), null);
      return;
    }
    if (event instanceof CanonicalStreamEvent.Failed failed) {
      settle(request, state, state.failureKind(), null, failed.error());
    }
  }

  /**
   * 调用方中途断开时的结算。
   *
   * <p>它被记成 {@link OutcomeKind#CLIENT_CANCELLED} 而不是失败，理由在 OutcomeKind 那里：
   * 移动端切后台、用户关页面都会产生取消，把它算作上游故障会让一次共享网络的抖动
   * 熔断掉一个健康的实例。
   */
  private void observeCancelled(CanonicalRequest request, State state) {
    settle(request, state, OutcomeKind.CLIENT_CANCELLED, null, cancelled());
  }

  /**
   * 取消的错误形态。
   *
   * <p>它没有状态码：取消发生时接收方已经不在了，「对客户端给什么状态码」这件事不成立。
   * 但错误形态本身必须存在 —— {@link Outcome} 要求 SUCCESS 之外都带着原因，
   * 而这条不变式的价值就在于下游永远不必处理「失败了但说不出为什么」这一种情形。
   * 为取消开一个洞，等于把这种情形重新放回来。
   */
  private static GatewayError cancelled() {
    return new GatewayError("client_cancelled", "调用方在收到终态之前断开：这条流没有走完", null);
  }

  /**
   * 上报一次结果，并**保证一条流只上报一次**。
   *
   * <p>{@code claim()} 这一道守卫不是防御性编程而是契约的一部分：
   * 「恰好结算一次」是 ADR-0006 的核心断言，而到达这里的路径有四条
   * （终态事件、失败事件、补发的失败、取消），其中任意两条在同一条流上先后发生时，
   * 少一道守卫就会变成两条结算记录 —— 表现为一次调用被计成两次，
   * 而这一类错误没有任何报错，只会让指标与熔断阈值慢慢失真。
   */
  private void settle(CanonicalRequest request,
                      State state,
                      OutcomeKind kind,
                      CanonicalUsage usage,
                      GatewayError error) {
    if (!state.claim()) {
      return;
    }
    observer.onTerminal(new Outcome(
        request.requestId(), request.serviceId(), instanceId, kind,
        state.latency(), usage, error));
  }

  /**
   * 上游错误体的分类。
   *
   * <p>**4xx 原样透传，5xx 收敛到 502。** 前者是这一次的请求有问题（模型名不存在、参数不合法），
   * 客户端需要看到自己的错误；后者是上游自身故障，客户端对此无能为力，
   * 让它去区分是哪一种上游故障只会增加耦合。
   */
  private GatewayError clientError(int status, String body) {
    if (status >= CLIENT_ERROR_FLOOR && status < SERVER_ERROR_FLOOR) {
      return new GatewayError(
          "upstream_4xx",
          "上游拒绝了这次请求（" + status + "）：" + errors.messageFrom().apply(body),
          status);
    }
    return new GatewayError(
        "upstream_5xx",
        "上游处理本次请求时失败（" + status + "）：" + errors.messageFrom().apply(body),
        BAD_GATEWAY);
  }

  /** 上游在讲完之前就把连接收了。这不是成功：内容是否完整无从判断。 */
  private GatewayError closedEarly() {
    return new GatewayError(
        "upstream_closed",
        errors.prematureCloseMessage(),
        BAD_GATEWAY);
  }

  /**
   * 一条流以失败收尾时的形态。
   *
   * <p>三种来源分开写是因为它们的性质不同，而后果也不同：
   * 上游明确拒绝（4xx 原样透传）、上游够不着或超时（网关自己的判断）、
   * 以及我们这一侧没预料到的东西（一律不把内部细节交出去）。
   */
  private CanonicalStreamEvent failedEvent(State state, Throwable failure) {
    Throwable cause = unwrap(failure);
    if (cause instanceof UpstreamRejection rejection) {
      state.noteFailure(OutcomeKind.UPSTREAM_FAILURE);
      return new CanonicalStreamEvent.Failed(clientError(rejection.status(), rejection.body()));
    }
    if (cause instanceof UpstreamFailure upstream) {
      state.noteFailure(upstream.kind());
      return new CanonicalStreamEvent.Failed(upstream.error());
    }
    state.noteFailure(OutcomeKind.UPSTREAM_FAILURE);
    return new CanonicalStreamEvent.Failed(
        new GatewayError("internal", "网关处理这条流时失败", BAD_GATEWAY));
  }

  private static Throwable unwrap(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  /** 一条流在翻译过程中的累积状态。它属于一次订阅，见 {@link #assemble}。 */
  public static final class State {

    private final Instant start = Instant.now();

    /** 「话没说完就断了」的那句话。它由形态给出，见 {@link ErrorExplainer#prematureCloseMessage}。 */
    private final String prematureCloseMessage;

    /**
     * @param prematureCloseMessage 这条流在没收到的终态之前结束时对客户端说的话
     */
    public State(String prematureCloseMessage) {
      this.prematureCloseMessage = prematureCloseMessage;
    }

    private String id = "";

    private String model = "";

    private Long created;

    private boolean opened;

    private boolean terminated;

    private boolean settled;

    private OutcomeKind failureKind = OutcomeKind.UPSTREAM_FAILURE;

    private CanonicalFinishReason finishReason;

    private CanonicalUsage usage;

    /**
     * 记下这一帧带来的身份，并回答「是不是第一次见到它」。
     *
     * <p>只有第一帧才值得产出 {@code Opening}：之后每一帧都重复带 id 与 model，
     * 把它们逐帧发成事件只会让消费方一遍遍处理同样的信息。
     *
     * @param chunkId 这一帧给出的流标识，没给时为 null
     * @param chunkModel 这一帧给出的模型名，没给时为 null
     * @param chunkCreated 这一帧给出的创建时间（秒），没给时为 null
     * @return 第一次见到身份返回 true
     */
    public boolean open(String chunkId, String chunkModel, Long chunkCreated) {
      if (opened) {
        return false;
      }
      if (chunkId != null) {
        id = chunkId;
      }
      if (chunkModel != null) {
        model = chunkModel;
      }
      if (chunkCreated != null) {
        created = chunkCreated;
      }
      opened = true;
      return true;
    }

    public void noteFinishReason(CanonicalFinishReason reason) {
      finishReason = reason;
    }

    public void noteUsage(CanonicalUsage value) {
      usage = value;
    }

    public void terminate() {
      terminated = true;
    }

    /**
     * 抢下「这一次结算」的名额。
     *
     * @return 抢到了返回 true；这条流已经结算过返回 false
     */
    public boolean claim() {
      if (settled) {
        return false;
      }
      settled = true;
      return true;
    }

    public String id() {
      return id;
    }

    public String model() {
      return model;
    }

    public Long created() {
      return created;
    }

    /**
     * 上游把话讲完了但没发终态就收了连接。
     *
     * <p>这里补发 {@code Failed} 而不是让流安静地结束：安静结束与成功在这一层长得一模一样，
     * 而它们对熔断器来说是相反的两种结论（ADR-0006 第 1 节）。
     * 「讲完了」的判据由形态给出 —— 有的形态有结束哨兵，有的形态由最后一帧的内容宣告。
     */
    public Flux<CanonicalStreamEvent> closeWithoutTerminal() {
      if (terminated) {
        return Flux.empty();
      }
      noteFailure(OutcomeKind.UPSTREAM_FAILURE);
      return Flux.just(new CanonicalStreamEvent.Failed(
          new GatewayError("upstream_closed", prematureCloseMessage, BAD_GATEWAY)));
    }

    /**
     * 记下失败类别。
     *
     * <p>类别只在产生 {@code Failed} 事件的那一处说得清（超时与「上游拒绝了」在那里还是两个类型），
     * 而上报 {@link Outcome} 发生在之后；这里就是它们之间的那道桥。
     */
    public void noteFailure(OutcomeKind kind) {
      failureKind = kind == null ? OutcomeKind.UPSTREAM_FAILURE : kind;
    }

    public CanonicalFinishReason finishReason() {
      return finishReason;
    }

    public CanonicalUsage usage() {
      return usage;
    }

    public OutcomeKind failureKind() {
      return failureKind;
    }

    /** 到此时为止的耗时。终态一到就取它，因此它是「到收到终态为止」的耗时。 */
    public Duration latency() {
      return Duration.between(start, Instant.now());
    }
  }
}
