from __future__ import annotations

import asyncio
import codecs
import getpass
import hmac
import json
import logging
import mimetypes
import os
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Body, FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.background import BackgroundTask

from . import __version__
from .config import Config, Redactor, identifier, open_contained
from .executions import Executions
from .mcp_manager import McpFailure, McpManager
from .schedules import Schedules
from .skills import Skills
from .store import Store, now


class SafeLogFilter(logging.Filter):
    def __init__(self, redactor: Redactor):
        super().__init__()
        self.redactor = redactor

    def filter(self, record):
        record.msg = self.redactor.text(record.getMessage())
        record.args = ()
        # 第三方 SDK 的异常堆栈可能包含 HTTP Header 和原协议数据。
        if record.name.startswith(("mcp", "httpx", "httpcore")):
            record.exc_info = None
            record.exc_text = None
        return True


def shared_mounts() -> list[dict]:
    result = []
    try:
        for line in Path("/proc/self/mountinfo").read_text().splitlines():
            before, after = line.split(" - ", 1)
            fields, filesystem = before.split(), after.split()
            root, path = fields[3], fields[4]
            def unescape(value):
                return value.replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\")
            # 按完整路径段匹配，避免把 /proc/sys/kernel/hostname 这类挂载误报为共享目录。
            segments = set((root + "/" + path).lower().split("/"))
            if segments & {"sdcard", "storage", "shared", "host", "android"} or "/data/media" in (root + " " + path).lower():
                result.append({"path": unescape(path), "source": unescape(filesystem[1]), "source_root": unescape(root), "read_only": "ro" in fields[5].split(",")})
    except (OSError, ValueError, IndexError):
        pass
    return result


def required(data: dict, *keys: str):
    for key in keys:
        if key not in data:
            raise ValueError(f"缺少字段 {key}")


