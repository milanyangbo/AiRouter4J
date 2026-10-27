package io.github.airouter.archtest.layout.ordinary.transport;

import io.github.airouter.archtest.layout.ordinary.core.Canonical;

/** 普通写法：出站管道只认识 Canonical 与 HTTP，不知道路由与治理。 */
public final class HttpOutbound {

  public String send(Canonical request) {
    return request.content();
  }
}
