import importlib.util
from pathlib import Path

import pytest

PLUGIN_DIR = Path(__file__).resolve().parent.parent / "hermes-app"


def load_plugin_api_standalone():
    spec = importlib.util.spec_from_file_location("hermes_dashboard_plugin_hermes-app", PLUGIN_DIR / "dashboard" / "plugin_api.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_every_entry_point_shares_one_instance_of_each_module_and_one_sender():
    import sys

    api = load_plugin_api_standalone()
    siblings = sys.modules["hermes_app__siblings"]

    for name in ("store", "events", "sender", "webpush"):
        assert siblings.load(name) is sys.modules[f"hermes_app_{name}"]
    assert siblings.load("sender") is api._sender
    assert siblings.load("store") is api._store
    assert siblings.shared_sender() is api.sender_factory()
    # A second standalone load of the entry point (hot-mount) still reaches the same sender.
    assert load_plugin_api_standalone().sender_factory() is siblings.shared_sender()


def test_a_sibling_that_fails_to_load_leaves_no_module_behind(tmp_path, monkeypatch):
    import sys

    load_plugin_api_standalone()
    siblings = sys.modules["hermes_app__siblings"]
    (tmp_path / "broken.py").write_text("raise RuntimeError('boom')\n")
    monkeypatch.setattr(siblings, "PLUGIN_DIR", tmp_path)

    with pytest.raises(RuntimeError, match="boom"):
        siblings.load("broken")
    assert "hermes_app_broken" not in sys.modules
    with pytest.raises(RuntimeError, match="boom"):
        siblings.load("broken")
