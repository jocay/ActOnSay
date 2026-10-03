---
name: system-inspection
description: 检查 Debian 容器的系统版本、运行时间、磁盘、内存与服务状态，输出真实 Markdown 报告。只读巡检，不自动修改系统设置。
requires:
  commands: [python3]
---

# 系统巡检

用户需要检查手机内 Debian 环境健康状态或生成巡检报告时使用。

1. 加载本 Skill 的任务快照。通过 `run_skill_script` 执行 `scripts/inspect.py`，不需要安装任何依赖。
2. 脚本只读取系统信息，将报告写入 `ACTONSAY_ARTIFACT_DIR/system-report.md`。
3. 查看执行结果和真实文件产物，向用户说明磁盘、内存与 systemd 服务的发现。
4. 发现异常时先提出具体修复操作；巡检本身不安装软件、不重启服务。

容器中的指标受 Droidspaces 共享内核与手机运行状态影响，不能将容器报告当作整台手机的完整诊断。
