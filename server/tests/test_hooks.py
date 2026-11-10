import importlib.util
import json
import logging
import os
import subprocess
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from events import MAX_PLAINTEXT
from store import Store

SERVER = Path(__file__).resolve().parent.parent
PLUGIN_INIT = SERVER / "hermes-app" / "__init__.py"


def load_plugin():
    # Loaded the way Hermes loads it: the package by file path, not importable by its directory name.
    spec = importlib.util.spec_from_file_location("hermes_plugins_hermes_app_under_test", PLUGIN_INIT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeSender:
    def __init__(self):
        self.events = []

    def submit(self, event: bytes):
        self.events.append(event)

    def sent(self):
        return [json.loads(e) for e in self.events]


class FakeCtx:
    def __init__(self, cron=None):
        self.cron = cron
        self.hooks = {}

    def current_cron_execution(self):
        return self.cron

    def register_hook(self, name, callback):
        self.hooks[name] = callback


CRON = SimpleNamespace(job_id="j1", job_name="Disk check")


@pytest.fixture
def env(tmp_path):
    store, sender = Store(tmp_path / "push.db"), FakeSender()

    def build(cron=None):
        ctx = FakeCtx(cron)
        return load_plugin().make_hooks(ctx, lambda: store, sender)

    return build, store, sender


def test_cron_run_pushes_the_report(env):
    build, _, sender = env
    build(CRON)["post_llm_call"](session_id="s1", assistant_response="Disk 91% full", platform="cron")
    assert sender.sent() == [{"v": 1, "type": "cron", "session_id": "s1", "job_id": "j1", "job_name": "Disk check", "text": "Disk 91% full"}]


def test_silent_cron_report_pushes_nothing(env):
    build, _, sender = env
    build(CRON)["post_llm_call"](session_id="s1", assistant_response="[SILENT]", platform="cron")
    assert sender.events == []


def test_failed_cron_report_is_marked_failed(env):
    build, _, sender = env
    build(CRON)["post_llm_call"](session_id="s1", assistant_response="[CRON_FAILURE] boom", platform="cron")
    assert sender.sent()[0]["failed"] is True


def test_long_job_name_cannot_push_the_event_over_the_limit(env):
    build, _, sender = env
    cron = SimpleNamespace(job_id="j1", job_name="n" * 10_000)
    build(cron)["post_llm_call"](session_id="s1", assistant_response="x" * 10_000, platform="cron")
    assert len(sender.events[0]) <= MAX_PLAINTEXT
    assert len(sender.sent()[0]["job_name"]) == 200


def test_reply_in_followed_session_is_pushed(env):
    build, store, sender = env
    store.follow("s1", now=time.time())
    build()["post_llm_call"](session_id="s1", assistant_response="Done.", platform="tui")
    assert sender.sent() == [{"v": 1, "type": "reply", "session_id": "s1", "text": "Done."}]


def test_reply_in_unfollowed_session_is_not_pushed(env):
    build, _, sender = env
    build()["post_llm_call"](session_id="s1", assistant_response="Done.", platform="tui")
    assert sender.events == []


def test_cron_platform_without_execution_is_not_pushed(env):
    build, store, sender = env
    store.follow("s1", now=time.time())
    build()["post_llm_call"](session_id="s1", assistant_response="Done.", platform="cron")
    assert sender.events == []


@pytest.mark.parametrize("kind,platform,expected", [("approval", "tui", "approval"), ("clarify", "desktop", "clarify")])
def test_human_input_request_is_pushed_and_remembered(env, kind, platform, expected):
    build, store, sender = env
    build()["on_human_input_request"](kind=kind, request_id="r1", session_id="s1", platform=platform, prompt="rm -rf /tmp/x")
    assert sender.sent() == [{"v": 1, "type": expected, "session_id": "s1", "request_id": "r1", "text": "rm -rf /tmp/x"}]
    assert store.pushed_request("r1") == "s1"


@pytest.mark.parametrize("kind,platform", [("sudo", "tui"), ("approval", "cli"), ("clarify", "telegram")])
def test_other_human_input_requests_are_not_pushed(env, kind, platform):
    build, store, sender = env
    build()["on_human_input_request"](kind=kind, request_id="r1", session_id="s1", platform=platform, prompt="p")
    assert sender.events == []
    assert store.pushed_request("r1") is None


def test_resolved_pushes_for_a_pushed_request(env):
    build, _, sender = env
    hooks = build()
    hooks["on_human_input_request"](kind="approval", request_id="r1", session_id="s1", platform="tui", prompt="ls")
    hooks["on_human_input_resolved"](request_id="r1", outcome="approved")
    assert sender.sent()[-1] == {"v": 1, "type": "resolved", "session_id": "s1", "request_id": "r1"}


def test_resolved_for_an_unknown_request_pushes_nothing(env):
    build, _, sender = env
    build()["on_human_input_resolved"](request_id="nope", outcome="approved")
    assert sender.events == []


def test_hooks_ignore_unknown_kwargs(env):
    build, _, sender = env
    hooks = build(CRON)
    hooks["post_llm_call"](session_id="s1", assistant_response="hi", platform="cron", model="m", future_kwarg=1)
    hooks["on_human_input_request"](kind="approval", request_id="r1", session_id="s1", platform="tui", prompt="p", future_kwarg=1)
    hooks["on_human_input_resolved"](request_id="r1", future_kwarg=1)
    assert [e["type"] for e in sender.sent()] == ["cron", "approval", "resolved"]


def test_store_failure_is_logged_without_content_and_never_raised(caplog):
    class BrokenStore:
        def __getattr__(self, name):
            raise RuntimeError("secret-text")

    ctx, sender = FakeCtx(), FakeSender()
    hooks = load_plugin().make_hooks(ctx, BrokenStore, sender)
    with caplog.at_level(logging.WARNING, logger="hermes-app"):
        hooks["post_llm_call"](session_id="s1", assistant_response="secret-text", platform="tui")
        hooks["on_human_input_request"](kind="approval", request_id="r1", session_id="s1", platform="tui", prompt="secret-text")
    assert "RuntimeError" in caplog.text
    assert "secret-text" not in caplog.text
    assert sender.events == []


def test_register_registers_the_three_hooks():
    ctx = FakeCtx()
    load_plugin().register(ctx)
    assert set(ctx.hooks) == {"post_llm_call", "on_human_input_request", "on_human_input_resolved"}


def test_install_copies_the_plugin_without_pycache(tmp_path):
    home = tmp_path / "home"
    stale = home / "plugins" / "hermes-app" / "stale.py"
    stale.parent.mkdir(parents=True)
    stale.write_text("old")
    (SERVER / "hermes-app" / "__pycache__").mkdir(exist_ok=True)
    (SERVER / "hermes-app" / "__pycache__" / "x.pyc").write_bytes(b"")
    result = subprocess.run(["sh", str(SERVER / "install.sh"), str(home)], capture_output=True, text=True, check=True)
    target = home / "plugins" / "hermes-app"
    for name in ("plugin.yaml", "__init__.py", "events.py", "dashboard/plugin_api.py", "dashboard/manifest.json"):
        assert (target / name).is_file(), name
    assert not stale.exists()
    assert not list(target.rglob("__pycache__"))
    assert "hermes plugins enable hermes-app" in result.stdout
    assert "dashboard" in result.stdout and "gateway" in result.stdout


def test_install_is_executable():
    assert os.access(SERVER / "install.sh", os.X_OK)
