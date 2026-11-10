"""The JSON the plugin pushes to the app, and the judgement of which cron replies are worth one."""

import importlib.util
import json
import sys
import unicodedata
from pathlib import Path


def _sibling(name: str):
    """Load a module of this directory by file path, so it works both inside the plugin package and when
    plugin_api.py is imported standalone by the dashboard (where relative imports do not exist)."""
    module_name = f"hermes_app_{name}"
    if module_name not in sys.modules:
        spec = importlib.util.spec_from_file_location(module_name, Path(__file__).with_name(f"{name}.py"))
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
    return sys.modules[module_name]


_MAX_PLAINTEXT = _sibling("webpush").MAX_PLAINTEXT
_ELLIPSIS = "…"


def _dump(payload: dict) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def event_json(type: str, **fields) -> bytes:
    """The event as UTF-8 JSON of at most MAX_PLAINTEXT bytes; `text` is cut at a character boundary to fit."""
    payload = {"v": 1, "type": type, **{k: v for k, v in fields.items() if v is not None}}
    encoded = _dump(payload)
    text = payload.get("text")
    if len(encoded) <= _MAX_PLAINTEXT or text is None:
        return encoded
    overflow = len(encoded) - _MAX_PLAINTEXT
    # Bytes of text to keep, leaving room for the ellipsis. Cutting a character can only free more than asked.
    keep = max(len(text.encode("utf-8")) - overflow - len(_ELLIPSIS.encode("utf-8")), 0)
    cut = text.encode("utf-8")[:keep].decode("utf-8", errors="ignore")
    payload["text"] = cut + _ELLIPSIS
    return _dump(payload)


_SILENCE_MARKERS = frozenset({"[SILENT]", "SILENT", "NO_REPLY", "NO REPLY", "[静默]", "静默", "[沉默]", "沉默"})
_BRACKETED_SILENCE_MARKERS = tuple(sorted(m for m in _SILENCE_MARKERS if m.startswith("[")))
# Longer than any marker could plausibly be, even with stray punctuation.
_MARKER_LENGTH_CAP = 64


def _is_edge_punctuation(ch: str) -> bool:
    # Square brackets stay structural so a malformed `[SILENT` cannot become `SILENT`.
    return ch not in "[]" and unicodedata.category(ch).startswith("P")


def _strip_edge_punctuation(text: str) -> str:
    start, end = 0, len(text)
    while start < end and _is_edge_punctuation(text[start]):
        start += 1
    while end > start and _is_edge_punctuation(text[end - 1]):
        end -= 1
    return text[start:end].strip()


def _is_marker(text: str) -> bool:
    stripped = text.strip()
    if not 0 < len(stripped) <= _MARKER_LENGTH_CAP:
        return False
    return any(" ".join(form.upper().split()) in _SILENCE_MARKERS for form in (stripped, _strip_edge_punctuation(stripped)))


def is_silent(text: str) -> bool:
    """Whether a cron run's reply means "nothing to report", which Hermes does not deliver either: a marker as
    the whole reply or its own first or last line, or the bracketed marker opening it. A port of Hermes'
    `is_autonomous_silence_response` (and the app's core/cron/Silence.kt)."""
    stripped = text.strip()
    if not stripped:
        return False
    lines = [line for line in stripped.splitlines() if line.strip()]
    return stripped.upper().startswith(_BRACKETED_SILENCE_MARKERS) or any(_is_marker(c) for c in (stripped, lines[0], lines[-1]))


def is_failure(text: str) -> bool:
    """Whether the cron reply is a failure report, which Hermes marks with `[CRON_FAILURE]` as its first line."""
    return text.lstrip().startswith("[CRON_FAILURE]")
