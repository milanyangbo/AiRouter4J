# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`（当前唯一的一处是 `.scratch/p0-foundation/`）
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, never a single combined tickets file
- **票号是全仓库一份序列**：`.scratch/<slug>/` 只按功能分目录，**编号不按目录回到 `01`** ——
  否则「票 01」会指向两张票，而票号的意义正是「有没有授权」的判据，含糊就等于没有判据。
  新 slug 的第一张票从**当前最大号 + 1** 开始（`.scratch/selection-chain/` 因此是 `08` 起）。
  跨 slug 重复由 `TicketReferencesExistTest` 的第三条断言判红
- Comments and conversation history append to the bottom of the file under a `## Comments` heading
- **没有票文件 = 没有授权。** 编号取自目录里真实存在的文件，不从上一个数字往下续 ——
  自己续出来的编号是一个不存在的编号。执行侧的硬约束与违约处置见根 `AGENTS.md` 的「先有票，再动手」

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## review 前要核对的一件事

审一段 diff 之前，先问**它的票文件是哪一个**，并确认那个文件真的存在：

- 指不出票文件 → 这次改动没有被授权。先停下来问，不要审完再补一份文档
- 指出的编号在 `.scratch/<slug>/issues/` 里没有对应文件 → 那是一个不存在的编号

文档里**写着的**票号由 `airouter-app` 的 `TicketReferencesExistTest` 在构建里判红
（悬空引用与缺号各一条）。它扫不到提交信息与对话 —— 那一半归 review 这一步，
因为 review 阶段的上下文压力最小，适合施加这类标准。

## 与 ADR-0020 的分工

[ADR-0020](../adr/0020-stage-plan-and-task-board.md) 是**阶段排期与任务清单**的权威，它取代的是旧票
（`01`–`13` 那种编号文件），不是本目录。

职责划分：

| 产物 | 住哪 | 说明 |
|---|---|---|
| 阶段排期 / 任务清单 | ADR-0020 | 一份清单，不再按阶段开文件 |
| **功能设计（spec）** | `.scratch/<feature-slug>/spec.md` | 与排期不重复：spec 讲一个功能**长什么样**，排期讲**什么时候做** |

旧票目录 `.scratch/p0-foundation/` 已于 2026-10-26 删除。
