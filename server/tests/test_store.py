from pathlib import Path

import pytest

from store import Store, Subscription

DAY = 24 * 3600
SUB = Subscription("https://ntfy.example/up", "p256", "authsecret")


@pytest.fixture
def store(tmp_path: Path) -> Store:
    return Store(tmp_path / "push.db")


def test_no_subscription_initially(store):
    assert store.subscription() is None


def test_new_subscription_replaces_old(store):
    store.set_subscription(SUB)
    newer = Subscription("https://ntfy.example/other", "p2", "a2")
    store.set_subscription(newer)
    assert store.subscription() == newer


def test_clear_is_idempotent(store):
    store.set_subscription(SUB)
    store.clear_subscription()
    store.clear_subscription()
    assert store.subscription() is None


def test_followed_session_expires_after_30_days(store):
    store.follow("s1", now=1000)
    assert store.is_followed("s1", now=1000 + 30 * DAY - 1)
    assert not store.is_followed("s1", now=1000 + 30 * DAY + 1)
    assert not store.is_followed("other", now=1000)


def test_following_again_extends_the_window(store):
    store.follow("s1", now=0)
    store.follow("s1", now=20 * DAY)
    assert store.is_followed("s1", now=45 * DAY)


def test_writes_prune_rows_older_than_30_days(store):
    store.follow("old", now=0)
    store.remember_request("r-old", "old", now=0)
    store.follow("new", now=31 * DAY)
    assert store.pushed_request("r-old") is None
    assert store.is_followed("new", now=31 * DAY)


def test_request_memory(store):
    assert store.pushed_request("r1") is None
    store.remember_request("r1", "s1", now=1000)
    assert store.pushed_request("r1") == "s1"


def test_two_stores_on_one_file_see_each_others_writes(tmp_path):
    a, b = Store(tmp_path / "push.db"), Store(tmp_path / "push.db")
    a.set_subscription(SUB)
    a.follow("s1", now=5)
    a.remember_request("r1", "s1", now=5)
    assert b.subscription() == SUB
    assert b.is_followed("s1", now=5)
    assert b.pushed_request("r1") == "s1"
    b.clear_subscription()
    assert a.subscription() is None


def test_clearing_a_specific_endpoint_keeps_a_different_subscription(store):
    store.set_subscription(SUB)
    store.clear_subscription("https://ntfy.example/other")
    assert store.subscription() == SUB
    store.clear_subscription(SUB.endpoint)
    assert store.subscription() is None


def test_database_files_are_private_to_the_owner(tmp_path):
    path = tmp_path / "push.db"
    store = Store(path)
    store.set_subscription(SUB)
    files = [p for p in tmp_path.iterdir() if p.name.startswith("push.db")]
    assert path in files
    assert {oct(p.stat().st_mode & 0o777) for p in files} == {"0o600"}
