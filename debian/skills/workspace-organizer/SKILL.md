---
name: workspace-organizer
description: 整理用户指定工作目录中的普通文件，首次先生成移动计划，用户明确开启自动执行后才移动。不删除文件、不覆盖重名文件、不跟随链接。
requires:
  commands: [python3]
---

# 工作目录文件整理

用户需要将某个工作目录的文件按类型整理时使用。

1. 明确目标目录，禁止把 `/`、`/etc`、`/usr`、`/var`、`/root` 或手机共享根目录当作整理目录。
2. 首次执行 `scripts/organize.py <绝对目录>`，生成产物 `organization-plan.json` 和 `organization-report.md`，不会移动文件。
3. 展示计划。只有用户明确允许这一次移动，或用户已给该任务模板开启自动整理，才再执行 `scripts/organize.py <绝对目录> --apply`。
4. 移动仅限该目录中的一级普通文件，按类型放入子目录。所有同名冲突、链接和特殊文件均跳过，结果落盘。
5. 实际执行与计划之间目标文件可能改变，以最终报告为准。不要根据计划宣称整理已完成。
