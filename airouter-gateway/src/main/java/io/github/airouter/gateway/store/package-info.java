/**
 * 库与 Redis 的读写。
 *
 * <p>知道：SQL、迁移版本、连接坐标。不该知道：谁在用它 ——
 * 它不被 {@code faces} / {@code providers} 直接依赖（ADR-0003 第 2 节），
 * 请求路径上不该出现「读一次库才能决定怎么转发」这种事。
 *
 * <p>这个包是**阻塞 API 唯一的合法住所**。MyBatis 是这套栈里唯一的同步 IO，
 * 因此它的类型（mapper 接口、{@code SqlSession}）不许越出这个包 —— 由结构规则 **R10** 判红。
 * 包对外只暴露 {@code Mono} / {@code Flux}：每次调用都过
 * {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}，
 * 否则就是把 JPA 的隐形阻塞换成 MyBatis 的显式阻塞，什么都没换来（ADR-0017 第 2 节纪律 6）。
 * 这一条由 **R11** 判红。
 *
 * <p>SQL 只写在 XML mapper 里，与 Flyway 迁移放在一起评审：
 * mapper 里引用了哪一列，就要有哪一次的迁移（0017 第 4 节）。
 *
 * <p>「库不在」不是异常，是**一种被定义过的档位**（0017 第 7 节）：
 * {@link io.github.airouter.gateway.store.StoreAvailability} 在启动期探测一次，
 * 探测失败 → 迁移不跑、store 的读返回「没有那一版」而不是抛连接异常。
 * 探测只做一次而不是每次调用 catch，是因为后者会把「SQL 写错了」与「库不在」
 * 混成同一件事，于是坏掉的 SQL 也能静默通过。
 */
package io.github.airouter.gateway.store;
