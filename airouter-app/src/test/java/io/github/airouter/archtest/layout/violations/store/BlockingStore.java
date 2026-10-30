package io.github.airouter.archtest.layout.violations.store;

import java.util.List;

/**
 * 违规：包内老老实实用着 MyBatis，但对外返回一个 {@code List} ——
 * 阻塞调用照样发生在调用方的线程上。R10 挡住的是类型，这一件由 R11 挡。
 */
public final class BlockingStore {

  public List<String> latest() {
    return List.of();
  }
}
