import argparse
import os
from datetime import datetime
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory")
    args = parser.parse_args()
    directory = Path(args.directory).resolve(strict=True)
    if not directory.is_dir():
        raise ValueError("报告目录不存在")
    artifacts = Path(os.environ["ACTONSAY_ARTIFACT_DIR"])
    artifacts.mkdir(parents=True, exist_ok=True)
    lines = ["# 工作目录报告", "", f"目录：{directory}", f"生成时间：{datetime.now().astimezone().isoformat()}", "", "| 文件 | 字节数 | 修改时间 |", "|---|---:|---|"]
    count = 0
    for root, folders, files in os.walk(directory, followlinks=False):
        folders[:] = sorted(folder for folder in folders if not (Path(root) / folder).is_symlink() and folder != ".git")
        for name in sorted(files):
            file = Path(root) / name
            if file.is_symlink() or not file.is_file():
                continue
            info = file.stat()
            relative = file.relative_to(directory).as_posix().replace("|", " ")
            lines.append(f"| {relative} | {info.st_size} | {datetime.fromtimestamp(info.st_mtime).astimezone().isoformat()} |")
            count += 1
            if count >= 2000:
                break
        if count >= 2000:
            lines += ["", "文件数量较多，本报告最多列出 2000 个文件。"]
            break
    destination = artifacts / "workspace-report.md"
    destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Report saved: {destination}")


if __name__ == "__main__":
    main()
