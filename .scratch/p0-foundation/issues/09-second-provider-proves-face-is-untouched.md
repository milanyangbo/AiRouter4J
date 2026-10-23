# 09: 用第二个 Provider 验证 Face 侧零改动

> 来源：[spec.md](../spec.md) — 用户故事 30

**What to build:** 接入第二个 wire 形态确实不同（帧命名或 usage 字段形态有别）的 Provider，并且整个改动完全落在 Provider 自己的范围内，Face 侧与 `core` 的差异统计为空。

这是 ADR-0005 消除 N×N 那条主张的唯一实证手段。不实际加第二个 Provider，「加 Provider 不用改 Face」就只是一句写在文档里的声明 —— 而来源项目最不缺的就是这类无法被推翻的声明。

**Blocked by:** 06 流式 SSE 路径与显式终态

**Status:** ready-for-agent

- [ ] 接入第二个 Provider，其 wire 形态在至少一个具体方面与第一个不同（例如 SSE 帧命名、usage 字段形状、私有字段位置）
- [ ] 该改动在 Face 侧的文件上没有任何变更（以差异统计断言：本次变更触及的 Face 侧文件数为 0）
- [ ] 该改动在 `core` 上没有任何变更
- [ ] 第一个 Provider 的全部 golden-file 基线保持通过且未被修改
- [ ] 两个 Provider 通过同一个对外接口路径可达，由配置决定走哪一个，而不是代码分支
- [ ] 第二个 Provider 同样具备自己的端对端覆盖：同步与流式两条路径都能通
- [ ] 新增这个 Provider 的过程中没有引入任何新的 Face 侧条件分支 —— 若出现，说明抽象体表错了，应当回来修正而不是接受
