"""Loads the plugin's modules by file path and wires them, so a process has one instance of each.

Two entry points reach the plugin in the same process: Hermes imports the package as `hermes_plugins.hermes_app`
(hooks), and the dashboard imports `dashboard/plugin_api.py` standalone (routes). Neither can use the other's
import path, and relative imports break standalone, so both load every module through here under the fixed
names `hermes_app_<name>`. Each entry point loads this file itself by path under `hermes_app__siblings`
(reusing it if present), which makes whichever runs first the one that creates the shared state.
"""

import importlib.util
import sys
import threading
from pathlib import Path
from types import ModuleType

PLUGIN_DIR = Path(__file__).resolve().parent


def load(name: str) -> ModuleType:
    """The module `<plugin dir>/<name>.py` as `hermes_app_<name>`; a failed load leaves nothing behind."""
    module_name = f"hermes_app_{name}"
    if module_name in sys.modules:
        return sys.modules[module_name]
    spec = importlib.util.spec_from_file_location(module_name, PLUGIN_DIR / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        sys.modules.pop(module_name, None)
        raise
    return module


_shared = None
_shared_lock = threading.Lock()


def shared_sender():
    """The process's one sender, on the default store."""
    global _shared
    with _shared_lock:
        if _shared is None:
            _shared = load("sender").Sender(load("store").default_store, load("webpush").encrypt)
        return _shared