def download(root: Path, relative: str, max_bytes: int):
    source, size = open_contained(root, relative, max_bytes)

    def chunks():
        remaining = size
        try:
            while remaining > 0:
                chunk = source.read(min(65536, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk
        finally:
            source.close()

    return StreamingResponse(chunks(), media_type=mimetypes.guess_type(relative)[0] or "application/octet-stream", headers={"Content-Length": str(size), "Content-Disposition": f"attachment; filename*=utf-8''{quote(Path(relative).name)}"}, background=BackgroundTask(source.close))


def create_app(config: Config | None = None) -> FastAPI:
    config = config or Config.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        token = config.prepare()
        redactor = Redactor(token)
        log_filter = SafeLogFilter(redactor)
        for logger_name in ("uvicorn.error", "mcp", "mcp.client.streamable_http", "mcp.client.session", "mcp.client.stdio", "httpx", "httpcore", "apscheduler"):
            logger = logging.getLogger(logger_name)
            logger.addFilter(log_filter)
            # SDK debug 日志包含协议正文，始终禁止 debug 输出。
            logger.setLevel(logging.WARNING)
            for handler in logger.handlers:
                handler.addFilter(log_filter)
        for handler in logging.getLogger().handlers:
            handler.addFilter(log_filter)
        store = Store(config.data_dir / "bridge.sqlite3")
        executions = Executions(config, store, redactor)
        schedules = Schedules(config, store, executions)
        skills = Skills(config, store)
        mcp = McpManager(config, store, redactor)
        app.state.token, app.state.redactor = token, redactor
        app.state.store, app.state.executions = store, executions
        app.state.schedules, app.state.skills, app.state.mcp = schedules, skills, mcp
        app.state.import_lock = asyncio.Lock()
        try:
            await skills.builtins()
            await executions.start()
            await schedules.start()
            yield
        finally:
            await schedules.close()
            await mcp.close()
            await executions.close()
            store.close()

    app = FastAPI(title="ActOnSay Debian Bridge", version=__version__, lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def local_auth(request: Request, call_next):
        authorization = request.headers.get("authorization", "")
        supplied = authorization[7:] if authorization.lower().startswith("bearer ") else ""
        if not supplied or not supplied.isascii() or not hmac.compare_digest(supplied, getattr(app.state, "token", "")):
            return JSONResponse({"error_type": "unauthorized", "message": "配对令牌无效"}, status_code=401, headers={"WWW-Authenticate": "Bearer"})
        if request.method in {"POST", "PATCH", "PUT"}:
            length = request.headers.get("content-length")
            if request.headers.get("transfer-encoding") or (length and (not length.isdigit() or int(length) > 30 * 1024 * 1024)):
                return JSONResponse({"error_type": "payload_too_large", "message": "请求体超过 30 MiB 或采用不支持的传输编码"}, status_code=413)
            if not length and request.headers.get("content-type"):
                return JSONResponse({"error_type": "length_required", "message": "请提供 Content-Length"}, status_code=411)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        # 不使用默认返回的 input，避免请求中的凭据被回显。
        return JSONResponse({"error_type": "invalid_request", "message": "请求字段或查询参数格式不合法"}, status_code=422)

    @app.exception_handler(ValueError)
    async def value_error(request, error):
        return JSONResponse({"error_type": "invalid_request", "message": app.state.redactor.text(str(error))}, status_code=400)

    @app.exception_handler(KeyError)
    async def missing_error(request, error):
        return JSONResponse({"error_type": "not_found", "message": str(error).strip("'")}, status_code=404)

    @app.exception_handler(FileNotFoundError)
    async def file_missing(request, error):
        return JSONResponse({"error_type": "not_found", "message": "文件或目录不存在"}, status_code=404)

    @app.exception_handler(UnicodeDecodeError)
    async def encoding_error(request, error):
        return JSONResponse({"error_type": "unsupported_encoding", "message": "文件不是 UTF-8 文本，请通过产物下载"}, status_code=400)

    @app.exception_handler(McpFailure)
    async def mcp_error(request, error):
        return JSONResponse({"error_type": error.kind, "message": str(error)}, status_code=504 if error.kind == "timeout" else 502)

    router = APIRouter(prefix="/v1")

    @router.get("/health")
    async def health():
        return {"status": "ready", "version": __version__, "identity": {"uid": os.getuid(), "gid": os.getgid(), "user": getpass.getuser(), "root": os.getuid() == 0}, "shared_mounts": shared_mounts(), "timezone": config.timezone, "time": now(), "data_dir": str(config.data_dir), "python": sys.executable, "automation": app.state.schedules.automation(), "capabilities": ["executions", "events", "schedules", "ai_triggers", "mcp_stdio", "mcp_streamable_http", "skills"]}

    @router.post("/executions", status_code=202)
    async def submit_execution(data: dict = Body(...)):
        required(data, "request_id", "task_id", "command")
        return app.state.executions.submit(data)

    @router.get("/executions")
    async def list_executions(task_id: str | None = None, limit: int = Query(200, ge=1, le=500), after: str | None = None):
        clauses, args = [], []
        if task_id:
            clauses.append("task_id=?")
            args.append(identifier(task_id))
        if after:
            clauses.append("created_at>?")
            args.append(after)
        sql = "SELECT * FROM executions" + (" WHERE " + " AND ".join(clauses) if clauses else "") + " ORDER BY created_at DESC LIMIT ?"
        return {"executions": [app.state.executions.public(row) for row in app.state.store.all(sql, (*args, limit))]}

    @router.get("/executions/{execution_id}")
    async def get_execution(execution_id: str):
        return app.state.executions.public(app.state.executions.get(execution_id))

    @router.post("/executions/{execution_id}/cancel")
    async def cancel_execution(execution_id: str):
        return await app.state.executions.cancel(execution_id)

    @router.post("/executions/{execution_id}/rerun", status_code=202)
    async def rerun_execution(execution_id: str, data: dict = Body(...)):
        required(data, "request_id", "task_id")
        return app.state.executions.rerun(execution_id, data)

    @router.post("/executions/{execution_id}/resolve")
    async def resolve_execution(execution_id: str, data: dict = Body(...)):
        required(data, "status", "note")
        return app.state.executions.resolve(execution_id, data)

    @router.get("/executions/{execution_id}/log")
    async def execution_log(execution_id: str, offset: int = Query(0, ge=0), limit: int = Query(65536, ge=4, le=262144)):
        return app.state.executions.log(execution_id, offset, limit)

    @router.get("/executions/{execution_id}/artifact-text")
    async def artifact_text(execution_id: str, path: str):
        row = app.state.executions.get(execution_id)
        source, size = open_contained(app.state.executions.run_dir(row) / "artifacts", path, config.max_artifact_bytes)
        with source:
            raw = source.read(262144)
        truncated = size > len(raw)
        # 增量解码器保留截断位置的不完整字符，同时拒绝二进制文件。
        text = codecs.getincrementaldecoder("utf-8")("strict").decode(raw, final=not truncated)
        return {"path": path, "text": app.state.redactor.text(text), "truncated": truncated}

    @router.get("/executions/{execution_id}/artifacts/{path:path}")
    async def download_execution_artifact(execution_id: str, path: str):
        row = app.state.executions.get(execution_id)
        return download(app.state.executions.run_dir(row) / "artifacts", path, config.max_artifact_bytes)

    @router.get("/tasks/{task_id}/artifacts/{path:path}")
    async def download_task_artifact(task_id: str, path: str):
        root = config.data_dir / "tasks" / identifier(task_id) / "artifacts"
        return download(root, path, config.max_artifact_bytes)

    @router.get("/events")
    async def events(after: int = Query(0, ge=0), limit: int = Query(200, ge=1, le=1000), wait_seconds: int = Query(0, ge=0, le=25)):
        deadline = asyncio.get_running_loop().time() + wait_seconds
        while True:
            rows = app.state.store.all("SELECT * FROM events WHERE id>? ORDER BY id LIMIT ?", (after, limit))
            if rows or asyncio.get_running_loop().time() >= deadline:
                break
            await asyncio.sleep(0.5)
        return {"events": [row | {"data": json.loads(row["data"])} for row in rows], "cursor": rows[-1]["id"] if rows else after}

    @router.get("/events/stream")
    async def events_stream(request: Request, after: int = Query(0, ge=0)):
        async def stream():
            cursor = after
            while not await request.is_disconnected():
                rows = app.state.store.all("SELECT * FROM events WHERE id>? ORDER BY id LIMIT 100", (cursor,))
                for row in rows:
                    cursor = row["id"]
                    value = row | {"data": json.loads(row["data"])}
                    yield f"id: {cursor}\nevent: {row['type']}\ndata: {json.dumps(value, ensure_ascii=False)}\n\n"
                if not rows:
                    yield ": heartbeat\n\n"
                    await asyncio.sleep(10)
        return StreamingResponse(stream(), media_type="text/event-stream")

    @router.get("/automation")
    async def get_automation():
        return app.state.schedules.automation()

    @router.patch("/automation")
    async def set_automation(data: dict = Body(...)):
        required(data, "paused")
        if not isinstance(data["paused"], bool):
            raise ValueError("paused 必须是布尔值")
        result = app.state.schedules.pause(data["paused"])
        if not result["paused"]:
            await app.state.schedules.tick()
        return result

    @router.get("/schedules")
    async def list_schedules():
        return {"schedules": [app.state.redactor.json(app.state.schedules.public(row)) for row in app.state.store.all("SELECT * FROM schedules ORDER BY created_at")]}

    @router.post("/schedules", status_code=201)
    async def create_schedule(data: dict = Body(...)):
        return app.state.schedules.save(data)

    @router.get("/schedules/{schedule_id}")
    async def get_schedule(schedule_id: str):
        return app.state.redactor.json(app.state.schedules.public(app.state.schedules.get(schedule_id)))

    @router.patch("/schedules/{schedule_id}")
    async def update_schedule(schedule_id: str, data: dict = Body(...)):
        return app.state.schedules.save(data, schedule_id)

    @router.delete("/schedules/{schedule_id}")
    async def delete_schedule(schedule_id: str):
        app.state.schedules.delete(schedule_id)
        return {"deleted": True}

    @router.get("/triggers")
    async def list_triggers(schedule_id: str | None = None):
        rows = app.state.store.all("SELECT * FROM triggers" + (" WHERE schedule_id=?" if schedule_id else "") + " ORDER BY created_at DESC LIMIT 500", (schedule_id,) if schedule_id else ())
        return {"triggers": [app.state.schedules.trigger_public(row) for row in rows]}

    @router.get("/ai-triggers")
    async def ai_triggers(include_completed: bool = False):
        return {"triggers": app.state.schedules.ai_triggers(include_completed)}

    @router.post("/ai-triggers/{trigger_id}/claim")
    async def claim_ai(trigger_id: str, data: dict = Body(...)):
        required(data, "owner_id")
        return app.state.schedules.claim(trigger_id, data["owner_id"])

    @router.post("/ai-triggers/{trigger_id}/ack")
    async def ack_ai(trigger_id: str, data: dict = Body(...)):
        required(data, "task_id", "owner_id")
        return app.state.schedules.ack(trigger_id, data["task_id"], data["owner_id"])

    @router.post("/ai-triggers/{trigger_id}/complete")
    async def complete_ai(trigger_id: str, data: dict = Body(...)):
        required(data, "task_id", "status")
        return app.state.schedules.complete(trigger_id, data["task_id"], data["status"])

    @router.post("/ai-triggers/{trigger_id}/resolve")
    async def resolve_ai(trigger_id: str, data: dict = Body(...)):
        required(data, "task_id", "status", "note")
        return app.state.schedules.resolve(trigger_id, data["task_id"], data["status"], data["note"])

    @router.get("/mcp")
    async def list_mcp():
        return {"servers": [app.state.mcp.public(json.loads(row["definition"])) for row in app.state.store.all("SELECT definition FROM mcp_servers ORDER BY created_at")]}

    @router.post("/mcp", status_code=201)
    async def create_mcp(data: dict = Body(...)):
        return await app.state.mcp.save(data)

    @router.patch("/mcp/{server_id}")
    async def update_mcp(server_id: str, data: dict = Body(...)):
        return await app.state.mcp.save(data, server_id)

    @router.delete("/mcp/{server_id}")
    async def delete_mcp(server_id: str):
        await app.state.mcp.delete(server_id)
        return {"deleted": True}

    @router.post("/mcp/{server_id}/test")
    async def test_mcp(server_id: str):
        return await app.state.mcp.request(server_id, "test", {})

    @router.get("/mcp/{server_id}/tools")
    async def mcp_tools(server_id: str):
        return await app.state.mcp.request(server_id, "tools", {})

    @router.post("/mcp/{server_id}/call")
    async def mcp_call(server_id: str, data: dict = Body(...)):
        required(data, "name")
        return await app.state.mcp.call(server_id, data)

    @router.get("/mcp-calls/{call_id}")
    async def get_mcp_call(call_id: str):
        row = app.state.store.one("SELECT id,status,result FROM mcp_calls WHERE id=? OR request_id=?", (call_id, call_id))
        if not row:
            raise KeyError("MCP 调用不存在")
        return json.loads(row["result"]) if row["result"] else {"id": row["id"], "status": row["status"]}

    @router.post("/mcp-calls/{call_id}/cancel")
    async def cancel_mcp_call(call_id: str):
        return await app.state.mcp.cancel_call(call_id)

    @router.post("/mcp-calls/{call_id}/resolve")
    async def resolve_mcp_call(call_id: str, data: dict = Body(...)):
        required(data, "status", "note")
        return app.state.mcp.resolve_call(call_id, data["status"], data["note"])

    @router.get("/mcp/{server_id}/resources")
    async def mcp_resources(server_id: str):
        return await app.state.mcp.request(server_id, "resources", {})

    @router.get("/mcp/{server_id}/resource-templates")
    async def mcp_resource_templates(server_id: str):
        return await app.state.mcp.request(server_id, "resource_templates", {})

    @router.post("/mcp/{server_id}/resources/read")
    async def read_resource(server_id: str, data: dict = Body(...)):
        required(data, "uri")
        result = await app.state.mcp.request(server_id, "read_resource", data)
        return app.state.mcp.materialize(result, data.get("task_id"))

    @router.get("/mcp/{server_id}/prompts")
    async def mcp_prompts(server_id: str):
        return await app.state.mcp.request(server_id, "prompts", {})

    @router.post("/mcp/{server_id}/prompts/get")
    async def get_prompt(server_id: str, data: dict = Body(...)):
        required(data, "name")
        return app.state.mcp.materialize(await app.state.mcp.request(server_id, "get_prompt", data), data.get("task_id"))

    @router.get("/skills")
    async def list_skills():
        return {"skills": app.state.redactor.json(app.state.skills.list())}

    @router.post("/skills/import", status_code=201)
    async def import_skill(data: dict = Body(...)):
        required(data, "kind", "source")
        async with app.state.import_lock:
            return await app.state.skills.import_skill(data)

    @router.patch("/skills/{skill_id}")
    async def enable_skill(skill_id: str, data: dict = Body(...)):
        required(data, "enabled")
        if not isinstance(data["enabled"], bool):
            raise ValueError("enabled 必须是布尔值")
        return app.state.skills.enable(skill_id, data["enabled"])

    @router.delete("/skills/{skill_id}")
    async def delete_skill(skill_id: str):
        app.state.skills.delete(skill_id)
        return {"deleted": True}

    @router.post("/skills/{skill_id}/update")
    async def update_skill(skill_id: str):
        async with app.state.import_lock:
            return await app.state.skills.update(skill_id)

    @router.post("/skills/{skill_id}/snapshot")
    async def snapshot_skill(skill_id: str, data: dict = Body(...)):
        required(data, "task_id")
        return app.state.skills.snapshot(skill_id, data["task_id"])

    @router.get("/skills/{skill_id}/read")
    async def read_skill(skill_id: str, path: str = "SKILL.md", task_id: str | None = None):
        return app.state.skills.read(skill_id, path, task_id)

    @router.post("/skills/{skill_id}/run", status_code=202)
    async def run_skill(skill_id: str, data: dict = Body(...)):
        required(data, "task_id", "request_id", "path")
        return app.state.executions.submit(app.state.skills.script_request(skill_id, data))

    app.include_router(router)
    return app
