package io.github.airouter.gateway.store;

import java.time.Instant;
import java.util.Objects;

/**
 * 一份**不可变**的配置快照（ADR-0010 第 6 节）。
 *
 * <p>每次配置变更产生一版，带单调递增的版本号。它回答两个问题：
 * 现在生效的是哪一版、能回滚到哪一版。
 * 多副本之间广播的是「变更事件 + 版本号」，**不是全量配置** ——
 * 所以正文留在库里，不跟着事件走（来源项目的 {@code HttpConfigPushService}
 * 恰恰是把全量配置推给一个并不存在的端点，ADR-0017 第 8 节裁决不搬）。
 *
 * <p>正文在这里是**字符串**，不是解析好的对象树：这一版的形状由当时的配置模型决定，
 * 而配置模型会随阶段演进。让存储层去认识某一版模型的字段，
 * 等于给「回滚到旧版」这件事加上一层必须同步演进的翻译 ——
 * 存原文、由那个版本自己的模型去读，回滚才不会卡在反序列化上。
 *
 * @param version 版本号，单调递增且唯一。它同时是回滚的坐标
 * @param content 快照正文
 * @param createdAt 这一版产生的时间
 * @param actor 是谁带来的这次变更（空串表示启动期自动产生，没有人对它负责）
 */
public record ConfigSnapshot(long version, String content, Instant createdAt, String actor) {

  public ConfigSnapshot {
    if (version <= 0L) {
      throw new IllegalArgumentException("配置版本号必须为正，收到的是 " + version + "："
          + "它是回滚的坐标，从 0 开始会让「上一版」与「没有上一版」无法区分");
    }
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(actor, "actor");
  }
}
