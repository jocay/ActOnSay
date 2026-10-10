from __future__ import annotations

import json
import math
import uuid
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.date import DateTrigger
from apscheduler.triggers.interval import IntervalTrigger

from .config import Config, identifier
from .executions import Executions, FINAL
from .store import Store, encode, now


def instant(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("时间必须包含时区偏移")
    return parsed.astimezone(timezone.utc)


ACTIVE_TRIGGER = {"pending", "claimed", "acknowledged", "queued", "running", "result_unknown"}


class Schedules:
    def __init__(self, config: Config, store: Store, executions: Executions):
        self.config, self.store, self.executions = config, store, executions
        self.scheduler = AsyncIOScheduler(timezone=timezone.utc)

    def definition(self, value: dict) -> dict:
        result = dict(value)
        result["name"] = str(result.get("name", "")).strip()
        if not result["name"] or len(result["name"]) > 200:
            raise ValueError("请填写不超过 200 字符的自动化名称")
        result.setdefault("kind", "ai")
        result.setdefault("schedule_type", "once")
        result.setdefault("timezone", self.config.timezone)
        result.setdefault("enabled", True)
        result.setdefault("misfire_policy", "latest")
        result.setdefault("catchup_window_seconds", 86400)
        result.setdefault("timeout_seconds", 900)
        if result["kind"] not in {"script", "ai"}:
            raise ValueError("自动化类型必须是 script 或 ai")
        field = {"once": "run_at", "interval": "interval_seconds", "cron": "cron"}.get(result["schedule_type"])
        if not field or result.get(field) is None:
            raise ValueError("请选择有效调度方式并填写对应时间参数")
        if result.get("env") and not isinstance(result["env"], dict):
            raise ValueError("自动化环境变量必须是对象")
        if result["misfire_policy"] not in {"latest", "skip"}:
            raise ValueError("补跑策略必须是 latest 或 skip")
        if not 0 <= int(result["catchup_window_seconds"]) <= 7 * 86400:
            raise ValueError("补跑窗口必须在 0 至 7 天之间")
        required = "command" if result["kind"] == "script" else "goal"
        if not str(result.get(required, "")).strip() or len(str(result[required])) > 128 * 1024:
            raise ValueError("执行内容为空或超出大小限制")
        if not 1 <= int(result["timeout_seconds"]) <= 86400:
            raise ValueError("超时必须在 1 至 86400 秒之间")
        result["enabled"] = bool(result["enabled"])
        # 为 interval 保存固定起点，修改名称不会让下一次时间漂移。
        result.setdefault("anchor", now())
        self.trigger(result)
        return result

    def trigger(self, definition: dict):
        zone = ZoneInfo(definition["timezone"])
        kind = definition["schedule_type"]
        if kind == "once":
            return DateTrigger(run_date=instant(definition["run_at"]), timezone=zone)
        if kind == "interval":
            seconds = int(definition["interval_seconds"])
            if seconds < 60 or seconds > 365 * 86400:
                raise ValueError("间隔必须在 60 秒至 365 天之间")
            return IntervalTrigger(seconds=seconds, start_date=instant(definition["anchor"]) + timedelta(seconds=seconds), timezone=zone)
        if kind == "cron":
            expression = str(definition["cron"])
            fields = expression.split()
            if len(fields) != 5:
                raise ValueError("Cron 必须包含分、时、日、月、星期五个字段")
            # APScheduler 3 的数字星期从星期一开始；避免悄悄采用与 Unix 不同的语义。
            if any(character.isdigit() for character in fields[4]):
                raise ValueError("Cron 星期请用 mon、tue、wed、thu、fri、sat、sun 或 *")
            return CronTrigger.from_crontab(expression, timezone=zone)
        raise ValueError("调度方式必须是 once、interval 或 cron")

    def preview(self, data: dict, count: int = 5) -> dict:
        """按与正式调度相同的触发规则预览未来几次时间；只读取时间相关字段，不保存。"""
        timing = {key: data[key] for key in ("schedule_type", "run_at", "interval_seconds", "cron", "timezone", "anchor") if data.get(key) is not None}
        definition = self.definition({"name": "preview", "kind": "ai", "goal": "preview"} | timing)
        trigger = self.trigger(definition)
        zone = ZoneInfo(definition["timezone"])
        runs, previous, cursor = [], None, datetime.now(timezone.utc)
        for _ in range(max(1, min(int(count), 10))):
            upcoming = trigger.get_next_fire_time(previous, cursor)
            if upcoming is None:
                break
            runs.append(upcoming.astimezone(zone).isoformat())
            previous, cursor = upcoming, upcoming + timedelta(microseconds=1)
        return {"timezone": definition["timezone"], "next_runs": runs}

    def public(self, row: dict) -> dict:
        value = json.loads(row["definition"])
        # API 列表不回显脚本环境中的凭据。
        if "env" in value:
            value["env_keys"] = list(value.pop("env"))
        value.update(id=row["id"], next_run=row["next_run"], created_at=row["created_at"], updated_at=row["updated_at"])
        return value

    def get(self, schedule_id: str) -> dict:
        row = self.store.one("SELECT * FROM schedules WHERE id=?", (schedule_id,))
        if not row:
            raise KeyError("自动化不存在")
        return row

    def automation(self) -> dict:
        row = self.store.one("SELECT value FROM settings WHERE key='automation_paused'")
        return {"paused": bool(row and row["value"] == "true")}

    def pause(self, paused: bool) -> dict:
        self.store.execute("INSERT INTO settings(key,value) VALUES('automation_paused',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", ("true" if paused else "false",))
        self.store.event("automation.updated", "global", {"paused": paused})
        return {"paused": paused}

    def save(self, data: dict, schedule_id: str | None = None) -> dict:
        old = self.get(schedule_id) if schedule_id else None
        merged = json.loads(old["definition"]) if old else {}
        merged.update({key: value for key, value in data.items() if key not in {"id", "next_run", "created_at", "updated_at", "env_keys"}})
        definition = self.definition(merged)
        schedule_id = schedule_id or str(uuid.uuid4())
        changed = not old or any(definition.get(key) != json.loads(old["definition"]).get(key) for key in ("schedule_type", "run_at", "interval_seconds", "cron", "timezone", "enabled"))
        next_run = old["next_run"] if old and not changed else None
        if changed and definition["enabled"]:
            next_time = self.trigger(definition).get_next_fire_time(None, datetime.now(timezone.utc))
            next_run = next_time.astimezone(timezone.utc).isoformat() if next_time else None
        self.store.execute("""INSERT INTO schedules(id,definition,next_run,created_at,updated_at) VALUES(?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET definition=excluded.definition,next_run=excluded.next_run,updated_at=excluded.updated_at""", (schedule_id, encode(definition), next_run, old["created_at"] if old else now(), now()))
        self.executions.redactor.register(definition.get("env"))
        result = self.public(self.get(schedule_id))
        self.store.event("schedule.updated", schedule_id, {"id": schedule_id, "enabled": definition["enabled"], "next_run": next_run})
        return result

    def delete(self, schedule_id: str):
        self.get(schedule_id)
        self.store.execute("DELETE FROM schedules WHERE id=?", (schedule_id,))
        # 已开始的任务仍保留完整记录；尚未领取的触发取消。
        self.store.execute("UPDATE triggers SET status='cancelled',updated_at=? WHERE schedule_id=? AND status IN ('pending','claimed')", (now(), schedule_id))
        self.store.event("schedule.deleted", schedule_id, {"id": schedule_id})

    async def start(self):
        for row in self.store.all("SELECT definition FROM schedules"):
            self.executions.redactor.register(json.loads(row["definition"]).get("env"))
        # SQLite 是权威定义；APScheduler 只负责唤醒，避免 pickle 和双份可修改状态。
        self.scheduler.add_job(self.tick, "interval", seconds=10, id="actonsay-tick", coalesce=True, max_instances=1, misfire_grace_time=60)
        self.scheduler.start()
        await self.tick()

    async def tick(self):
        if self.automation()["paused"]:
            return
        current = datetime.now(timezone.utc)
        for row in self.store.all("SELECT * FROM schedules WHERE next_run IS NOT NULL"):
            definition = json.loads(row["definition"])
            if not definition["enabled"]:
                continue
            due = instant(row["next_run"])
            if due > current:
                continue
            trigger = self.trigger(definition)
            recent = due
            if definition["schedule_type"] == "interval":
                interval = int(definition["interval_seconds"])
                recent = due + timedelta(seconds=math.floor((current - due).total_seconds() / interval) * interval)
                next_time = recent + timedelta(seconds=interval)
            elif definition["schedule_type"] == "once":
                next_time = None
            else:
                # 过长停机从补跑窗口开始扫描，分钟 Cron 的扫描总量有上限。
                floor = current - timedelta(seconds=int(definition["catchup_window_seconds"]))
                if recent < floor:
                    recent = trigger.get_next_fire_time(None, floor)
                    if recent is None or recent > current:
                        next_time = recent
                        recent = due
                    else:
                        next_time = trigger.get_next_fire_time(recent, recent + timedelta(microseconds=1))
                else:
                    next_time = trigger.get_next_fire_time(recent, recent + timedelta(microseconds=1))
                while next_time and next_time <= current:
                    recent = next_time
                    next_time = trigger.get_next_fire_time(recent, recent + timedelta(microseconds=1))
            late = (current - recent).total_seconds()
            should_fire = late <= int(definition["catchup_window_seconds"])
            # 秒级抖动不算错过；skip 在容器恢复时跳过过期超过一分钟的计划。
            if definition["misfire_policy"] == "skip" and late > 60:
                should_fire = False
            trigger_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"actonsay:{row['id']}:{recent.isoformat()}"))
            with self.store.transaction() as db:
                # UI 更改、删除与时钟触发共享事务，旧 next_run 不可覆盖新设置。
                fresh = db.execute("SELECT next_run FROM schedules WHERE id=?", (row["id"],)).fetchone()
                if not fresh or fresh["next_run"] != row["next_run"]:
                    continue
                db.execute("UPDATE schedules SET next_run=?,updated_at=? WHERE id=?", (next_time.astimezone(timezone.utc).isoformat() if next_time else None, now(), row["id"]))
                active = db.execute("SELECT id FROM triggers WHERE schedule_id=? AND status IN ('pending','claimed','acknowledged','queued','running','result_unknown') LIMIT 1", (row["id"],)).fetchone()
                status = "pending" if should_fire and not active else "skipped"
                db.execute("INSERT OR IGNORE INTO triggers(id,schedule_id,scheduled_at,kind,status,definition,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", (trigger_id, row["id"], recent.isoformat(), definition["kind"], status, encode(definition), now(), now()))
            self.store.event("schedule.triggered" if status == "pending" else "schedule.skipped", trigger_id, {"schedule_id": row["id"], "scheduled_at": recent.isoformat(), "kind": definition["kind"], "status": status})
        # 先落盘 pending 再提交，任意两步之间崩溃后通过相同 request_id 恢复。
        for row in self.store.all("SELECT * FROM triggers WHERE kind='script' AND status='pending'"):
            definition = json.loads(row["definition"])
            task_id = f"schedule-{row['id']}"
            try:
                execution = self.executions.submit({"request_id": row["id"], "task_id": task_id, "command": definition["command"], "cwd": definition.get("cwd"), "env": definition.get("env", {}), "timeout_seconds": definition["timeout_seconds"]}, schedule_id=row["schedule_id"], trigger_id=row["id"])
                self.store.execute("UPDATE triggers SET task_id=?,execution_id=?,status=?,updated_at=? WHERE id=?", (task_id, execution["id"], execution["status"], now(), row["id"]))
            except (ValueError, OSError):
                self.store.execute("UPDATE triggers SET status='failed',updated_at=? WHERE id=?", (now(), row["id"]))
                self.store.event("schedule.failed", row["id"], {"error_type": "invalid_execution", "schedule_id": row["schedule_id"]})

    def trigger_public(self, row: dict) -> dict:
        definition = json.loads(row["definition"])
        return {key: row[key] for key in ("id", "schedule_id", "scheduled_at", "kind", "status", "task_id", "execution_id", "owner_id", "lease_until", "created_at", "updated_at")} | {"name": definition["name"], "goal": definition.get("goal"), "timezone": definition["timezone"]}

    def ai_triggers(self, include_completed: bool = False) -> list[dict]:
        clause = "" if include_completed else " AND status IN ('pending','claimed','acknowledged','result_unknown')"
        return [self.trigger_public(row) for row in self.store.all("SELECT * FROM triggers WHERE kind='ai'" + clause + " ORDER BY created_at LIMIT 500")]

    def claim(self, trigger_id: str, owner_id: str) -> dict:
        if self.automation()["paused"]:
            raise ValueError("自动化已暂停")
        identifier(owner_id)
        current = datetime.now(timezone.utc)
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM triggers WHERE id=? AND kind='ai'", (trigger_id,)).fetchone()
            if not row:
                raise KeyError("AI 触发不存在")
            row = dict(row)
            if row["status"] == "acknowledged":
                return self.trigger_public(row)
            if row["status"] not in {"pending", "claimed"}:
                raise ValueError("触发已完成或不可领取")
            if row["status"] == "claimed" and row["owner_id"] != owner_id and row["lease_until"] and instant(row["lease_until"]) > current:
                raise ValueError("触发已由另一接收端领取")
            lease = (current + timedelta(seconds=120)).isoformat()
            db.execute("UPDATE triggers SET status='claimed',owner_id=?,lease_until=?,updated_at=? WHERE id=?", (owner_id, lease, now(), trigger_id))
        return self.trigger_public(self.store.one("SELECT * FROM triggers WHERE id=?", (trigger_id,)))

    def ack(self, trigger_id: str, task_id: str, owner_id: str | None) -> dict:
        identifier(task_id)
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM triggers WHERE id=? AND kind='ai'", (trigger_id,)).fetchone()
            if not row:
                raise KeyError("AI 触发不存在")
            if row["task_id"]:
                if row["task_id"] != task_id:
                    raise ValueError("触发已经绑定另一任务，禁止重复执行")
                return self.trigger_public(dict(row))
            if row["status"] != "claimed" or not owner_id or row["owner_id"] != owner_id:
                raise ValueError("请先领取，再由相同接收端确认持久化")
            db.execute("UPDATE triggers SET status='acknowledged',task_id=?,lease_until=NULL,updated_at=? WHERE id=?", (task_id, now(), trigger_id))
        self.store.event("ai_trigger.acknowledged", trigger_id, {"task_id": task_id}, task_id)
        return self.trigger_public(self.store.one("SELECT * FROM triggers WHERE id=?", (trigger_id,)))

    def complete(self, trigger_id: str, task_id: str, status: str) -> dict:
        if status not in FINAL:
            raise ValueError("状态必须是 succeeded、failed、cancelled 或 result_unknown")
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM triggers WHERE id=? AND kind='ai'", (trigger_id,)).fetchone()
            if not row:
                raise KeyError("AI 触发不存在")
            if row["task_id"] != task_id:
                raise ValueError("任务 ID 与触发绑定不一致")
            if row["status"] in FINAL:
                if row["status"] == status:
                    return self.trigger_public(dict(row))
                raise ValueError("触发已有终态，结果待确认请使用人工核实入口")
            db.execute("UPDATE triggers SET status=?,updated_at=? WHERE id=?", (status, now(), trigger_id))
        self.store.event("ai_trigger.finished", trigger_id, {"status": status}, task_id)
        return self.trigger_public(self.store.one("SELECT * FROM triggers WHERE id=?", (trigger_id,)))

    def resolve(self, trigger_id: str, task_id: str, status: str, note: str) -> dict:
        note = str(note).strip()
        if status not in {"succeeded", "failed", "cancelled"} or not note or len(note) > 4096:
            raise ValueError("请选择成功、失败或取消，并填写人工核实说明")
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM triggers WHERE id=? AND kind='ai'", (trigger_id,)).fetchone()
            if not row:
                raise KeyError("AI 触发不存在")
            if row["status"] != "result_unknown" or row["task_id"] != task_id:
                raise ValueError("仅匹配任务 ID 的结果待确认触发允许人工核实")
            db.execute("UPDATE triggers SET status=?,updated_at=? WHERE id=?", (status, now(), trigger_id))
        self.store.event("ai_trigger.resolved", trigger_id, {"status": status, "note": self.executions.redactor.text(note), "source": "user"}, task_id)
        return self.trigger_public(self.store.one("SELECT * FROM triggers WHERE id=?", (trigger_id,)))

    async def close(self):
        if self.scheduler.running:
            self.scheduler.shutdown(wait=False)
