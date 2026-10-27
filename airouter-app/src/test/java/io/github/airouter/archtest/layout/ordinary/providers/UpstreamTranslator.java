package io.github.airouter.archtest.layout.ordinary.providers;

import io.github.airouter.archtest.layout.ordinary.core.Canonical;

/** 普通写法：出站翻译只认识 Canonical，不认识入站协议。 */
public final class UpstreamTranslator {

  public String encode(Canonical request) {
    return request.content();
  }
}
