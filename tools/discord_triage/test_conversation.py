"""Tests for the thread-memory helpers.

`conversation.py` is deliberately dependency-free, so these run with nothing
installed and no tokens:

    python3 test_conversation.py        # or: pytest test_conversation.py
"""

from __future__ import annotations

from conversation import (
    ELISION,
    RECORDED_MARKER,
    Turn,
    asked_about_platform,
    find_issue_anchor,
    render_transcript,
)

BOT = "AMT Triage"


def _user(mid: int, text: str, author: str = "kb1abc", **kw) -> Turn:
    return Turn(message_id=mid, author=author, text=text, **kw)


def _bot(mid: int, text: str) -> Turn:
    return Turn(message_id=mid, author=BOT, text=text, is_bot=True, is_self=True)


# The conversation this whole feature exists for: one report, four messages.
THREAD = [
    _user(100, "The QSO simulator freezes when I send too fast"),
    _user(101, "iPhone 15, iOS 17.4, app version 1.8.2"),
    _bot(102, "Thanks! Which platform are you seeing this on — iOS, iPadOS, macOS, or Android?"),
    _user(103, "iOS, like I said above", attachments=["freeze.png"]),
]


def test_transcript_keeps_every_message_in_order():
    text = render_transcript(THREAD)
    assert "freezes when I send too fast" in text
    assert "iOS 17.4" in text
    assert text.index("freezes") < text.index("iOS 17.4") < text.index("like I said")


def test_transcript_marks_the_bots_own_messages():
    assert f"{BOT} [bot]:" in render_transcript(THREAD)


def test_transcript_names_attachments():
    assert "[image: freeze.png]" in render_transcript(THREAD)


def test_transcript_skips_empty_messages():
    turns = [_user(1, "real report"), _user(2, "   ")]
    assert render_transcript(turns).splitlines() == ["kb1abc: real report"]


def test_recorded_marker_splits_old_from_new():
    lines = render_transcript(THREAD, recorded_through=101).splitlines()
    assert lines[2] == RECORDED_MARKER
    # Everything before the marker is still there — it is context, not history
    # to be dropped.
    assert "freezes when I send too fast" in lines[0]
    assert "like I said above" in lines[-1]


def test_no_marker_when_nothing_is_new():
    assert RECORDED_MARKER not in render_transcript(THREAD, recorded_through=103)


def test_long_thread_keeps_the_report_and_the_latest():
    turns = [_user(1, "original report")]
    turns += [_user(i, f"filler {i} " + "x" * 200) for i in range(2, 200)]
    turns.append(_user(500, "the newest detail"))
    text = render_transcript(turns, max_chars=2000)
    assert len(text) <= 2000
    assert "original report" in text
    assert "the newest detail" in text
    assert ELISION in text


def test_finds_the_issue_the_bot_announced():
    turns = THREAD + [
        _bot(104, "Logged it — opened #42: https://github.com/n9ho/another-morse-trainer/issues/42 ✅"),
        _user(105, "thanks!"),
    ]
    anchor = find_issue_anchor(turns)
    assert anchor is not None
    assert (anchor.repo, anchor.number) == ("n9ho/another-morse-trainer", 42)
    # The announcement is where the record caught up with the conversation.
    assert anchor.recorded_through == 104


def test_anchor_follows_the_latest_issue_update():
    turns = [
        _bot(1, "opened #7: https://github.com/n9ho/another-morse-trainer/issues/7 ✅"),
        _user(2, "also it only happens at 30 WPM"),
        _bot(3, "Got it — updated #7. ✅"),
        _user(4, "and only with Farnsworth on"),
    ]
    anchor = find_issue_anchor(turns)
    assert anchor.number == 7
    assert anchor.recorded_through == 3  # the newest detail is still unrecorded


def test_android_reports_recover_their_own_repo():
    turns = [_bot(1, "opened #3: https://github.com/n9ho/another-morse-trainer-android/issues/3 ✅")]
    assert find_issue_anchor(turns).repo == "n9ho/another-morse-trainer-android"


def test_a_link_pasted_by_a_reporter_is_not_this_threads_issue():
    turns = [_user(1, "looks like https://github.com/n9ho/another-morse-trainer/issues/9 maybe?")]
    assert find_issue_anchor(turns) is None


def test_no_issue_yet():
    assert find_issue_anchor(THREAD) is None


def test_platform_question_is_detected_once_asked():
    assert asked_about_platform(THREAD)


def test_platform_question_not_detected_before_it_is_asked():
    assert not asked_about_platform(THREAD[:2])


def test_a_reporter_naming_platforms_is_not_the_bot_asking():
    turns = [_user(1, "Is this on iOS or Android?")]
    assert not asked_about_platform(turns)


def test_a_bot_statement_about_platforms_is_not_a_question():
    turns = [_bot(1, "Filed it with the platform: ios label.")]
    assert not asked_about_platform(turns)


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
