from __future__ import annotations

import asyncio
import base64
import json
import os
import re
import uuid
from contextlib import AsyncExitStack
from datetime import timedelta
from pathlib import Path
from urllib.parse import parse_qsl, quote, urlsplit

from jsonschema import Draft202012Validator, ValidationError
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.client.streamable_http import streamablehttp_client
from pydantic import AnyUrl

from .config import Config, Redactor, identifier
from .store import Store, encode, now


class McpFailure(Exception):
    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind


def safe_connection_error(error: BaseException) -> str:
    if isinstance(error, BaseExceptionGroup) and error.exceptions:
        return safe_connection_error(error.exceptions[0])
    response = getattr(error, "response", None)
    if response is not None and getattr(response, "status_code", None):
        return f"MCP 连接失败（HTTP {response.status_code}），请检查地址与认证 Header"
    if isinstance(error, FileNotFoundError):
        return "MCP 可执行文件或工作目录不存在，请检查 command、cwd 和容器依赖"
    if isinstance(error, PermissionError):
        return "MCP 启动权限不足，请检查执行文件权限和目录权限"
    if isinstance(error, TimeoutError):
        return "MCP 初始化超时，请检查服务、网络与超时设置"
    return f"MCP 连接失败（{type(error).__name__}），请检查协议、依赖和认证配置"


class Connection:
    """连接和 SDK 上下文始终由同一个 actor 任务拥有。"""

    def __init__(self, definition: dict):
        self.definition = definition
        self.queue: asyncio.Queue = asyncio.Queue(maxsize=32)
        self.ready = asyncio.get_running_loop().create_future()
        self.task = asyncio.create_task(self.run(), name=f"mcp:{definition['id']}")
        self.state = "connecting"
        self.error = None
        self.session = None
        self.tools: dict[str, dict] = {}

    async def pages(self, method: str, field: str) -> dict:
        items, cursor, seen = [], None, set()
        for _ in range(100):
            page = await getattr(self.session, method)(cursor=cursor)
            value = page.model_dump(mode="json", by_alias=True, exclude_none=True)
            items += value.get(field, [])
            if len(items) > 5000:
                raise ValueError("MCP 列表超过 5000 项")
            cursor = value.get("nextCursor")
            if not cursor:
                return {field: items}
            if cursor in seen:
                raise ValueError("MCP 分页游标重复")
            seen.add(cursor)
        raise ValueError("MCP 分页超过 100 页")

    async def operation(self, method: str, payload: dict):
        if method in {"test", "tools"}:
            result = await self.pages("list_tools", "tools")
            self.tools = {item["name"]: item for item in result["tools"]}
            for item in result["tools"]:
                item["qualified_name"] = f"mcp_{self.definition['id'].replace('-', '')[:12]}__{item['name']}"
            return {"status": "connected", "tools_count": len(result["tools"]), **result} if method == "test" else result
        if method == "resources":
            return await self.pages("list_resources", "resources")
        if method == "resource_templates":
            return await self.pages("list_resource_templates", "resourceTemplates")
        if method == "prompts":
            return await self.pages("list_prompts", "prompts")
        if method == "read_resource":
            result = await self.session.read_resource(AnyUrl(payload["uri"]))
        elif method == "get_prompt":
            result = await self.session.get_prompt(payload["name"], arguments=payload.get("arguments", {}))
        elif method == "call_tool":
            name = payload["name"]
            prefix = f"mcp_{self.definition['id'].replace('-', '')[:12]}__"
            if name.startswith(prefix):
                name = name[len(prefix):]
            if not self.tools:
                await self.operation("tools", {})
            if name not in self.tools:
                raise ValueError("MCP 工具不存在")
            arguments = payload.get("arguments", {})
            try:
                Draft202012Validator(self.tools[name].get("inputSchema", {"type": "object"})).validate(arguments)
            except ValidationError:
                raise ValueError("MCP 工具参数不符合 inputSchema") from None
            result = await self.session.call_tool(name, arguments=arguments, read_timeout_seconds=timedelta(seconds=self.definition["timeout_seconds"]))
        else:
            raise ValueError("不支持的 MCP 操作")
        return result.model_dump(mode="json", by_alias=True, exclude_none=True)

    async def run(self):
        current_future = None
        try:
            definition = self.definition
            async with AsyncExitStack() as stack:
                if definition["transport"] == "stdio":
                    # 第三方服务器的 stderr 可能含凭据，不直接进入服务日志。
                    sink = stack.enter_context(open(os.devnull, "w"))
                    transport = stdio_client(StdioServerParameters(command=definition["command"], args=definition.get("args", []), env=definition.get("env"), cwd=definition.get("cwd")), errlog=sink)
                else:
                    transport = streamablehttp_client(definition["url"], headers=definition.get("headers", {}), timeout=timedelta(seconds=definition["timeout_seconds"]), sse_read_timeout=timedelta(seconds=max(300, definition["timeout_seconds"])))
                streams = await stack.enter_async_context(transport)
                self.session = await stack.enter_async_context(ClientSession(streams[0], streams[1], read_timeout_seconds=timedelta(seconds=definition["timeout_seconds"])))
                async with asyncio.timeout(definition["timeout_seconds"]):
                    await self.session.initialize()
                self.state = "connected"
                self.ready.set_result(True)
                while True:
                    method, payload, current_future = await self.queue.get()
                    if method == "close":
                        if not current_future.done():
                            current_future.set_result(None)
                        break
                    if current_future.cancelled():
                        continue
                    try:
                        async with asyncio.timeout(definition["timeout_seconds"]):
                            result = await self.operation(method, payload)
                        if not current_future.done():
                            current_future.set_result(result)
                    except ValueError as error:
                        if not current_future.done():
                            current_future.set_exception(error)
                    except Exception as error:
                        kind = "timeout" if isinstance(error, TimeoutError) else "connection_error"
                        if not current_future.done():
                            current_future.set_exception(McpFailure(kind, "MCP 请求超时，可能已产生副作用" if kind == "timeout" else "MCP 连接或协议失败，执行结果可能待确认"))
                        raise
                    finally:
                        current_future = None
        except BaseException as error:
            self.state = "disconnected"
            self.error = "连接已停止" if isinstance(error, asyncio.CancelledError) else safe_connection_error(error)
            failure = McpFailure("connection_error", self.error)
            if not self.ready.done():
                self.ready.set_exception(failure)
                # 标记已观察，避免没有等待者时 Future 产生无关告警。
                self.ready.exception()
            if current_future and not current_future.done():
                current_future.set_exception(failure)
        finally:
            self.state = "disconnected"
            while not self.queue.empty():
                _, _, future = self.queue.get_nowait()
                if not future.done():
                    future.set_exception(McpFailure("connection_error", "MCP 连接已断开"))

    async def request(self, method: str, payload: dict):
        await asyncio.shield(self.ready)
        if self.task.done():
            raise McpFailure("connection_error", self.error or "MCP 连接已断开")
        future = asyncio.get_running_loop().create_future()
        try:
            self.queue.put_nowait((method, payload, future))
        except asyncio.QueueFull:
            raise McpFailure("busy", "MCP 请求队列已满") from None
        try:
            return await future
        except asyncio.CancelledError:
            # 中止拥有 SDK 的 actor，使底层请求取消且 stdio 进程组回收。
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
            raise

    async def close(self):
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)


