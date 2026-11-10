import importlib.util
import json
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from store import Store, Subscription

PLUGIN_API = Path(__file__).resolve().parent.parent / "hermes-app" / "dashboard" / "plugin_api.py"
BODY = {"endpoint": "https://ntfy.example/up", "p256dh": "pk", "auth": "au"}


class FakeSender:
    def __init__(self):
        self.events = []

    def submit(self, event: bytes):
        self.events.append(event)


@pytest.fixture
def env(tmp_path, monkeypatch):
    # Loaded the way the dashboard loads it: standalone, by file path.
    spec = importlib.util.spec_from_file_location("hermes_dashboard_plugin_hermes-app", PLUGIN_API)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    store, sender = Store(tmp_path / "push.db"), FakeSender()
    monkeypatch.setattr(module, "store_factory", lambda: store)
    monkeypatch.setattr(module, "sender_factory", lambda: sender)
    app = FastAPI()
    app.include_router(module.router, prefix="/api/plugins/hermes-app")
    return TestClient(app), store, sender


URL = "/api/plugins/hermes-app"


def test_put_subscription_stores_it(env):
    client, store, _ = env
    assert client.put(f"{URL}/subscription", json=BODY).status_code == 204
    assert store.subscription() == Subscription("https://ntfy.example/up", "pk", "au")


def test_put_subscription_without_a_field_is_422(env):
    client, store, _ = env
    assert client.put(f"{URL}/subscription", json={"endpoint": "x", "p256dh": "y"}).status_code == 422
    assert store.subscription() is None


def test_delete_subscription_clears_it_and_is_idempotent(env):
    client, store, _ = env
    client.put(f"{URL}/subscription", json=BODY)
    assert client.delete(f"{URL}/subscription").status_code == 204
    assert client.delete(f"{URL}/subscription").status_code == 204
    assert store.subscription() is None


def test_put_followed_marks_the_session(env):
    client, store, _ = env
    assert client.put(f"{URL}/followed/sess-1").status_code == 204
    import time

    assert store.is_followed("sess-1", now=time.time())
    assert not store.is_followed("sess-2", now=time.time())


def test_post_test_queues_a_test_event(env):
    client, store, sender = env
    store.set_subscription(Subscription("e", "p", "a"))
    assert client.post(f"{URL}/test").status_code == 204
    assert [json.loads(e) for e in sender.events] == [{"v": 1, "type": "test"}]


def test_post_test_without_subscription_is_409(env):
    client, _, sender = env
    response = client.post(f"{URL}/test")
    assert response.status_code == 409
    assert response.json() == {"detail": "no subscription"}
    assert sender.events == []
