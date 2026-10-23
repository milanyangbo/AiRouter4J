package io.github.airouter.archtest.layout.violations.providers;

import io.github.airouter.archtest.layout.violations.faces.InboundCodec;

/** 违规样例（R3b）：出站协议翻译回头去用入站协议的类。 */
public final class UpstreamCodec {

  public String encode() {
    return "upstream";
  }

  public InboundCodec backReference() {
    return new InboundCodec();
  }
}