class McpManager:
    def __init__(self, config: Config, store: Store, redactor: Redactor):
        self.config, self.store, self.redactor = config, store, redactor
        self.connections: dict[str, Connection] = {}
        self.inflight: dict[str, asyncio.Task] = {}
        for row in store.all("SELECT definition FROM mcp_servers"):
            definition = json.loads(row["definition"])
            redactor.register(definition.get("env"))
            redactor.register(definition.get("headers"))
        for row in store.all("SELECT id FROM mcp_calls WHERE status='running'"):
            result = {"id": row["id"], "status": "result_unknown", "isError": True, "error_type": "service_interrupted", "content": [{"type": "text", "text": "服务中断，工具可能已产生副作用，请确认后显式重跑"}]}
            store.execute("UPDATE mcp_calls SET status='result_unknown',result=?,finished_at=? WHERE id=?", (encode(result), now(), row["id"]))

    def get(self, server_id: str) -> dict:
        row = self.store.one("SELECT definition FROM mcp_servers WHERE id=?", (server_id,))
        if not row:
            raise KeyError("MCP 服务不存在")
        return json.loads(row["definition"])

    def public(self, definition: dict) -> dict:
        value = dict(definition)
        value["env_keys"] = list(value.pop("env", {}))
        value["header_keys"] = list(value.pop("headers", {}))
        connection = self.connections.get(value["id"])
        value["connection_status"] = connection.state if connection else "disconnected"
        value["error"] = connection.error if connection else None
        return value

    async def save(self, data: dict, server_id: str | None = None) -> dict:
        definition = self.get(server_id) if server_id else {}
        definition.update({key: value for key, value in data.items() if key not in {"id", "env_keys", "header_keys", "connection_status", "error"}})
        definition.setdefault("enabled", True)
        definition.setdefault("args", [])
        definition.setdefault("env", {})
        definition.setdefault("headers", {})
        definition.setdefault("timeout_seconds", 60)
        name = str(definition.get("name", "")).strip()
        if not name or len(name) > 200:
            raise ValueError("请填写不超过 200 字符的 MCP 名称")
        definition["name"] = name
        if not 1 <= int(definition["timeout_seconds"]) <= 3600:
            raise ValueError("MCP 超时必须在 1 至 3600 秒之间")
        definition["timeout_seconds"] = int(definition["timeout_seconds"])
        definition["enabled"] = bool(definition["enabled"])
        if definition.get("transport") == "stdio":
            if not isinstance(definition.get("command"), str) or not definition["command"].strip() or "\0" in definition["command"]:
                raise ValueError("请输入 stdio 可执行文件名，参数填写到 args")
            if not isinstance(definition["args"], list) or len(definition["args"]) > 128 or any(not isinstance(value, str) or "\0" in value for value in definition["args"]):
                raise ValueError("MCP args 必须是字符串数组")
            if definition.get("cwd") and not Path(definition["cwd"]).is_dir():
                raise ValueError("MCP 工作目录不存在")
        elif definition.get("transport") == "streamable_http":
            url = urlsplit(str(definition.get("url", "")))
            if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password or url.fragment or any(re.search(r"(?i)token|password|secret|authorization|(?:api[_-]?)?key", key) for key, value in parse_qsl(url.query)):
                raise ValueError("请输入 HTTP 或 HTTPS MCP 地址，认证放在 Header 中")
        else:
            raise ValueError("MCP 传输必须是 stdio 或 streamable_http")
        for field in ("env", "headers"):
            values = definition[field]
            if not isinstance(values, dict) or len(values) > 128 or any(not isinstance(key, str) or not isinstance(value, str) or len(key) > 256 or len(value) > 65536 or "\0" in key + value or "\r" in key + value or "\n" in key + value for key, value in values.items()):
                raise ValueError("MCP 环境变量及 Header 必须是字符串对象，不允许控制字符")
            self.redactor.register(values)
        server_id = server_id or str(uuid.uuid4())
        definition["id"] = server_id
        definition["updated_at"] = now()
        if connection := self.connections.pop(server_id, None):
            await connection.close()
        self.store.execute("INSERT INTO mcp_servers(id,definition,created_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET definition=excluded.definition", (server_id, encode(definition), now()))
        self.store.event("mcp.updated", server_id, {"name": definition["name"], "enabled": definition["enabled"]})
        return self.public(definition)

    async def delete(self, server_id: str):
        self.get(server_id)
        if connection := self.connections.pop(server_id, None):
            await connection.close()
        self.store.execute("DELETE FROM mcp_servers WHERE id=?", (server_id,))
        self.store.event("mcp.deleted", server_id, {"id": server_id})

    async def request(self, server_id: str, method: str, payload: dict):
        definition = self.get(server_id)
        if not definition["enabled"]:
            raise ValueError("MCP 服务已禁用")
        connection = self.connections.get(server_id)
        if not connection or connection.task.done():
            connection = Connection(definition)
            self.connections[server_id] = connection
        return await connection.request(method, payload)

    def materialize(self, result: dict, task_id: str | None) -> dict:
        artifacts = []
        if task_id:
            root = self.config.data_dir / "tasks" / identifier(task_id) / "artifacts"
            root.mkdir(parents=True, exist_ok=True, mode=0o700)

            def walk(value):
                if isinstance(value, list):
                    return [walk(item) for item in value]
                if not isinstance(value, dict):
                    return value
                encoded = value.get("data") if value.get("type") in {"image", "audio"} else value.get("blob")
                if encoded:
                    if len(encoded) > self.config.max_artifact_bytes * 4 // 3 + 8:
                        return {"type": "text", "text": "MCP 文件超过 50 MiB，未保存"}
                    try:
                        raw = base64.b64decode(encoded, validate=True)
                    except ValueError:
                        return {"type": "text", "text": "MCP 文件编码不合法"}
                    mime = value.get("mimeType", "application/octet-stream")
                    suffix = {"image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp", "audio/wav": ".wav", "audio/mpeg": ".mp3", "application/pdf": ".pdf"}.get(mime, ".bin")
                    name = f"mcp-{uuid.uuid4()}{suffix}"
                    (root / name).write_bytes(raw)
                    artifact = {"name": name, "path": name, "size": len(raw), "mime_type": mime, "reference": f"/v1/tasks/{task_id}/artifacts/{quote(name, safe='/')}"}
                    artifacts.append(artifact)
                    return {key: item for key, item in value.items() if key not in {"data", "blob"}} | {"artifact": artifact}
                return {key: walk(item) for key, item in value.items()}

            result = walk(result)
        else:
            # 没有任务归属时也不把大块 base64 直接返回给模型。
            def drop_binary(value):
                if isinstance(value, list):
                    return [drop_binary(item) for item in value]
                if isinstance(value, dict):
                    return {key: ("[文件需要 task_id 才能保存]" if key in {"data", "blob"} else drop_binary(item)) for key, item in value.items()}
                return value
            result = drop_binary(result)
        result["artifacts"] = artifacts
        return self.redactor.json(result)

    async def call(self, server_id: str, payload: dict) -> dict:
        self.get(server_id)
        request_id = identifier(payload.get("request_id") or str(uuid.uuid4()))
        task_id = identifier(payload["task_id"]) if payload.get("task_id") else None
        request = {"name": payload["name"], "arguments": payload.get("arguments", {})}
        with self.store.transaction() as db:
            previous = db.execute("SELECT * FROM mcp_calls WHERE request_id=?", (request_id,)).fetchone()
            if previous:
                if previous["server_id"] != server_id or previous["task_id"] != task_id or json.loads(previous["request"]) != request:
                    raise ValueError("同一 MCP 幂等请求 ID 的内容不一致")
                return json.loads(previous["result"]) if previous["result"] else {"id": previous["id"], "status": "result_unknown", "isError": True, "error_type": "request_in_progress", "content": [{"type": "text", "text": "原工具调用仍在执行或结果未落盘，请查询调用状态，禁止自动重放"}]}
            call_id = str(uuid.uuid4())
            db.execute("INSERT INTO mcp_calls(id,request_id,server_id,task_id,status,request,created_at) VALUES(?,?,?,?,'running',?,?)", (call_id, request_id, server_id, task_id, encode(request), now()))
        self.store.event("mcp.call_started", call_id, {"server_id": server_id, "name": request["name"]}, task_id)
        self.inflight[call_id] = asyncio.current_task()
        try:
            result = self.materialize(await self.request(server_id, "call_tool", request), task_id)
            result.update(id=call_id, status="failed" if result.get("isError") else "succeeded")
        except ValueError as error:
            result = {"id": call_id, "status": "failed", "isError": True, "error_type": "invalid_request", "content": [{"type": "text", "text": str(error)}]}
        except McpFailure as error:
            result = {"id": call_id, "status": "result_unknown", "isError": True, "error_type": error.kind, "content": [{"type": "text", "text": str(error)}]}
        except asyncio.CancelledError:
            result = {"id": call_id, "status": "result_unknown", "isError": True, "error_type": "cancelled", "content": [{"type": "text", "text": "工具调用已中断，副作用结果待确认"}]}
            self.store.execute("UPDATE mcp_calls SET status=?,result=?,finished_at=? WHERE id=?", (result["status"], encode(result), now(), call_id))
            raise
        except Exception:
            result = {"id": call_id, "status": "result_unknown", "isError": True, "error_type": "bridge_error", "content": [{"type": "text", "text": "工具结果无法完整保存，副作用结果待确认"}]}
        finally:
            self.inflight.pop(call_id, None)
        self.store.execute("UPDATE mcp_calls SET status=?,result=?,finished_at=? WHERE id=?", (result["status"], encode(result), now(), call_id))
        self.store.event("mcp.call_finished", call_id, {"server_id": server_id, "status": result["status"], "artifacts": result.get("artifacts", [])}, task_id)
        return result

    async def cancel_call(self, call_id: str) -> dict:
        row = self.store.one("SELECT id,status,result FROM mcp_calls WHERE id=? OR request_id=?", (call_id, call_id))
        if not row:
            raise KeyError("MCP 调用不存在")
        if task := self.inflight.get(row["id"]):
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            row = self.store.one("SELECT id,status,result FROM mcp_calls WHERE id=?", (row["id"],))
        return json.loads(row["result"]) if row["result"] else {"id": row["id"], "status": row["status"]}

    def resolve_call(self, call_id: str, status: str, note: str) -> dict:
        note = str(note).strip()
        if status not in {"succeeded", "failed", "cancelled"} or not note or len(note) > 4096:
            raise ValueError("请选择成功、失败或取消，并填写人工核实说明")
        resolution = {"status": status, "note": self.redactor.text(note), "source": "user", "confirmed_at": now()}
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM mcp_calls WHERE id=? OR request_id=?", (call_id, call_id)).fetchone()
            if not row:
                raise KeyError("MCP 调用不存在")
            if row["status"] != "result_unknown":
                raise ValueError("仅结果待确认的 MCP 调用允许人工核实")
            result = json.loads(row["result"]) if row["result"] else {"id": row["id"], "content": []}
            result.update(status=status, isError=status != "succeeded", error_type="user_resolved", resolution=resolution)
            result.setdefault("content", []).append({"type": "text", "text": f"用户人工核实：{resolution['note']}"})
            db.execute("UPDATE mcp_calls SET status=?,result=? WHERE id=?", (status, encode(result), row["id"]))
        self.store.event("mcp.call_resolved", row["id"], resolution, row["task_id"])
        return result

    async def close(self):
        await asyncio.gather(*(connection.close() for connection in self.connections.values()), return_exceptions=True)
