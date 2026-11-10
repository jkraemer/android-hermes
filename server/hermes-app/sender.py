"""Sends events to the registered push endpoint from one background thread, so hooks never wait on the network.

Nothing here logs an endpoint or an event's text, only the event type and the HTTP status.
"""

import json
import logging
import queue
import threading
import time
from collections.abc import Callable

import httpx

log = logging.getLogger("hermes-app.push")


QUEUE_SIZE = 100
TIMEOUT_SECONDS = 10
HEADERS = {"Content-Encoding": "aes128gcm", "Content-Type": "application/octet-stream", "TTL": "86400", "Urgency": "high"}


def httpx_post(url: str, body: bytes, headers: dict) -> int:
    return httpx.post(url, content=body, headers=headers, timeout=TIMEOUT_SECONDS).status_code


class Sender:
    def __init__(self, store_factory: Callable, encrypt: Callable[[bytes, str, str], bytes], post: Callable[[str, bytes, dict], int] = httpx_post):
        self._store_factory = store_factory
        self._encrypt = encrypt
        self._post = post
        self._queue: queue.Queue[bytes] = queue.Queue(QUEUE_SIZE)
        self._start_lock = threading.Lock()
        self._thread: threading.Thread | None = None

    def submit(self, event: bytes) -> None:
        """Queue an event for sending; never blocks, and drops the event when the queue is full."""
        self._ensure_thread()
        try:
            self._queue.put_nowait(event)
        except queue.Full:
            log.warning("push queue full, event dropped")

    def drain_for_tests(self, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while self._queue.unfinished_tasks and time.monotonic() < deadline:
            time.sleep(0.005)

    def _ensure_thread(self) -> None:
        with self._start_lock:
            if self._thread is None:
                self._thread = threading.Thread(target=self._run, name="hermes-app-push", daemon=True)
                self._thread.start()

    def _run(self) -> None:
        while True:
            event = self._queue.get()
            try:
                self._send(event)
            except Exception as error:  # a failed push is dropped, never retried
                log.warning("push failed: %s", type(error).__name__)
            finally:
                self._queue.task_done()

    def _send(self, event: bytes) -> None:
        store = self._store_factory()
        subscription = store.subscription()
        if subscription is None:
            return
        event_type = json.loads(event).get("type")
        body = self._encrypt(event, subscription.p256dh, subscription.auth)
        status = self._post(subscription.endpoint, body, dict(HEADERS))
        log.info("push %s: HTTP %s", event_type, status)
        if status in (404, 410):
            store.clear_subscription()
