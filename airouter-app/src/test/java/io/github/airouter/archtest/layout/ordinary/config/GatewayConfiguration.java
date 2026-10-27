package io.github.airouter.archtest.layout.ordinary.config;

import io.github.airouter.archtest.layout.ordinary.faces.InboundTranslator;
import io.github.airouter.archtest.layout.ordinary.providers.UpstreamTranslator;

/** 普通写法：装配层认识所有人 —— 规则里没有任何一条禁止 config 依赖别人。 */
public final class GatewayConfiguration {

  private final InboundTranslator inbound = new InboundTranslator();

  private final UpstreamTranslator upstream = new UpstreamTranslator();

  public String roundTrip(String raw) {
    return upstream.encode(inbound.translate(raw));
  }
}
