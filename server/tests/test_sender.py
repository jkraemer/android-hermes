import json
import logging
import threading
import time

import pytest
from http_ece import decrypt

from events import event_json
from sender import Sender
from store import Store, Subscription
from webpush import encrypt

ENDPOINT = "https://ntfy.example/secret-topic"


def keypair():
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key = ec.generate_private_key(ec.SECP256R1())
    public = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    return key, public


def b64(data: bytes) -> str:
    import base64

    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


@pytest.fixture
def key_and_sub():
    key, public = keypair()
    auth = b"0123456789abcdef"
    return key, auth, Subscription(ENDPOINT, b64(public), b64(auth))


@pytest.fixture
def store(tmp_path):
    return Store(tmp_path / "push.db")


class Recorder:
    def __init__(self, status=201):
        self.status, self.calls = status, []

    def __call__(self, url, body, headers):
        self.calls.append((url, body, headers))
        if isinstance(self.status, Exception):
            raise self.status
        return self.status


def sender_with(store, post):
    return Sender(lambda: store, encrypt, post)


def test_posts_ciphertext_the_app_can_decrypt(store, key_and_sub):
    key, auth, sub = key_and_sub
    store.set_subscription(sub)
    post = Recorder()
    s = sender_with(store, post)
    event = event_json("cron", session_id="s", text="héllo")
    s.submit(event)
    s.drain_for_tests(5)

    [(url, body, headers)] = post.calls
    assert url == ENDPOINT
    assert headers == {"Content-Encoding": "aes128gcm", "Content-Type": "application/octet-stream", "TTL": "86400", "Urgency": "high"}
    assert decrypt(body, private_key=key, auth_secret=auth) == event
    assert json.loads(event)["text"] == "héllo"


@pytest.mark.parametrize("status", [404, 410])
def test_gone_endpoint_is_forgotten(store, key_and_sub, status):
    store.set_subscription(key_and_sub[2])
    s = sender_with(store, Recorder(status))
    s.submit(event_json("test"))
    s.drain_for_tests(5)
    assert store.subscription() is None


@pytest.mark.parametrize("status", [200, 201, 429, 507, RuntimeError("network down")])
def test_other_outcomes_keep_the_subscription(store, key_and_sub, status):
    store.set_subscription(key_and_sub[2])
    post = Recorder(status)
    s = sender_with(store, post)
    s.submit(event_json("test"))
    s.drain_for_tests(5)
    assert len(post.calls) == 1
    assert store.subscription() == key_and_sub[2]


def test_no_subscription_means_no_post(store):
    post = Recorder()
    s = sender_with(store, post)
    s.submit(event_json("test"))
    s.drain_for_tests(5)
    assert post.calls == []


def test_a_full_queue_drops_without_blocking(store, key_and_sub):
    store.set_subscription(key_and_sub[2])
    release = threading.Event()

    def blocked_post(url, body, headers):
        release.wait(10)
        return 201

    s = sender_with(store, blocked_post)
    try:
        started = time.monotonic()
        for _ in range(150):
            s.submit(event_json("test"))
        assert time.monotonic() - started < 0.05
    finally:
        release.set()
        s.drain_for_tests(10)


def test_the_log_has_neither_endpoint_nor_text(store, key_and_sub, caplog):
    store.set_subscription(key_and_sub[2])
    caplog.set_level(logging.DEBUG)
    for status in (201, 429, RuntimeError(f"cannot reach {ENDPOINT}")):
        s = sender_with(store, Recorder(status))
        s.submit(event_json("reply", text="top-secret-text"))
        s.drain_for_tests(5)
    assert caplog.records
    assert "secret-topic" not in caplog.text
    assert "top-secret-text" not in caplog.text
    assert "ntfy.example" not in caplog.text
