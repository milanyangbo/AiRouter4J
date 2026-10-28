package io.github.airouter.gateway.select;

import io.github.airouter.gateway.routing.SelectionContext;
import java.util.Optional;

/**
 * 粘滞用哪个键来认「这次调用算谁的」。
 *
 * <p>默认答案是第 ① 步的**调用方标识**，自定义键（某个请求头、会话 id）优先 ——
 * 这一条还没有来源：它随认证（阶段 6 / ADR-0018）明确，那时多一个实现即可，
 * 包装器一行都不用改。做成接口而不是一段 {@code if} 的理由就是这个：
 * 「键从哪来」与「粘滞怎么用键」是两件会各自变化的事。
 */
@FunctionalInterface
public interface StickyKeyResolver {

  /**
   * @param context 这一次调用交给选择链的那份输入
   * @return 键；**为空表示认不出这次调用算谁的** —— 与「键恰好是某个字符串」是两种含义，
   *     混起来会让「没有调用方」变成「所有人粘在同一个实例上」
   */
  Optional<String> keyOf(SelectionContext context);

  /**
   * 默认的那一份：键 = 调用方标识，**空白按缺失算**（它是省略，不是另一个键）。
   *
 * <p>它每一次选择都会被调用一次，因此必须是轻的、无副作用的：
 * 键解析沿着这条路进到了每一个请求，任何一次远程查询都会把选择的成本变成不会被察觉的依赖。
   */
  static StickyKeyResolver callerIdentity() {
    return context -> Optional.ofNullable(context.callerId()).filter(id -> !id.isBlank());
  }
}
