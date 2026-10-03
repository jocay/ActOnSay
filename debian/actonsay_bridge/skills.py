from __future__ import annotations

import asyncio
import base64
import hashlib
import importlib.metadata
import io
import json
import os
import re
import shutil
import shlex
import signal
import stat
import sys
import tempfile
import uuid
import zipfile
from pathlib import Path
from urllib.parse import urlsplit

import yaml

from .config import Config, contained_path, identifier
from .store import Store, encode, now


class Skills:
    def __init__(self, config: Config, store: Store):
        self.config, self.store = config, store

    def get(self, skill_id: str) -> dict:
        row = self.store.one("SELECT * FROM skills WHERE id=?", (skill_id,))
        if not row:
            raise KeyError("Skill 不存在")
        return json.loads(row["definition"])

    def public(self, definition: dict) -> dict:
        return {key: value for key, value in definition.items() if key not in {"path"}}

    def list(self) -> list[dict]:
        return [self.public(json.loads(row["definition"])) for row in self.store.all("SELECT definition FROM skills ORDER BY created_at")]

    def inspect(self, root: Path) -> tuple[dict, str]:
        skill_file = contained_path(root, "SKILL.md")
        if not skill_file.is_file() or skill_file.stat().st_size > 128 * 1024:
            raise ValueError("SKILL.md 缺失或大于 128 KiB")
        text = skill_file.read_text(encoding="utf-8")
        match = re.match(r"\A---\s*\n(.*?)\n---(?:\s*\n|$)", text, re.S)
        if not match:
            raise ValueError("SKILL.md 需要包含 name 和 description 的 YAML 前置内容")
        try:
            metadata = yaml.safe_load(match.group(1))
        except yaml.YAMLError:
            raise ValueError("SKILL.md 的 YAML 前置内容格式不合法") from None
        if not isinstance(metadata, dict):
            raise ValueError("Skill 元数据必须是对象")
        try:
            metadata = json.loads(json.dumps(metadata, ensure_ascii=False, default=str))
        except (ValueError, RecursionError):
            raise ValueError("Skill 元数据存在循环引用或过深嵌套") from None
        name = str(metadata.get("name", ""))
        description = str(metadata.get("description", ""))
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) or len(name) > 64:
            raise ValueError("Skill 名称使用不超过 64 字符的小写字母、数字和短横线")
        if not description.strip() or len(description) > 1024:
            raise ValueError("Skill 描述为空或超过 1024 字符")
        checksum = hashlib.sha256()
        total, count = 0, 0
        for directory, folders, files in os.walk(root, followlinks=False):
            folders[:] = sorted(folder for folder in folders if folder != ".git")
            for entry in folders + files:
                if (Path(directory) / entry).is_symlink():
                    raise ValueError("Skill 不允许符号链接")
            for filename in sorted(files):
                path = Path(directory) / filename
                if not path.is_file():
                    raise ValueError("Skill 不允许特殊文件")
                count += 1
                total += path.stat().st_size
                if count > 2000 or total > self.config.max_skill_bytes:
                    raise ValueError("Skill 超过 2000 个文件或 50 MiB 限额")
                checksum.update(path.relative_to(root).as_posix().encode())
                with path.open("rb") as source:
                    while chunk := source.read(65536):
                        checksum.update(chunk)
        metadata["name"], metadata["description"] = name, description
        return metadata, checksum.hexdigest()

    def dependency_status(self, metadata: dict) -> dict:
        extra = metadata.get("metadata") or {}
        requires = metadata.get("requires") or (extra.get("requires") if isinstance(extra, dict) else None) or {}
        if not isinstance(requires, dict):
            return {"missing": [], "declared": requires, "note": "依赖声明无法自动解析，请阅读 Skill 正文"}
        missing = []
        for command in requires.get("commands", []):
            if not shutil.which(str(command)):
                missing.append({"kind": "command", "name": str(command)})
        for package in requires.get("python_packages", []):
            try:
                importlib.metadata.version(str(package))
            except importlib.metadata.PackageNotFoundError:
                missing.append({"kind": "python_package", "name": str(package)})
        for key in requires.get("env", []):
            if not os.environ.get(str(key)):
                missing.append({"kind": "env", "name": str(key)})
        return {"declared": requires, "missing": missing}

    def copy_checked(self, source: Path, destination: Path):
        self.inspect(source)
        shutil.copytree(source, destination, ignore=shutil.ignore_patterns(".git"), symlinks=False)
        self.inspect(destination)
        for directory, folders, files in os.walk(destination):
            Path(directory).chmod(0o700)
            for filename in files:
                (Path(directory) / filename).chmod(0o600)

    def save_directory(self, root: Path, kind: str, source: str, *, ref: str | None = None, replace_id: str | None = None, builtin: bool = False) -> dict:
        metadata, version = self.inspect(root)
        previous = self.get(replace_id) if replace_id else None
        for item in self.list():
            if item["name"] == metadata["name"] and item["id"] != replace_id:
                raise ValueError("已存在同名 Skill，请使用更新入口")
        skill_id = replace_id or str(uuid.uuid4())
        version_root = self.config.data_dir / "skills" / skill_id / "versions" / version
        version_root.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not version_root.exists():
            staging = version_root.parent / f".import-{uuid.uuid4()}"
            try:
                self.copy_checked(root, staging)
                os.replace(staging, version_root)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
        definition = {"id": skill_id, "name": metadata["name"], "description": metadata["description"], "enabled": previous["enabled"] if previous else True, "source_kind": kind, "source": source, "ref": ref, "version": version, "path": str(version_root), "metadata": metadata, "dependencies": self.dependency_status(metadata), "builtin": builtin, "updated_at": now()}
        self.store.execute("INSERT INTO skills(id,definition,created_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET definition=excluded.definition", (skill_id, encode(definition), now()))
        self.store.event("skill.updated", skill_id, {"name": definition["name"], "version": version})
        return self.public(definition)

    def unzip(self, archive: zipfile.ZipFile, root: Path):
        members = archive.infolist()
        if len(members) > 2000 or sum(item.file_size for item in members) > self.config.max_skill_bytes:
            raise ValueError("ZIP 超过 2000 项或解压后 50 MiB")
        seen = set()
        for item in members:
            if item.flag_bits & 1:
                raise ValueError("暂不支持加密 ZIP")
            relative = item.filename
            if not relative or relative.startswith("/") or "\\" in relative or re.match(r"^[a-zA-Z]:", relative):
                raise ValueError("ZIP 路径不合法")
            target = contained_path(root, relative, must_exist=False)
            if str(target) in seen:
                raise ValueError("ZIP 包含重复路径")
            seen.add(str(target))
            mode = (item.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in {0, stat.S_IFREG, stat.S_IFDIR}):
                raise ValueError("ZIP 包含符号链接或特殊文件")
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
            else:
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with archive.open(item) as source, target.open("xb") as output:
                    copied = 0
                    while chunk := source.read(65536):
                        copied += len(chunk)
                        if copied > item.file_size or copied > self.config.max_skill_bytes:
                            raise ValueError("ZIP 解压大小与声明不符")
                        output.write(chunk)

    async def clone(self, source: str, root: Path, ref: str | None):
        url = urlsplit(source)
        if url.scheme not in {"https", "http", "ssh"} or not url.hostname or url.password or url.query or url.fragment or (url.username and url.scheme != "ssh"):
            raise ValueError("Git 来源必须是 HTTPS、HTTP 或 SSH URL，认证通过容器配置，地址中不得包含凭据")
        if ref and (ref.startswith("-") or "\0" in ref or len(ref) > 200):
            raise ValueError("Git 引用不合法")
        args = ["git", "clone", "--depth", "1", "--no-recurse-submodules"]
        if ref:
            args += ["--branch", ref]
        args += ["--", source, str(root)]
        env = {**os.environ, "GIT_TERMINAL_PROMPT": "0", "GIT_CONFIG_COUNT": "2", "GIT_CONFIG_KEY_0": "core.hooksPath", "GIT_CONFIG_VALUE_0": "/dev/null", "GIT_CONFIG_KEY_1": "protocol.file.allow", "GIT_CONFIG_VALUE_1": "never"}
        process = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, env=env, start_new_session=True)
        try:
            code = await asyncio.wait_for(process.wait(), 120)
            if code:
                raise ValueError("Git 拉取失败，请检查地址、引用、网络及容器认证配置")
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            await process.wait()
            raise

    async def import_skill(self, request: dict) -> dict:
        kind = request["kind"]
        source = str(request.get("source", ""))
        if not source or len(source) > 2048:
            raise ValueError("请填写来源路径或 Git URL")
        with tempfile.TemporaryDirectory(prefix="skill-", dir=self.config.data_dir / "imports") as temporary:
            root = Path(temporary)
            if kind == "directory":
                source_root = Path(source).expanduser().resolve(strict=True)
                if not source_root.is_dir():
                    raise ValueError("来源不是目录")
            elif kind == "zip":
                unpack = root / "unpack"
                unpack.mkdir(mode=0o700)
                if request.get("data_base64"):
                    data = str(request["data_base64"])
                    if len(data) > 28 * 1024 * 1024:
                        raise ValueError("上传 ZIP 超过 20 MiB")
                    raw = base64.b64decode(data, validate=True)
                    if len(raw) > 20 * 1024 * 1024:
                        raise ValueError("上传 ZIP 超过 20 MiB")
                    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                        self.unzip(archive, unpack)
                else:
                    archive_path = Path(source).expanduser().resolve(strict=True)
                    if archive_path.stat().st_size > 20 * 1024 * 1024:
                        raise ValueError("ZIP 超过 20 MiB")
                    with zipfile.ZipFile(archive_path) as archive:
                        self.unzip(archive, unpack)
                if (unpack / "SKILL.md").is_file():
                    source_root = unpack
                else:
                    choices = [path for path in unpack.iterdir() if path.is_dir() and (path / "SKILL.md").is_file()]
                    if len(choices) != 1:
                        raise ValueError("ZIP 根目录或唯一子目录必须包含 SKILL.md")
                    source_root = choices[0]
                if request.get("data_base64"):
                    # 保存上传包供显式更新查看来源，升级不丢失。
                    uploaded = self.config.data_dir / "imports" / f"uploaded-{uuid.uuid4()}.zip"
                    uploaded.write_bytes(raw)
                    source = str(uploaded)
            elif kind == "git":
                source_root = root / "repository"
                await self.clone(source, source_root, request.get("ref"))
            else:
                raise ValueError("导入类型必须是 directory、zip 或 git")
            return await asyncio.to_thread(self.save_directory, source_root, kind, source, ref=request.get("ref"), replace_id=request.get("replace_id"))

    async def update(self, skill_id: str) -> dict:
        definition = self.get(skill_id)
        return await self.import_skill({"kind": definition["source_kind"], "source": definition["source"], "ref": definition.get("ref"), "replace_id": skill_id})

    def enable(self, skill_id: str, enabled: bool) -> dict:
        definition = self.get(skill_id)
        definition["enabled"] = enabled
        definition["updated_at"] = now()
        self.store.execute("UPDATE skills SET definition=? WHERE id=?", (encode(definition), skill_id))
        return self.public(definition)

    def delete(self, skill_id: str):
        self.get(skill_id)
        self.store.execute("DELETE FROM skills WHERE id=?", (skill_id,))
        # 版本和任务快照保留，删除只影响后续任务；不会抹除在用脚本。
        self.store.event("skill.deleted", skill_id, {"id": skill_id})

    def snapshot(self, skill_id: str, task_id: str) -> dict:
        identifier(task_id)
        existing = self.store.one("SELECT * FROM skill_snapshots WHERE task_id=? AND skill_id=?", (task_id, skill_id))
        if existing:
            return json.loads(existing["definition"])
        definition = self.get(skill_id)
        if not definition["enabled"]:
            raise ValueError("Skill 已禁用")
        destination = self.config.data_dir / "tasks" / task_id / "skills" / skill_id
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not destination.exists():
            temporary = destination.parent / f".snapshot-{uuid.uuid4()}"
            try:
                self.copy_checked(Path(definition["path"]), temporary)
                os.replace(temporary, destination)
            finally:
                if temporary.exists():
                    shutil.rmtree(temporary)
        snapshot = self.public(definition) | {"task_id": task_id, "cwd": str(destination), "path": str(destination)}
        self.store.execute("INSERT OR IGNORE INTO skill_snapshots(task_id,skill_id,definition,path,created_at) VALUES(?,?,?,?,?)", (task_id, skill_id, encode(snapshot), str(destination), now()))
        row = self.store.one("SELECT definition FROM skill_snapshots WHERE task_id=? AND skill_id=?", (task_id, skill_id))
        return json.loads(row["definition"])

    def read(self, skill_id: str, relative: str, task_id: str | None) -> dict:
        definition = self.snapshot(skill_id, task_id) if task_id else self.get(skill_id)
        path = contained_path(Path(definition["path"]), relative)
        if not path.is_file() or path.stat().st_size > 256 * 1024:
            raise ValueError("只能读取不超过 256 KiB 的文本文件")
        return {"id": skill_id, "path": relative, "text": path.read_text(encoding="utf-8"), "version": definition["version"], "cwd": definition["path"], "task_id": task_id}

    def script_request(self, skill_id: str, request: dict) -> dict:
        snapshot = self.snapshot(skill_id, request["task_id"])
        relative = str(request["path"])
        if not relative.startswith("scripts/"):
            raise ValueError("只能执行 scripts/ 内的脚本")
        path = contained_path(Path(snapshot["path"]), relative)
        if not path.is_file():
            raise ValueError("脚本不存在")
        interpreter = {".py": sys.executable, ".sh": "/bin/bash"}.get(path.suffix)
        if not interpreter:
            raise ValueError("首版支持 Python 和 Bash 脚本")
        args = request.get("args", [])
        if not isinstance(args, list) or len(args) > 128 or any(not isinstance(value, str) or "\0" in value for value in args):
            raise ValueError("args 必须是字符串数组")
        missing = self.dependency_status(snapshot["metadata"])["missing"]
        supplied_env = request.get("env") or {}
        missing = [item for item in missing if item["kind"] != "env" or not supplied_env.get(item["name"])]
        if missing:
            names = "、".join(f"{item['kind']}: {item['name']}" for item in missing)
            raise ValueError(f"Skill 缺少依赖：{names}。请创建可见的依赖安装任务后再执行")
        return {"request_id": request["request_id"], "task_id": request["task_id"], "command": shlex.join([interpreter, str(path), *args]), "cwd": snapshot["cwd"], "env": request.get("env", {}), "timeout_seconds": request.get("timeout_seconds", 900)}

    async def builtins(self):
        source = Path(__file__).resolve().parent.parent / "skills"
        if not source.exists():
            return
        existing = {item["name"]: item for item in self.list()}
        for path in source.iterdir():
            if path.is_dir() and (path / "SKILL.md").exists():
                metadata, version = self.inspect(path)
                previous = existing.get(metadata["name"])
                if previous is None:
                    self.save_directory(path, "directory", str(path), builtin=True)
                elif previous.get("builtin") and (previous["version"] != version or previous["source"] != str(path)):
                    # 内置 Skill 随服务升级；来源指向当前版本目录，已有任务仍使用各自快照。
                    self.save_directory(path, "directory", str(path), replace_id=previous["id"], builtin=True)
