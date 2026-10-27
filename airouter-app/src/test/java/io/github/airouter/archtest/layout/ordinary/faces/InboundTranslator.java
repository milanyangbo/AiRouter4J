package io.github.airouter.archtest.layout.ordinary.faces;

import io.github.airouter.archtest.layout.ordinary.core.Canonical;

/** 普通写法：入站翻译只认识 Canonical，不认识任何 provider。 */
public final class InboundTranslator {

  public Canonical translate(String raw) {
    return new Canonical(raw);
  }
}
