# Issue tracker: GitHub

规格和任务存放在 `wuzh7/image-admin` 的 GitHub Issues 中。进入本仓库后使用 `gh` CLI 操作，仓库地址从 Git remote 自动推断。

## 常用操作

- 创建：`gh issue create --title "..." --body "..."`
- 查看：`gh issue view <编号> --comments`
- 列表：`gh issue list --state open`
- 评论：`gh issue comment <编号> --body "..."`
- 标签：`gh issue edit <编号> --add-label "..."` 或 `--remove-label "..."`
- 关闭：`gh issue close <编号> --comment "..."`

Skill 要求“发布到 Issue Tracker”时创建 GitHub Issue；要求读取任务时查看对应 Issue 及评论。

## 约定

- Pull Request 不进入 triage 队列。
- `wayfinder` 使用父 Issue、子 Issue 和 GitHub 原生依赖关系；功能不可用时，在 Issue 正文中记录 `Blocked by: #<编号>`。
