# 03: 流式打通（正常路径）

**What to build:** 一条流式请求以 SSE 逐块拿到内容，并收到 `[DONE]` 哨兵；
终态是这条流的最后一个事件。

本票只走**正常路径** —— 上游好好说话、好好结束。异常终态（不讲完、静默、取消）
是 04 的事，分开是为了让「正常路径正确」与「异常路径被判对」各自能单独定位。

**Blocked by:** 02

**Status:** ready-for-agent

## Acceptance criteria

- [ ] `CanonicalStreamEvent` 是 sealed，实现层六型（`Opening` / `TextDelta` / `Usage` /
      `Ping` / `Terminal` / `Failed`）。`ToolCallDelta` **不引入** ——
      工具调用的增量形态取决于上游真实怎么发，现阶段没有能稳定复现它的端点
- [ ] `StreamingCompletionPort` 返回 `Flow.Publisher`（不是 `Mono` / `Flux`，
      Reactor 进 `core` 的依赖树会被 enforcer 判红）
- [ ] SSE 分帧器就位
- [ ] SLO 三角（`idleTimeout` × 最大累积内容 × 入站/出站缓冲区）三者**全部显式配置**
- [ ] 流式请求逐块收到内容，并收到 `[DONE]`
- [ ] 终态是这条流的**最后一个**事件；终态之后的帧不出现在客户端
- [ ] `contract-stream/` 与其 README 就位，比的是**帧序列**且包含 `[DONE]` 哨兵本身
      （哨兵的存废是一个被断言的事实）
- [ ] 流式与非流式的**延迟可比** —— 两者都算到收到终态为止
- [ ] 背压向两端传导
- [ ] 非流式与流式走**同一条路径**（流式不绕过治理）
- [ ] 对 `text/event-stream` 的下游响应**不设** `ResponseTimeout`
      （否则长流会被基础设施掐断）
- [ ] `idleTimeout` 是**块间**时长，不是总时长 —— 一条想很久的流不会被误杀

## Notes

- 接缝是 HTTP 入口，上游是能控制到字节块一级的录制器
- 本票不含异常终态与结算纪律（04），也不含字节层不变量（05）
- 测试文件并入模块预算，需合并同类项
