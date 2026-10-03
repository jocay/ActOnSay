from __future__ import annotations

import os
import re
import secrets
import stat
from dataclasses import dataclass
from pathlib import Path


IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")


def identifier(value: str) -> str:
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value) or value in {".", ".."}:
        raise ValueError("标识仅允许字母、数字、下划线、点和短横线，最多 128 个字符")
    return value


def contained_path(root: Path, relative: str, *, must_exist: bool = True) -> Path:
    # 根目录本身和祖先也必须保持实体目录，防止脚本替换整个产物目录为链接。
    for ancestor in (root, *root.parents):
        if ancestor.is_symlink():
            raise ValueError("不允许通过符号链接访问目录")
    root = root.resolve()
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts or "\\" in relative:
        raise ValueError("路径必须是目录内的相对路径")
    candidate = root / path
    current = root
    for part in path.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("不允许通过符号链接读取文件")
    resolved = candidate.resolve(strict=must_exist)
    if not resolved.is_relative_to(root):
        raise ValueError("路径超出允许目录")
    return resolved


def open_contained(root: Path, relative: str, max_bytes: int):
    """逐级 openat + NOFOLLOW，避免校验后文件/父目录被替换成链接。"""
    contained_path(root, relative)
    parts = Path(relative).parts
    if not parts:
        raise ValueError("请选择文件")
    directory_fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    file_fd = None
    try:
        for part in (*root.absolute().parts[1:], *parts[:-1]):
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        file_fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory_fd)
        info = os.fstat(file_fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > max_bytes:
            raise ValueError("产物不是普通文件或超出下载限额")
        stream = os.fdopen(file_fd, "rb")
        file_fd = None
        return stream, info.st_size
    finally:
        os.close(directory_fd)
        if file_fd is not None:
            os.close(file_fd)


@dataclass(frozen=True)
class Config:
    data_dir: Path
    token_file: Path
    timezone: str = "Asia/Shanghai"
    max_executions: int = 2
    max_log_bytes: int = 5 * 1024 * 1024
    max_artifact_bytes: int = 50 * 1024 * 1024
    max_skill_bytes: int = 50 * 1024 * 1024

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            Path(os.environ.get("ACTONSAY_DATA_DIR", "/var/lib/actonsay-bridge")),
            Path(os.environ.get("ACTONSAY_TOKEN_FILE", "/etc/actonsay-bridge/token")),
            os.environ.get("ACTONSAY_TIMEZONE", "Asia/Shanghai"),
        )

    def prepare(self) -> str:
        os.umask(0o077)
        for path in (self.data_dir, self.data_dir / "tasks", self.data_dir / "skills", self.data_dir / "imports"):
            path.mkdir(parents=True, exist_ok=True, mode=0o700)
            path.chmod(0o700)
        self.token_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not self.token_file.exists():
            # 原子创建，已有配对令牌在升级时保持不变。
            fd = os.open(self.token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as output:
                output.write(secrets.token_urlsafe(48) + "\n")
        self.token_file.chmod(0o600)
        token = self.token_file.read_text().strip()
        if len(token) < 32:
            raise RuntimeError("Pairing token is invalid")
        return token


class Redactor:
    def __init__(self, token: str):
        self.values = {token}

    def register(self, values: dict[str, str] | None) -> None:
        if values:
            self.values.update(str(value) for value in values.values() if len(str(value)) >= 4)
            for value in values.values():
                text = str(value)
                if text.lower().startswith(("bearer ", "basic ")) and len(text.split(" ", 1)[1]) >= 4:
                    self.values.add(text.split(" ", 1)[1])

    def text(self, value: str) -> str:
        for secret in sorted(self.values, key=len, reverse=True):
            value = value.replace(secret, "[REDACTED]")
        return re.sub(
            r"(?i)(authorization\s*[:=]\s*(?:bearer\s+)?|(?:api[_-]?key|token|password|secret)\s*[:=]\s*)[^\s,;]+",
            r"\1[REDACTED]", value,
        )

    def json(self, value):
        if isinstance(value, str):
            return self.text(value)
        if isinstance(value, list):
            return [self.json(item) for item in value]
        if isinstance(value, dict):
            return {key: ("[REDACTED]" if re.search(r"(?i)token|password|secret|authorization|api[_-]?key", key) else self.json(item)) for key, item in value.items()}
        return value
