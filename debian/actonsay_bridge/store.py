from __future__ import annotations

import json
import sqlite3
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def encode(value) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


class Store:
    def __init__(self, path: Path):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, isolation_level=None, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS executions (
                id TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE,
                task_id TEXT NOT NULL, status TEXT NOT NULL, command TEXT NOT NULL,
                cwd TEXT NOT NULL, env TEXT NOT NULL, timeout_seconds INTEGER NOT NULL,
                created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT,
                exit_code INTEGER, error_type TEXT, pid INTEGER, process_start TEXT,
                boot_id TEXT, log_size INTEGER NOT NULL DEFAULT 0,
                log_truncated INTEGER NOT NULL DEFAULT 0, artifacts TEXT NOT NULL DEFAULT '[]',
                schedule_id TEXT, trigger_id TEXT, resolution TEXT
            );
            CREATE INDEX IF NOT EXISTS executions_task ON executions(task_id,created_at);
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL,
                entity_id TEXT NOT NULL, task_id TEXT, created_at TEXT NOT NULL,
                data TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS schedules (
                id TEXT PRIMARY KEY, definition TEXT NOT NULL,
                next_run TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS triggers (
                id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL, scheduled_at TEXT NOT NULL,
                kind TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL,
                task_id TEXT, execution_id TEXT, owner_id TEXT, lease_until TEXT,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                UNIQUE(schedule_id, scheduled_at)
            );
            CREATE TABLE IF NOT EXISTS mcp_servers (
                id TEXT PRIMARY KEY, definition TEXT NOT NULL, created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS mcp_calls (
                id TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE, server_id TEXT NOT NULL,
                task_id TEXT, status TEXT NOT NULL, request TEXT NOT NULL,
                result TEXT, created_at TEXT NOT NULL, finished_at TEXT
            );
            CREATE TABLE IF NOT EXISTS skills (
                id TEXT PRIMARY KEY, definition TEXT NOT NULL, created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS skill_snapshots (
                task_id TEXT NOT NULL, skill_id TEXT NOT NULL, definition TEXT NOT NULL,
                path TEXT NOT NULL, created_at TEXT NOT NULL,
                PRIMARY KEY(task_id,skill_id)
            );
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY,value TEXT NOT NULL);
            PRAGMA user_version=1;
        """)
        # 可重复升级，仅添加本服务的新字段，保留已有行和历史。
        columns = {row["name"] for row in self.db.execute("PRAGMA table_info(executions)")}
        if "resolution" not in columns:
            self.db.execute("ALTER TABLE executions ADD COLUMN resolution TEXT")
        # 事件只用于续接同步，执行与触发记录各自完整保存；启动时限制事件表规模。
        self.db.execute("DELETE FROM events WHERE id <= (SELECT MAX(id) FROM events) - 20000")
        path.chmod(0o600)

    @contextmanager
    def transaction(self):
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield self.db
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def one(self, sql: str, args=()):
        with self.lock:
            row = self.db.execute(sql, args).fetchone()
            return dict(row) if row else None

    def all(self, sql: str, args=()):
        with self.lock:
            return [dict(row) for row in self.db.execute(sql, args).fetchall()]

    def execute(self, sql: str, args=()):
        with self.lock:
            return self.db.execute(sql, args)

    def event(self, kind: str, entity_id: str, data: dict, task_id: str | None = None):
        self.execute("INSERT INTO events(type,entity_id,task_id,created_at,data) VALUES(?,?,?,?,?)", (kind, entity_id, task_id, now(), encode(data)))

    def close(self):
        with self.lock:
            self.db.close()
