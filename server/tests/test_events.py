import json

import pytest

from events import event_json, is_failure, is_silent
from webpush import MAX_PLAINTEXT


def test_cron_event_shape():
    raw = event_json("cron", session_id="s", job_id="j", job_name="Daily", text="hi")
    assert json.loads(raw) == {"v": 1, "type": "cron", "session_id": "s", "job_id": "j", "job_name": "Daily", "text": "hi"}


def test_none_fields_are_dropped():
    assert json.loads(event_json("test", text=None)) == {"v": 1, "type": "test"}


def test_false_is_kept():
    assert json.loads(event_json("cron", failed=False))["failed"] is False


def test_event_text_is_cut_to_fit():
    raw = event_json("reply", session_id="s", text="ä😀" * 10000)
    assert len(raw) <= MAX_PLAINTEXT
    text = json.loads(raw.decode("utf-8"))["text"]
    assert text.endswith("…")
    assert len(raw) > MAX_PLAINTEXT - 8  # cut as late as possible


def test_short_text_is_untouched():
    assert json.loads(event_json("reply", text="ä😀"))["text"] == "ä😀"


@pytest.mark.parametrize("text", [
    "[SILENT]", " SILENT ", "NO_REPLY", "no reply", "NO REPLY", "no_reply",
    "[SILENT]\n\nNothing new this tick.", "2 deals filtered\n\n[SILENT]", "report\n[SILENT]", "[SILENT]\nreport",
    "no_reply\nduplicate inbound, already handled",
    "[SILENT] No changes detected", "[silent] nothing new", "silent",
    "[静默]", "**沉默**", "[静默]\n\nNothing new this tick.", "2 deals filtered\n\n[沉默]", "静默",
    "【静默】", "静默。", "【沉默】", "沉默。", "**[静默]**", "NO_REPLY.", ".NO_REPLY", "*NO_REPLY*",
    "*" * 29 + "SILENT" + "*" * 29,
    "NO\u001cREPLY", "NO\u001fREPLY", "NO\u0085REPLY", "NO REPLY", "NO　REPLY",
])
def test_silent(text):
    assert is_silent(text)


@pytest.mark.parametrize("text", [
    "nothing [SILENT] here",
    "I considered staying [SILENT] but here is the summary: 3 items merged.",
    "the lane said 静默 mid-sentence and kept talking",
    "status: 静默 means the lane is quiet",
    "Silent retry succeeded", "NO_REPLY needed here, the report follows",
    "[SILENT", "SILENT]",
    "", "   \n ", "All quiet on the western front.",
    "This report is quite long and ends with the words no reply",
    "*" * 30 + "SILENT" + "*" * 30,
    "NO​REPLY",
])
def test_not_silent(text):
    assert not is_silent(text)


def test_failure_is_first_line_marker():
    assert is_failure("[CRON_FAILURE]\nboom")
    assert is_failure("[CRON_FAILURE] boom")
    assert not is_failure("ok\n[CRON_FAILURE]")
    assert not is_failure("")
