import argparse
import json
import os
from datetime import datetime
from pathlib import Path


CATEGORIES = {
    ".pdf": "documents", ".docx": "documents", ".md": "documents", ".txt": "documents",
    ".csv": "tables", ".xlsx": "tables", ".json": "data", ".yaml": "data", ".yml": "data",
    ".png": "images", ".jpg": "images", ".jpeg": "images", ".webp": "images",
    ".mp3": "audio", ".wav": "audio", ".mp4": "video", ".zip": "archives", ".tar": "archives", ".gz": "archives",
}
FORBIDDEN = {"/", "/etc", "/usr", "/var", "/root", "/home", "/opt", "/data", "/sdcard", "/storage", "/mnt", "/mnt/shared"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    directory = Path(args.directory).resolve(strict=True)
    if not directory.is_dir() or str(directory) in FORBIDDEN or len(directory.parts) < 3:
        raise ValueError("请选择具体工作目录，禁止整理系统和共享根目录")
    artifacts = Path(os.environ["ACTONSAY_ARTIFACT_DIR"])
    artifacts.mkdir(parents=True, exist_ok=True)
    entries = []
    for source in sorted(directory.iterdir()):
        if source.is_symlink() or not source.is_file() or source.name.startswith("."):
            continue
        category = CATEGORIES.get(source.suffix.lower(), "other")
        folder = directory / category
        destination = folder / source.name
        if folder.is_symlink() or destination.exists() or destination.is_symlink():
            entries.append({"source": str(source), "destination": str(destination), "status": "skipped_conflict"})
            continue
        status = "planned"
        if args.apply:
            folder.mkdir(exist_ok=True)
            # 硬链接原子保留“不覆盖”语义，再删除原目录项，不读取/复制内容。
            # 同目录属于同一文件系统，创建失败就保留原文件。
            try:
                os.link(source, destination, follow_symlinks=False)
                source.unlink()
                status = "moved"
            except OSError:
                status = "failed_preserved_source"
        entries.append({"source": str(source), "destination": str(destination), "status": status})
    plan = {"directory": str(directory), "applied": args.apply, "time": datetime.now().astimezone().isoformat(), "operations": entries}
    (artifacts / "organization-plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# 文件整理报告", "", f"目标目录：{directory}", f"模式：{'实际执行' if args.apply else '预览计划，未移动文件'}", "", "| 原文件 | 目标文件 | 状态 |", "|---|---|---|"]
    lines += [f"| {Path(item['source']).name.replace('|', ' ')} | {Path(item['destination']).relative_to(directory).as_posix().replace('|', ' ')} | {item['status']} |" for item in entries]
    (artifacts / "organization-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Organization {'applied' if args.apply else 'planned'}: {len(entries)} files")


if __name__ == "__main__":
    main()
