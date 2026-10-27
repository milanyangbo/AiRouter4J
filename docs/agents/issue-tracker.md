# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`（当前唯一的一处是 `.scratch/p0-foundation/`）
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`, never a single combined tickets file
- Comments and conversation history append to the bottom of the file under a `## Comments` heading
- **没有票文件 = 没有授权。** 编号取自目录里真实存在的文件，不从上一个数字往下续 ——
  自己续出来的编号是一个不存在的编号。执行侧的硬约束与违约处置见根 `AGENTS.md` 的「先有票，再动手」

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## 与 ADR-0020 的分工

[ADR-0020](../adr/0020-stage-plan-and-task-board.md) 是**阶段排期与任务清单**的权威，它取代的是旧票
（`01`–`13` 那种编号文件），不是本目录。

职责划分：

| 产物 | 住哪 | 说明 |
|---|---|---|
| 阶段排期 / 任务清单 | ADR-0020 | 一份清单，不再按阶段开文件 |
| **功能设计（spec）** | `.scratch/<feature-slug>/spec.md` | 与排期不重复：spec 讲一个功能**长什么样**，排期讲**什么时候做** |

旧票目录 `.scratch/p0-foundation/` 已于 2026-10-26 删除。
