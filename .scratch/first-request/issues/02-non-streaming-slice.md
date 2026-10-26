# 02: 非流式打通

**What to build:** 一条非流式请求穿过网关拿到响应，且响应能被标准 OpenAI 客户端解析。

这是阶段 0–2 的**第一条纵向切口**：入站 Face → Canonical → 出网 Provider → 响应，
一层不落。它独立可验证，不需要等流式那边。

同时它是领域模型与契约测试的落脚点：`core` 的类型在本票里第一次被真实请求使用，
契约测试的发现机制也在本票立起。

**Blocked by:** 01

**Status:** ready-for-agent

## Acceptance criteria

- [ ] `core` 有 `CanonicalRequest` / `CanonicalResponse` / `GatewayError` /
      `UpstreamFailure` / `CompletionPort`（返回 `CompletionStage`）/ `Outcome` / `OutcomeKind`
- [ ] `core` 的依赖树里出现 Spring / Jackson / Reactor 即构建失败（R1 由 enforcer 判红）
- [ ] OpenAI 兼容 Face 与 Provider 就位，且**互不感知**（R3）
- [ ] 标准 OpenAI 客户端发一次非流式请求穿过网关，拿到响应
- [ ] 响应能被标准客户端解析 —— 翻译层的输出是**协议正确**的，不只是「看起来对」
- [ ] 上游返回 4xx/5xx → 收到对应的错误，并收敛成 `GatewayError`
- [ ] 上游地址不可达 → `UPSTREAM_FAILURE`
- [ ] 契约测试发现机制就位：一个用例一个目录，放入样本文件即被发现，
      **不需要改测试代码**；`contract/` 目录与其 README 就位
- [ ] 历史 golden 样本从 tag `baseline-pre-11` 取回落位
- [ ] `contract-ollama/` 的样本文件一并取回落位 ——
      但它的 runner **本阶段不建**（不为尚不存在的形态建 runner）
- [ ] `transport` 不知道业务包（R5）

## Notes

- 本票只做**非流式**那一条路。流式在 03
- 本票不涉及终态与结算（`Outcome` 的类型就位，但结算纪律在 04）
- 接缝取可用的最高层：HTTP 入口。上游用录制器，不是真 Ollama
  （真 Ollama 只用于抓样本，不进常规测试）
- 测试文件并入模块预算，需合并同类项，不按功能一文件地铺开
