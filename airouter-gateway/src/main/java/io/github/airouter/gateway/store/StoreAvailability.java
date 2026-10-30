package io.github.airouter.gateway.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 「现在有没有库」—— 启动期探测**一次**的结果。
 *
 * <p>为什么是启动期一次，而不是每次调用的时候 {@code try/catch}：
 * 依赖缺席必须是**被定义过的那一档行为**（ADR-0017 第 7 节），
 * 而不是「NPE 之后被某个 catch 吞掉」。按调用兜底会顺手把另一件事一起吞掉 ——
 * SQL 写错了、列不存在、迁移没跑，全都会表现为「这一档没有数据」，
 * 于是坏掉的 SQL 也能静默通过。「库不在」这种**环境事实**与之不同：
 * 它要么整个环境都不在，要么一直在，探测一次得到的答案对所有调用都成立。
 *
 * <p>探测失败时记录原因但不抛出：ADR-0017 第 7 节的表格写得很直白 ——
 * DB 不在的默认行为是**内存档运行、启动时不失败**，
 * 把探测异常抛出等于让「忘了起数据库」变成一次启动失败，那是另一件事。
 */
final class StoreAvailability {

  private final boolean available;

  private final String reason;

  /**
   * @param dataSource 数据源。用它的**连接**判可用性，不用 {@code DataSource} 的存在与否：
   *                   Hikari 这类池化数据源在建对象时并不连库，
   *                   于是在「库不在」的环境里它照样是一个能注入的 bean ——
   *                   拿它的存在当可用性判据，等于永远答「有库」
   */
  StoreAvailability(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    boolean connected = false;
    String failure = null;
    try (Connection connection = dataSource.getConnection()) {
      connected = connection != null;
    } catch (SQLException | RuntimeException ignored) {
      // 连不上就是这一种档位，不是错误。原因留着给诊断看，不抛出去（理由见类注释）。
      failure = "连接数据库失败";
    }
    this.available = connected;
    this.reason = failure;
  }

  /** 库在不在。不在时 store 的读一律走「没有那一版」这一档，而不是抛异常。 */
  boolean available() {
    return available;
  }

  /** 不可用的原因；可用时是 null。给启动日志与诊断用，不参与任何判定。 */
  String reason() {
    return reason;
  }
}
