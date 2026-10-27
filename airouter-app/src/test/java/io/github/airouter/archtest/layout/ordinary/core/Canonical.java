package io.github.airouter.archtest.layout.ordinary.core;

/** 普通写法：领域模型。谁都可以用它，它不依赖任何人。 */
public final class Canonical {

  private final String content;

  public Canonical(String content) {
    this.content = content;
  }

  public String content() {
    return content;
  }
}
