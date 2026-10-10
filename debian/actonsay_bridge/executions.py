from __future__ import annotations

import asyncio
import codecs
import json
import mimetypes
import os
import re
import signal
import time
import uuid
from pathlib import Path
from urllib.parse import quote

from .config import Config, Redactor, contained_path, identifier
from .store import Store, encode, now


FINAL = {"succeeded", "failed", "cancelled", "result_unknown"}
# 执行中日志大小的落库间隔（秒）。
LOG_META_INTERVAL = 2.0


def process_stamp(pid: int) -> str | None:
    try:
        # comm 字段可能包含空格和括号，后面的第 20 项才是 starttime。
        return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
    except (OSError, IndexError):
        return None


def boot_id() -> str:
    return Path("/proc/sys/kernel/random/boot_id").read_text().strip()


def orphan_groups(execution_id: str) -> set[int]:
    """按执行环境变量找回启动中途失去记录的进程组；读不到的进程直接跳过。"""
    marker = f"ACTONSAY_EXECUTION_ID={execution_id}".encode()
    own = os.getpgid(0)
    groups = set()
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            if marker in (entry / "environ").read_bytes().split(b"\0"):
                group = os.getpgid(int(entry.name))
                if group != own:
                    groups.add(group)
        except (OSError, ProcessLookupError):
            continue
    return groups


