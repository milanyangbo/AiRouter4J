package io.github.airouter.archtest.layout.violations.faces;

import io.github.airouter.archtest.layout.violations.providers.UpstreamCodec;

/** 违规样例（R3a）：入站协议翻译直接用了出站协议的类。真实代码里这等于「加 provider 要回来改 face」。 */
public final class InboundCodec {

  private final UpstreamCodec upstream = new UpstreamCodec();

  public String decode() {
    return upstream.encode();
  }
}
