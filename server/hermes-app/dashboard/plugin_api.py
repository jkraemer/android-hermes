"""Dashboard routes through which the Android app registers for push. Mounted at /api/plugins/hermes-app."""

import importlib.util
import sys
import time
from pathlib import Path

from fastapi import APIRouter, HTTPException, Response
from pydantic import BaseModel


def _sibling(name: str):
    """Load a module of the plugin directory by file path. The dashboard imports this file standalone, outside
    the plugin package, so neither relative nor package imports work; the modules load under fixed names
    and import each other the same way."""
    module_name = f"hermes_app_{name}"
    if module_name not in sys.modules:
        spec = importlib.util.spec_from_file_location(module_name, Path(__file__).resolve().parent.parent / f"{name}.py")
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
    return sys.modules[module_name]


_store, _events, _sender = _sibling("store"), _sibling("events"), _sibling("sender")

# Replaced by tests.
store_factory = _store.default_store
sender_factory = _sender.shared_sender

router = APIRouter()


class SubscriptionBody(BaseModel):
    endpoint: str
    p256dh: str
    auth: str


@router.put("/subscription", status_code=204)
def put_subscription(body: SubscriptionBody) -> Response:
    store_factory().set_subscription(_store.Subscription(body.endpoint, body.p256dh, body.auth))
    return Response(status_code=204)


@router.delete("/subscription", status_code=204)
def delete_subscription() -> Response:
    store_factory().clear_subscription()
    return Response(status_code=204)


@router.put("/followed/{session_id}", status_code=204)
def put_followed(session_id: str) -> Response:
    store_factory().follow(session_id, time.time())
    return Response(status_code=204)


@router.post("/test", status_code=204)
def post_test() -> Response:
    if store_factory().subscription() is None:
        raise HTTPException(status_code=409, detail="no subscription")
    sender_factory().submit(_events.event_json("test"))
    return Response(status_code=204)