class Executions:
    def __init__(self, config: Config, store: Store, redactor: Redactor):
        self.config, self.store, self.redactor = config, store, redactor
        self.semaphore = asyncio.Semaphore(config.max_executions)
        self.jobs: dict[str, asyncio.Task] = {}
        self.processes: dict[str, asyncio.subprocess.Process] = {}
        self.cancelled: set[str] = set()
        self.closed = False

    def task_dir(self, task_id: str) -> Path:
        return self.config.data_dir / "tasks" / identifier(task_id)

    def run_dir(self, row: dict) -> Path:
        return self.task_dir(row["task_id"]) / "executions" / row["id"]

    def public(self, row: dict) -> dict:
        result = {key: row[key] for key in ("id", "request_id", "task_id", "status", "cwd", "timeout_seconds", "created_at", "started_at", "finished_at", "exit_code", "error_type", "log_size", "schedule_id", "trigger_id")}
        result["log_truncated"] = bool(row["log_truncated"])
        result["artifacts"] = json.loads(row["artifacts"])
        result["artifact_dir"] = str(self.run_dir(row) / "artifacts")
        schedule = self.store.one("SELECT definition FROM schedules WHERE id=?", (row["schedule_id"],)) if row["schedule_id"] else None
        result["label"] = self.redactor.text(json.loads(schedule["definition"])["name"]) if schedule else "Debian 执行"
        result["resolution"] = json.loads(row["resolution"]) if row.get("resolution") else None
        return result

    def resolve(self, execution_id: str, request: dict) -> dict:
        status = request["status"]
        note = str(request["note"]).strip()
        if status not in {"succeeded", "failed", "cancelled"} or not note or len(note) > 4096:
            raise ValueError("请选择成功、失败或取消，并填写不超过 4096 字符的人工核实说明")
        resolution = {"status": status, "note": self.redactor.text(note), "confirmed_at": now(), "source": "user"}
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM executions WHERE id=?", (execution_id,)).fetchone()
            if not row:
                raise KeyError("执行记录不存在")
            if row["status"] != "result_unknown":
                raise ValueError("仅结果待确认的执行允许人工核实")
            db.execute("UPDATE executions SET status=?,error_type='user_resolved',resolution=? WHERE id=?", (status, encode(resolution), execution_id))
            if row["trigger_id"]:
                db.execute("UPDATE triggers SET status=?,updated_at=? WHERE id=? AND execution_id=?", (status, now(), row["trigger_id"], execution_id))
        self.store.event("execution.resolved", execution_id, resolution, row["task_id"])
        return self.public(self.get(execution_id))

    def rerun(self, execution_id: str, request: dict) -> dict:
        row = self.get(execution_id)
        if row["status"] not in FINAL:
            raise ValueError("执行尚未结束，请先明确取消或等待结束")
        if request["request_id"] == row["request_id"]:
            raise ValueError("显式重跑需要新的请求 ID")
        execution = self.submit({"request_id": request["request_id"], "task_id": request["task_id"], "command": row["command"], "cwd": row["cwd"], "env": json.loads(row["env"]), "timeout_seconds": row["timeout_seconds"]}, schedule_id=row["schedule_id"], trigger_id=row["trigger_id"])
        if row["trigger_id"]:
            self.store.execute("UPDATE triggers SET execution_id=?,task_id=?,status=?,updated_at=? WHERE id=?", (execution["id"], execution["task_id"], execution["status"], now(), row["trigger_id"]))
        self.store.event("execution.rerun", execution["id"], {"original_execution_id": execution_id}, execution["task_id"])
        return execution

    def get(self, execution_id: str) -> dict:
        row = self.store.one("SELECT * FROM executions WHERE id=?", (execution_id,))
        if not row:
            raise KeyError("执行记录不存在")
        return row

    async def start(self):
        # 服务崩溃后绝不把执行中的命令重放或标记成功。
        for row in self.store.all("SELECT * FROM executions WHERE status='running'"):
            pid = row["pid"]
            if pid and row["boot_id"] == boot_id() and row["process_start"] == process_stamp(pid):
                try:
                    if os.getpgid(pid) == pid:
                        os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            self.finish(row, "result_unknown", None, "service_interrupted")
        # 启动中途崩溃时进程可能已经开始但没有记下 pid：按环境变量清理残留进程组，结果一律待确认，不再重跑。
        for row in self.store.all("SELECT * FROM executions WHERE status='starting'"):
            for group in orphan_groups(row["id"]):
                try:
                    os.killpg(group, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            self.finish(row, "result_unknown", None, "interrupted_while_starting")
        for row in self.store.all("SELECT * FROM executions WHERE status='queued' ORDER BY created_at"):
            self.enqueue(row["id"])

    def submit(self, request: dict, *, schedule_id: str | None = None, trigger_id: str | None = None) -> dict:
        if self.closed:
            raise ValueError("执行服务正在关闭")
        request_id = identifier(request["request_id"])
        task_id = identifier(request["task_id"])
        command = str(request["command"])
        if not command.strip() or len(command) > 128 * 1024 or "\0" in command:
            raise ValueError("命令为空或超出 128 KiB")
        timeout = int(request.get("timeout_seconds", 900))
        if not 1 <= timeout <= 86400:
            raise ValueError("超时必须在 1 至 86400 秒之间")
        env = request.get("env") or {}
        if not isinstance(env, dict):
            raise ValueError("环境变量必须是对象")
        if len(env) > 128 or any(not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) or "\0" in str(value) or len(str(value)) > 65536 for key, value in env.items()):
            raise ValueError("环境变量格式不合法或超出限额")
        env = {key: str(value) for key, value in env.items()}
        self.redactor.register(env)
        work = self.task_dir(task_id) / "work"
        work.mkdir(parents=True, exist_ok=True, mode=0o700)
        cwd = Path(request.get("cwd") or work).expanduser().resolve()
        if not cwd.is_dir():
            raise ValueError("执行目录不存在")
        # 自主 Root 执行允许访问容器目录；下载边界仍限于每次执行的产物目录。
        with self.store.transaction() as db:
            previous = db.execute("SELECT * FROM executions WHERE request_id=?", (request_id,)).fetchone()
            if previous:
                previous = dict(previous)
                if (previous["task_id"], previous["command"], previous["cwd"], previous["timeout_seconds"], json.loads(previous["env"])) != (task_id, command, str(cwd), timeout, env):
                    raise ValueError("同一幂等请求 ID 的执行内容不一致")
                return self.public(previous)
            execution_id = str(uuid.uuid4())
            db.execute("""INSERT INTO executions(id,request_id,task_id,status,command,cwd,env,timeout_seconds,created_at,schedule_id,trigger_id)
                VALUES(?,?,?,'queued',?,?,?,?,?,?,?)""", (execution_id, request_id, task_id, command, str(cwd), encode(env), timeout, now(), schedule_id, trigger_id))
        row = self.get(execution_id)
        self.run_dir(row).mkdir(parents=True, exist_ok=True, mode=0o700)
        (self.run_dir(row) / "artifacts").mkdir(mode=0o700, exist_ok=True)
        self.store.event("execution.queued", execution_id, self.public(row), task_id)
        self.enqueue(execution_id)
        return self.public(row)

    def enqueue(self, execution_id: str):
        if execution_id not in self.jobs:
            job = asyncio.create_task(self.run(execution_id), name=f"execution:{execution_id}")
            self.jobs[execution_id] = job
            job.add_done_callback(lambda _: self.jobs.pop(execution_id, None))

    def finish(self, row: dict, status: str, exit_code: int | None, error_type: str | None):
        artifacts = self.artifacts(row)
        self.store.execute("UPDATE executions SET status=?,finished_at=?,exit_code=?,error_type=?,artifacts=? WHERE id=?", (status, now(), exit_code, error_type, encode(artifacts), row["id"]))
        if row["trigger_id"]:
            self.store.execute("UPDATE triggers SET status=?,updated_at=? WHERE id=?", (status, now(), row["trigger_id"]))
        self.store.event("execution.finished", row["id"], self.public(self.get(row["id"])), row["task_id"])

    def artifacts(self, row: dict) -> list[dict]:
        root = self.run_dir(row) / "artifacts"
        result = []
        if not root.exists():
            return result
        # 不跟随链接；数量和单文件大小均受限。
        for directory, folders, files in os.walk(root, followlinks=False):
            folders[:] = [folder for folder in folders if not (Path(directory) / folder).is_symlink()]
            for name in files:
                path = Path(directory) / name
                if path.is_symlink() or not path.is_file():
                    continue
                size = path.stat().st_size
                if size > self.config.max_artifact_bytes:
                    continue
                relative = path.relative_to(root).as_posix()
                result.append({"name": name, "path": relative, "size": size, "mime_type": mimetypes.guess_type(name)[0] or "application/octet-stream", "reference": f"/v1/executions/{row['id']}/artifacts/{quote(relative, safe='/')}"})
                if len(result) >= 200:
                    return result
        return result

    async def terminate(self, process: asyncio.subprocess.Process):
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        # 直接进程退出不代表子进程已退出，固定宽限后始终终止该组。
        await asyncio.sleep(0.5)
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            await asyncio.wait_for(process.wait(), timeout=3)
        except asyncio.TimeoutError:
            pass

    async def drain(self, row: dict, process: asyncio.subprocess.Process):
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        log = self.run_dir(row) / "output.log"
        written = 0
        truncated = False
        # 日志元数据按时间合并写库，值未变化时跳过；结束时下面再强制保存一次。
        saved = (0, False)
        saved_at = time.monotonic()
        # 留下最长凭据长度的尾部，避免凭据跨 stdout 分块时泄漏。
        tail = ""
        reserve = max(256, max((len(value) for value in self.redactor.values), default=256))
        with log.open("ab", buffering=0) as output:
            while chunk := await process.stdout.read(8192):
                tail += decoder.decode(chunk)
                reserve = max(reserve, max((len(value) for value in self.redactor.values), default=256))
                if len(tail) <= reserve:
                    continue
                # 按原文边界切分前先整体脱敏，再保留尾部；已跨边界的完整凭据不会漏出。
                safe = self.redactor.text(tail)
                if len(safe) != len(tail):
                    tail = safe
                prefix, tail = tail[:-reserve], tail[-reserve:]
                encoded = prefix.encode("utf-8")
                remaining = self.config.max_log_bytes - written
                if remaining > 0:
                    output.write(encoded[:remaining])
                    written += min(len(encoded), remaining)
                truncated = truncated or len(encoded) > remaining
                if (written, truncated) != saved and time.monotonic() - saved_at >= LOG_META_INTERVAL:
                    self.store.execute("UPDATE executions SET log_size=?,log_truncated=? WHERE id=?", (written, int(truncated), row["id"]))
                    saved, saved_at = (written, truncated), time.monotonic()
            tail += decoder.decode(b"", final=True)
            encoded = self.redactor.text(tail).encode("utf-8")
            remaining = max(0, self.config.max_log_bytes - written)
            output.write(encoded[:remaining])
            written += min(len(encoded), remaining)
            truncated = truncated or len(encoded) > remaining
        self.store.execute("UPDATE executions SET log_size=?,log_truncated=? WHERE id=?", (written, int(truncated), row["id"]))

    async def run(self, execution_id: str):
        row = self.get(execution_id)
        process = None
        reader = None
        try:
            async with self.semaphore:
                if execution_id in self.cancelled:
                    return
                # 先持久化“启动中”再创建进程：两步之间崩溃时，恢复按结果待确认处理，绝不当作排队任务再跑一次。
                with self.store.transaction() as db:
                    started = db.execute("UPDATE executions SET status='starting',started_at=? WHERE id=? AND status='queued'", (now(), execution_id))
                    if started.rowcount != 1:
                        return
                directory = self.run_dir(row)
                directory.mkdir(parents=True, exist_ok=True, mode=0o700)
                (directory / "artifacts").mkdir(exist_ok=True, mode=0o700)
                env = {"PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"), "HOME": os.environ.get("HOME", "/root"), "LANG": "C.UTF-8"}
                env.update(json.loads(row["env"]))
                self.redactor.register(json.loads(row["env"]))
                env.update(ACTONSAY_TASK_ID=row["task_id"], ACTONSAY_EXECUTION_ID=row["id"], ACTONSAY_ARTIFACT_DIR=str(directory / "artifacts"))
                process = await asyncio.create_subprocess_exec("/bin/bash", "-lc", row["command"], cwd=row["cwd"], env=env, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT, start_new_session=True)
                self.processes[execution_id] = process
                if execution_id in self.cancelled or self.get(execution_id)["status"] != "starting":
                    await self.terminate(process)
                    self.finish(row, "cancelled", process.returncode, "cancelled")
                    return
                self.store.execute("UPDATE executions SET status='running',pid=?,process_start=?,boot_id=? WHERE id=?", (process.pid, process_stamp(process.pid), boot_id(), execution_id))
                self.store.event("execution.running", execution_id, self.public(self.get(execution_id)), row["task_id"])
                reader = asyncio.create_task(self.drain(row, process))
                timed_out = False
                try:
                    await asyncio.wait_for(asyncio.shield(process.wait()), row["timeout_seconds"])
                except asyncio.TimeoutError:
                    timed_out = True
                    await self.terminate(process)
                # 清理后台子进程，避免持续持有 stdout 或脱离任务生命周期。
                await self.terminate(process)
                try:
                    await asyncio.wait_for(reader, 3)
                except asyncio.TimeoutError:
                    reader.cancel()
                    await asyncio.gather(reader, return_exceptions=True)
                if execution_id in self.cancelled:
                    self.finish(row, "cancelled", process.returncode, "cancelled")
                elif timed_out:
                    self.finish(row, "failed", process.returncode, "timeout")
                else:
                    self.finish(row, "succeeded" if process.returncode == 0 else "failed", process.returncode, None if process.returncode == 0 else "nonzero_exit")
        except asyncio.CancelledError:
            if process:
                await self.terminate(process)
            if reader:
                reader.cancel()
                await asyncio.gather(reader, return_exceptions=True)
            status = self.get(execution_id)["status"]
            if status not in FINAL:
                # 还未启动的队列任务在服务升级后可安全恢复；已进入启动的按结果待确认处理。
                if not (self.closed and status == "queued"):
                    self.finish(row, "result_unknown" if self.closed else "cancelled", process.returncode if process else None, "service_interrupted" if self.closed else "cancelled")
        except Exception:
            if process:
                await self.terminate(process)
            if reader:
                reader.cancel()
                await asyncio.gather(reader, return_exceptions=True)
            self.finish(row, "failed", process.returncode if process else None, "execution_error")
        finally:
            self.processes.pop(execution_id, None)
            self.cancelled.discard(execution_id)

    async def cancel(self, execution_id: str) -> dict:
        row = self.get(execution_id)
        if row["status"] in FINAL:
            return self.public(row)
        self.cancelled.add(execution_id)
        if process := self.processes.get(execution_id):
            await self.terminate(process)
        elif row["status"] == "queued":
            self.finish(row, "cancelled", None, "cancelled")
        if job := self.jobs.get(execution_id):
            await asyncio.wait({job}, timeout=5)
        return self.public(self.get(execution_id))

    def log(self, execution_id: str, offset: int, limit: int) -> dict:
        row = self.get(execution_id)
        path = self.run_dir(row) / "output.log"
        size = path.stat().st_size if path.exists() else 0
        offset = min(offset, size)
        raw = b""
        if path.exists():
            with path.open("rb") as source:
                source.seek(offset)
                raw = source.read(limit)
                # 不在 UTF-8 字符中间截断，游标按实际字节计数。
                for _ in range(3):
                    try:
                        raw.decode("utf-8")
                        break
                    except UnicodeDecodeError as error:
                        if error.reason == "unexpected end of data" and offset + len(raw) < size:
                            raw += source.read(1)
                        else:
                            break
        return {"text": raw.decode("utf-8", "replace"), "next_offset": offset + len(raw), "truncated": bool(row["log_truncated"]), "status": row["status"]}

    def artifact(self, execution_id: str, path: str) -> Path:
        row = self.get(execution_id)
        result = contained_path(self.run_dir(row) / "artifacts", path)
        if not result.is_file() or result.stat().st_size > self.config.max_artifact_bytes:
            raise ValueError("产物不是文件或超出下载限额")
        return result

    async def close(self):
        self.closed = True
        for job in list(self.jobs.values()):
            job.cancel()
        await asyncio.gather(*list(self.jobs.values()), return_exceptions=True)
