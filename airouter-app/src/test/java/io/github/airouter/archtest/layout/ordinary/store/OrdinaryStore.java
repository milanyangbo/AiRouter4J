package io.github.airouter.archtest.layout.ordinary.store;

import reactor.core.publisher.Mono;

/** 普通写法：store 的 public 方法只返回 {@code Mono}。 */
public final class OrdinaryStore {

  public Mono<String> latest() {
    return Mono.empty();
  }
}
