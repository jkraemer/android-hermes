"""Companion plugin for the Hermes Android app: pushes cron reports, approvals, questions and app replies."""

import importlib.util
import logging
import sys
import time
from pathlib import Path

log = logging.getLogger("hermes-app.hooks")

# Platforms of turns that come through the dashboard protocol, which the app can answer.
_APP_PLATFORMS = ("tui", "desktop")
_PUSHED_KINDS = ("approval", "clarify")
_MAX_JOB_NAME = 200


def _load_siblings():
    """Bootstrap the shared module loader by path (Hermes imports this package as hermes_plugins.hermes_app)."""
    name = "hermes_app__siblings"
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parent / "_siblings.py")
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        try:
            spec.loader.exec_module(module)
        except BaseException:
            sys.modules.pop(name, None)
            raise
    return sys.modules[name]


_siblings = _load_siblings()
_events = _siblings.load("events")
_store = _siblings.load("store")


def _never_raise(hook):
    """Hermes dispatches hooks on the agent thread; a failure here must not reach it, nor leak content into the log."""

    def guarded(**kwargs):
        try:
            hook(**kwargs)
        except Exception as e:
            log.warning("%s failed: %s", hook.__name__, type(e).__name__)

    guarded.__name__ = hook.__name__
    return guarded


def make_hooks(ctx, store_factory, sender) -> dict:
    """The hook callbacks by Hermes hook name. They only build an event, make one small store access and queue it."""

    @_never_raise
    def post_llm_call(session_id=None, assistant_response=None, platform=None, **kwargs):
        text = assistant_response or ""
        cron = ctx.current_cron_execution()
        if cron is not None:
            if _events.is_silent(text):
                return
            job_name = cron.job_name[:_MAX_JOB_NAME] if cron.job_name else None
            event = _events.event_json(
                "cron", session_id=session_id, job_id=cron.job_id, job_name=job_name, text=text, failed=True if _events.is_failure(text) else None
            )
        elif platform != "cron" and session_id and store_factory().is_followed(session_id, time.time()):
            event = _events.event_json("reply", session_id=session_id, text=text)
        else:
            return
        sender.submit(event)

    @_never_raise
    def on_human_input_request(kind=None, request_id=None, session_id=None, platform=None, prompt=None, **kwargs):
        if kind not in _PUSHED_KINDS or platform not in _APP_PLATFORMS:
            return
        store_factory().remember_request(request_id, session_id, time.time())
        sender.submit(_events.event_json(kind, session_id=session_id, request_id=request_id, text=prompt))

    @_never_raise
    def on_human_input_resolved(request_id=None, **kwargs):
        session_id = store_factory().pushed_request(request_id)
        if session_id is not None:
            sender.submit(_events.event_json("resolved", session_id=session_id, request_id=request_id))

    return {hook.__name__: hook for hook in (post_llm_call, on_human_input_request, on_human_input_resolved)}


def register(ctx):
    """Plugin entry point; the sender is the process's shared one, also used by the dashboard routes."""
    hooks = make_hooks(ctx, _store.default_store, _siblings.shared_sender())
    for name, callback in hooks.items():
        ctx.register_hook(name, callback)
