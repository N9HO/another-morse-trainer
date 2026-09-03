"""Tests for the model call itself: the budget it asks for, and what it does
when no verdict comes back.

test_bot_flow.py swaps out `bot.triage` wholesale, which is what it needs in
order to test thread memory — but it means nothing in the suite ever exercised
the Anthropic call in triage.py. That is where the bot broke: the verdict is a
structured output, and its `body` field is a whole Markdown issue write-up, so a
small output budget does not produce a shorter issue — it produces JSON that
stops mid-string. The SDK validates that JSON on the way out, so the call
raises, the report is never filed, and the reporter is told "I hit an error
analyzing that report". Every triage of a thread long enough to trip it failed
the same way.

Run standalone or under pytest:

    DISCORD_BOT_TOKEN=x ANTHROPIC_API_KEY=x GITHUB_TOKEN=x \\
      GITHUB_REPO=owner/repo python3 test_triage_call.py
"""

from __future__ import annotations

import os

os.environ.setdefault("DISCORD_BOT_TOKEN", "x")
os.environ.setdefault("ANTHROPIC_API_KEY", "x")
os.environ.setdefault("GITHUB_TOKEN", "x")
os.environ.setdefault("GITHUB_REPO", "n9ho/another-morse-trainer")

from pydantic import ValidationError  # noqa: E402

import config  # noqa: E402
import triage  # noqa: E402
from triage import TriageError, Verdict  # noqa: E402

# A verdict for a bug with the platform already established, so the platform
# policy has nothing to add and the happy-path tests read what they assert.
VERDICT = Verdict(
    kind="bug",
    should_file=True,
    is_duplicate=False,
    title="QSO simulator freezes above 25 WPM",
    body="### Steps to reproduce\n…\n\n_Reported via Discord by kb1abc._",
    labels=["bug"],
    severity="medium",
    platform="ios",
    reply="Thanks — logged it.",
)


class FakeResponse:
    def __init__(self, verdict, stop_reason="end_turn"):
        self.parsed_output = verdict
        self.stop_reason = stop_reason
        self.model = "claude-opus-5"


class FakeMessages:
    """Records the request, and answers with whatever the test set up."""

    def __init__(self, response=None, raises=None):
        self.response, self.raises = response, raises
        self.calls: list[dict] = []

    def parse(self, **kwargs):
        self.calls.append(kwargs)
        if self.raises is not None:
            raise self.raises
        return self.response


class FakeClient:
    def __init__(self, response=None, raises=None):
        self.messages = FakeMessages(response, raises)


class fake_model:
    """Swap triage's shared Anthropic client for a recorder."""

    def __init__(self, response=None, raises=None):
        self.client = FakeClient(response, raises)

    def __enter__(self):
        self._saved = triage._client
        triage._client = self.client
        return self.client.messages

    def __exit__(self, *exc):
        triage._client = self._saved
        return False


def _triage(**kw):
    return triage._triage_sync(
        "kb1abc",
        "The QSO simulator freezes when I send fast.",
        [],
        **kw,
    )


def _truncated_json_error() -> ValidationError:
    """The real error the SDK raises when a verdict is cut off mid-JSON."""
    try:
        Verdict.model_validate_json('{"kind": "bug", "title": "QSO simulator fre')
    except ValidationError as err:
        return err
    raise AssertionError("that JSON was supposed to be invalid")


# --- tests --------------------------------------------------------------------


def test_the_whole_verdict_is_given_room_to_arrive():
    """The regression: a 2048-token budget for a full issue write-up.

    An issue body with Steps to reproduce / Expected / Actual, a "Still needed"
    section, a follow-up comment and a Discord reply does not fit — and on a
    model that thinks by default the thinking is drawn from the same budget, so
    the JSON is cut off before the verdict is finished.
    """
    with fake_model(FakeResponse(VERDICT)) as messages:
        _triage()

    asked = messages.calls[0]["max_tokens"]
    assert asked == config.settings.max_tokens, (
        f"the call should ask for the configured budget, not {asked}"
    )
    assert asked >= 8000, (
        f"a {asked}-token budget cannot hold a full issue write-up plus thinking"
    )


def test_the_budget_stays_inside_what_a_non_streaming_call_allows():
    """The SDK refuses a non-streaming request implying >10 minutes of output,
    so a budget over the ceiling would fail every call instead of none."""
    assert config.settings.max_tokens <= config.MAX_OUTPUT_TOKENS


def test_a_cut_off_answer_says_so_instead_of_losing_the_report():
    with fake_model(raises=_truncated_json_error()):
        try:
            _triage()
        except TriageError as err:
            message = str(err)
        else:
            raise AssertionError("a truncated verdict must not pass silently")

    assert "ANTHROPIC_MAX_TOKENS" in message, (
        f"the log line has to name the knob to turn: {message!r}"
    )


def test_a_refusal_is_reported_not_recorded_as_noise():
    """No verdict is a failure to report, not a report that is noise.

    Calling it noise files nothing and (outside an explicit trigger) says
    nothing, so the maintainer never learns the report existed — the exact
    failure the file-first rule exists to prevent.
    """
    with fake_model(FakeResponse(None, stop_reason="refusal")):
        try:
            _triage()
        except TriageError as err:
            message = str(err)
        else:
            raise AssertionError("a missing verdict must be raised, not swallowed")

    assert "refusal" in message, f"say why there was no verdict: {message!r}"


def test_a_verdict_comes_back_through_the_platform_policy():
    with fake_model(FakeResponse(VERDICT.model_copy(update={"platform": "unknown"}))):
        verdict = _triage()

    assert verdict.needs_more_info, "a bug with no platform still needs the OS"
    assert "needs-info" in verdict.labels
    assert "ios" in verdict.reply.lower(), "and gets asked which OS it is"


def test_the_report_and_the_issue_corpus_both_reach_the_model():
    with fake_model(FakeResponse(VERDICT)) as messages:
        triage._triage_sync(
            "kb1abc",
            "The QSO simulator freezes when I send fast.",
            [{"number": 17, "title": "QSO simulator freezes", "state": "open"}],
        )

    sent = messages.calls[0]
    text = sent["messages"][0]["content"][0]["text"]
    assert "freezes when I send fast" in text
    assert "#17: QSO simulator freezes" in text, "dedup needs the existing issues"
    assert sent["output_format"] is Verdict
    # The instructions are the cached prefix; the report must not join them.
    assert sent["system"][0]["cache_control"] == {"type": "ephemeral"}


def test_an_oversized_budget_is_refused_at_startup_not_at_the_first_report():
    over = str(config.MAX_OUTPUT_TOKENS + 1)
    saved = os.environ.get("ANTHROPIC_MAX_TOKENS")
    os.environ["ANTHROPIC_MAX_TOKENS"] = over
    try:
        config.Settings.load()
    except RuntimeError as err:
        assert over in str(err), f"say what was wrong with it: {err}"
    else:
        raise AssertionError("a budget over the ceiling should be rejected")
    finally:
        if saved is None:
            del os.environ["ANTHROPIC_MAX_TOKENS"]
        else:
            os.environ["ANTHROPIC_MAX_TOKENS"] = saved


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(dict(globals()).items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            print(f"ok   {name}")
        except AssertionError as err:
            failures += 1
            print(f"FAIL {name}: {err}")
    raise SystemExit(1 if failures else 0)
