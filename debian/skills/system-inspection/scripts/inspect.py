import os
import platform
import shutil
import subprocess
from datetime import datetime
from pathlib import Path


def read(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return "无法读取"


def main():
    artifacts = Path(os.environ["ACTONSAY_ARTIFACT_DIR"])
    artifacts.mkdir(parents=True, exist_ok=True)
    usage = shutil.disk_usage("/")
    services = subprocess.run(["systemctl", "--failed", "--no-pager", "--plain"], capture_output=True, text=True, timeout=15, check=False)
    report = f"""# Debian 系统巡检

时间：{datetime.now().astimezone().isoformat()}

- 执行身份：UID {os.getuid()} / GID {os.getgid()}
- 内核：{platform.release()}（容器共享手机内核）
- 架构：{platform.machine()}
- 磁盘：总量 {usage.total // (1024**3)} GiB，可用 {usage.free // (1024**3)} GiB
- 系统运行时间（秒）：{read('/proc/uptime').split()[0]}

## 系统版本

```text
{read('/etc/os-release')}
```

## 内存（容器视图）

```text
{read('/proc/meminfo')}
```

## 失败服务

```text
{services.stdout.strip() or services.stderr.strip() or '未发现失败服务'}
```
"""
    destination = artifacts / "system-report.md"
    destination.write_text(report, encoding="utf-8")
    print(f"Report saved: {destination}")


if __name__ == "__main__":
    main()
