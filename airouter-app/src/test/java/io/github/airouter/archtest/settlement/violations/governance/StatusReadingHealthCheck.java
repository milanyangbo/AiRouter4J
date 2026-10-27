package io.github.airouter.archtest.settlement.violations.governance;

import org.springframework.http.HttpStatus;

/**
 * 违规样例（R4 的「看不到 HTTP 状态码」那一半）：治理逻辑自己读状态码决定成败。
 *
 * <p>它看起来很无害 —— 只是问一句「是不是 5xx」。危害在于这一句一旦存在，
 * 这个组件的成败口径就与结算点的口径是**两个**，而两个口径不会长期一致。
 * 这正是来源项目那个缺陷的形状：流式在响应头写出时就判成功，治理在别处判失败。
 */
public final class StatusReadingHealthCheck {

  public boolean healthy(int status) {
    return status < HttpStatus.INTERNAL_SERVER_ERROR.value();
  }
}
