package io.github.airouter.archtest.layout.ordinary.providers;

import io.github.airouter.archtest.layout.ordinary.core.Canonical;

/** 普通写法：出站翻译只认识 Canonical 与 transport，不认识任何 face。 */
public final class UpstreamTranslator {

  public String encode(Canonical canonical) {
    return canonical.content();
  }
}
