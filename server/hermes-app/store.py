"""The plugin's persistent state: the one push subscription, followed chat sessions and pushed requests.

Shared by the dashboard process (routes) and the gateway process (hooks) through one SQLite file in WAL mode;
every operation is its own short transaction on its own connection, so two processes never hold a lock long.
"""

import os
import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

RETENTION_SECONDS = 30 * 24 * 3600

_SCHEMA = """
CREATE TABLE IF NOT EXISTS subscription (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    endpoint TEXT NOT NULL, p256dh TEXT NOT NULL, auth TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS followed (session_id TEXT PRIMARY KEY, at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS requests (request_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, at REAL NOT NULL);
"""


@dataclass(frozen=True)
class Subscription:
    endpoint: str
    p256dh: str
    auth: str


class Store:
    def __init__(self, path: Path):
        self._path = path
        # The database holds the subscription's auth secret; SQLite gives the WAL/SHM files the main file's mode.
        os.close(os.open(path, os.O_CREAT, 0o600))
        with self._connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(_SCHEMA)

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        """A connection for one transaction: committed (or rolled back) and then closed."""
        db = sqlite3.connect(self._path, timeout=5)
        try:
            with db:
                yield db
        finally:
            db.close()

    def subscription(self) -> Subscription | None:
        with self._connect() as db:
            row = db.execute("SELECT endpoint, p256dh, auth FROM subscription").fetchone()
        return Subscription(*row) if row else None

    def set_subscription(self, subscription: Subscription) -> None:
        with self._connect() as db:
            db.execute(
                "INSERT OR REPLACE INTO subscription (id, endpoint, p256dh, auth) VALUES (1, ?, ?, ?)",
                (subscription.endpoint, subscription.p256dh, subscription.auth),
            )

    def clear_subscription(self, endpoint: str | None = None) -> None:
        """Forget the subscription; with an endpoint, only if it is still that one (a newer registration survives)."""
        with self._connect() as db:
            if endpoint is None:
                db.execute("DELETE FROM subscription")
            else:
                db.execute("DELETE FROM subscription WHERE endpoint = ?", (endpoint,))

    def follow(self, session_id: str, now: float) -> None:
        with self._connect() as db:
            self._prune(db, now)
            db.execute("INSERT OR REPLACE INTO followed (session_id, at) VALUES (?, ?)", (session_id, now))

    def is_followed(self, session_id: str, now: float) -> bool:
        with self._connect() as db:
            row = db.execute("SELECT 1 FROM followed WHERE session_id = ? AND at > ?", (session_id, now - RETENTION_SECONDS)).fetchone()
        return row is not None

    def remember_request(self, request_id: str, session_id: str, now: float) -> None:
        with self._connect() as db:
            self._prune(db, now)
            db.execute("INSERT OR REPLACE INTO requests (request_id, session_id, at) VALUES (?, ?, ?)", (request_id, session_id, now))

    def pushed_request(self, request_id: str) -> str | None:
        with self._connect() as db:
            row = db.execute("SELECT session_id FROM requests WHERE request_id = ?", (request_id,)).fetchone()
        return row[0] if row else None

    @staticmethod
    def _prune(db: sqlite3.Connection, now: float) -> None:
        cutoff = now - RETENTION_SECONDS
        db.execute("DELETE FROM followed WHERE at <= ?", (cutoff,))
        db.execute("DELETE FROM requests WHERE at <= ?", (cutoff,))


def default_store() -> Store:
    """The store in Hermes' per-plugin data directory. The Hermes import is internal API, so it is made here."""
    from plugins.plugin_storage import plugin_data_dir

    return Store(plugin_data_dir("hermes-app") / "push.db")
