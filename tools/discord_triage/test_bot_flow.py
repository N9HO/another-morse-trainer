"""End-to-end tests of thread memory, with fake Discord/GitHub/Claude.

These need the project's dependencies (discord.py, anthropic, pydantic) and a
dummy environment, both of which the README's "Run locally" setup provides:

    DISCORD_BOT_TOKEN=x ANTHROPIC_API_KEY=x GITHUB_TOKEN=x \\
      GITHUB_REPO=owner/repo python3 test_bot_flow.py

They cover the behavior the bot is judged on: a report split across several
messages is triaged once, as one report, and a thread never loses track of the
issue it already has.
"""

from __future__ import annotations

import asyncio
import dataclasses
import itertools
import os

os.environ.setdefault("DISCORD_BOT_TOKEN", "x")
os.environ.setdefault("ANTHROPIC_API_KEY", "x")
os.environ.setdefault("GITHUB_TOKEN", "x")
os.environ.setdefault("GITHUB_REPO", "n9ho/another-morse-trainer")

import bot  # noqa: E402
from conversation import RECORDED_MARKER  # noqa: E402
from triage import Verdict  # noqa: E402

REPO = "n9ho/another-morse-trainer"
_ids = itertools.count(1000)


# --- fakes --------------------------------------------------------------------


class FakeUser:
    def __init__(self, uid: int, name: str, is_bot: bool = False):
        self.id, self.display_name, self.bot = uid, name, is_bot


REPORTER = FakeUser(1, "kb1abc")
BOT_USER = FakeUser(2, "AMT Triage", is_bot=True)


class FakeAttachment:
    def __init__(self, filename: str):
        self.filename, self.content_type, self.size = filename, "image/png", 10

    async def read(self):
        return b"not really a png"


class FakeMessage:
    def __init__(self, author: FakeUser, content: str):
        self.id, self.author, self.content, self.attachments = next(_ids), author, content, []


class FakeThread:
    """Just enough discord.Thread for _gather_thread and _say."""

    def __init__(self, messages: list[FakeMessage], name: str = "Triage: a report"):
        self.id = next(_ids)
        self.name = name
        self.messages = list(messages)
        self.archived = False
        self.parent = None

    @property
    def starter_message(self):
        return self.messages[0] if self.messages else None

    def history(self, limit=None, oldest_first=True):
        messages = self.messages[:limit]

        async def gen():
            for m in messages:
                yield m

        return gen()

    async def send(self, text: str):
        self.messages.append(FakeMessage(BOT_USER, text))

    def post(self, text: str) -> FakeMessage:
        message = FakeMessage(REPORTER, text)
        self.messages.append(message)
        return message

    @property
    def said(self) -> list[str]:
        return [m.content for m in self.messages if m.author is BOT_USER]


class Harness:
    """Swaps the bot's collaborators for recorders, and restores them after."""

    def __init__(self, verdicts: list[Verdict]):
        self.verdicts = list(verdicts)
        self.transcripts: list[str] = []
        self.calls: list[dict] = []
        self.created: list[dict] = []
        self.comments: list[tuple[int, str]] = []
        self.stamp_lookups = 0
        self.stamped: dict | None = None

    async def _triage(self, author, content, open_issues, **kw):
        self.transcripts.append(content)
        self.calls.append(kw)
        return self.verdicts.pop(0) if self.verdicts else _verdict()

    async def _create_issue(self, title, body, labels, repo=None):
        self.created.append({"title": title, "body": body, "repo": repo})
        number = 40 + len(self.created)
        return {"number": number, "html_url": f"https://github.com/{repo}/issues/{number}"}

    async def _comment_issue(self, number, body, repo=None):
        self.comments.append((number, body))
        return {"html_url": "https://github.com/x/y/issues/1#comment"}

    async def _find_issue_for_thread(self, thread_id, repos):
        self.stamp_lookups += 1
        return self.stamped

    def __enter__(self):
        self._saved = {
            name: getattr(bot, name)
            for name in ("triage", "create_issue", "comment_issue",
                         "list_open_issues", "find_issue_for_thread", "settings", "client")
        }
        bot.triage = self._triage
        bot.create_issue = self._create_issue
        bot.comment_issue = self._comment_issue
        bot.list_open_issues = _no_open_issues
        bot.find_issue_for_thread = self._find_issue_for_thread
        # A short settle keeps the tests quick; the coalescing logic is the same.
        bot.settings = dataclasses.replace(self._saved["settings"], settle_seconds=0.05)
        bot.client = FakeUserClient()
        bot.pending.clear()
        return self

    def __exit__(self, *exc):
        for name, value in self._saved.items():
            setattr(bot, name, value)
        bot.pending.clear()
        return False


class FakeUserClient:
    user = BOT_USER


async def _no_open_issues(limit=50):
    return []


def _verdict(**kw) -> Verdict:
    base = dict(
        kind="bug", should_file=True, is_duplicate=False, title="QSO simulator freezes",
        body="### Steps\n…", labels=["bug"], severity="medium", platform="ios",
        reply="Thanks — logged it.", needs_more_info=False, issue_update="",
    )
    base.update(kw)
    return Verdict(**base)


def run(coro):
    return asyncio.run(coro)


# --- tests --------------------------------------------------------------------


def test_a_burst_of_messages_is_one_triage_over_the_whole_thread():
    """The reported bug: three thoughts, three messages, one report."""
    with Harness([_verdict()]) as h:
        thread = FakeThread([FakeMessage(REPORTER, "QSO sim freezes when I send fast")])

        async def scenario():
            first = asyncio.create_task(bot._triage_thread(thread, explicit=False))
            await asyncio.sleep(0.01)
            thread.post("iPhone 15, iOS 17.4")
            second = asyncio.create_task(bot._triage_thread(thread, explicit=False))
            await asyncio.sleep(0.01)
            thread.post("only above 25 WPM")
            third = asyncio.create_task(bot._triage_thread(thread, explicit=False))
            await asyncio.gather(first, second, third)

        run(scenario())

    assert len(h.transcripts) == 1, f"expected one triage, got {len(h.transcripts)}"
    assert len(h.created) == 1, "a burst must not file three issues"
    transcript = h.transcripts[0]
    for detail in ("freezes when I send fast", "iOS 17.4", "25 WPM"):
        assert detail in transcript, f"{detail!r} missing from the transcript"


def test_the_bots_own_question_and_its_answer_reach_the_next_pass():
    with Harness([_verdict(platform="unknown", needs_more_info=True,
                           reply="Which platform — iOS, iPadOS, macOS, or Android?"),
                  _verdict(issue_update="Platform: iOS 17.4 on an iPhone 15.")]) as h:
        thread = FakeThread([FakeMessage(REPORTER, "QSO sim freezes")])
        run(bot._triage_thread(thread, explicit=True))
        thread.post("iOS 17.4, iPhone 15")
        run(bot._triage_thread(thread, explicit=True))

    second = h.transcripts[1]
    assert "Which platform" in second, "the bot's own question must be in the transcript"
    assert "iOS 17.4" in second
    # Having asked once, it doesn't force the OS question again.
    assert h.calls[0]["ask_platform"] is True
    assert h.calls[1]["ask_platform"] is False
    # And the follow-up updates the issue instead of filing a second one.
    assert len(h.created) == 1
    assert h.comments and h.comments[0][0] == 41


def test_a_follow_up_only_reports_what_is_new():
    with Harness([_verdict(), _verdict(issue_update="Also happens with Farnsworth on.")]) as h:
        thread = FakeThread([FakeMessage(REPORTER, "QSO sim freezes")])
        run(bot._triage_thread(thread, explicit=True))
        thread.post("also with Farnsworth on")
        run(bot._triage_thread(thread, explicit=True))

    assert RECORDED_MARKER in h.transcripts[1], "the recorded part must be marked off"
    assert h.transcripts[1].index("QSO sim freezes") < h.transcripts[1].index(RECORDED_MARKER)
    assert h.calls[1]["has_issue"] is True


def test_a_restart_recovers_the_issue_from_the_thread():
    with Harness([_verdict(), _verdict(issue_update="Only above 25 WPM.")]) as h:
        thread = FakeThread([FakeMessage(REPORTER, "QSO sim freezes")])
        run(bot._triage_thread(thread, explicit=True))
        assert len(h.created) == 1

        bot.pending.clear()  # the bot restarted: the thread -> issue map is gone
        thread.post("only above 25 WPM")
        run(bot._triage_thread(thread, explicit=True))

    assert len(h.created) == 1, "a forgotten thread must not file a second issue"
    assert h.comments == [(41, "Only above 25 WPM.\n\n_Added via Discord._")]
    assert h.stamp_lookups == 0, "the thread's own transcript already had the answer"


def test_a_deleted_announcement_falls_back_to_the_issue_stamp():
    with Harness([_verdict(issue_update="Only above 25 WPM.")]) as h:
        h.stamped = {"number": 41, "repo": REPO}
        thread = FakeThread([
            FakeMessage(REPORTER, "QSO sim freezes"),
            FakeMessage(BOT_USER, "Thanks — could you say which platform?"),
            FakeMessage(REPORTER, "iOS, only above 25 WPM"),
        ])
        run(bot._triage_thread(thread, explicit=True))

    assert h.stamp_lookups == 1
    assert not h.created, "the stamp said this thread already has an issue"
    assert h.comments == [(41, "Only above 25 WPM.\n\n_Added via Discord._")]


def test_a_fresh_thread_costs_no_github_lookup():
    with Harness([_verdict()]) as h:
        run(bot._triage_thread(FakeThread([FakeMessage(REPORTER, "QSO sim freezes")]),
                               explicit=True))
    assert h.stamp_lookups == 0


def test_auto_mode_stays_quiet_on_chatter():
    with Harness([_verdict(kind="noise", should_file=False, platform="n/a",
                           severity="n/a", reply="👍")]) as h:
        thread = FakeThread([FakeMessage(REPORTER, "gm all")])
        run(bot._triage_thread(thread, explicit=False))
    assert thread.said == [], "unprompted chatter gets silence, not a reply"
    assert not h.created


def test_the_forum_post_from_the_bug_report():
    """The real thing: a forum post, an answer already given, two 🐛 reactions.

    Reported at 6:57 ("the keyboard covers the submit button"), answered at
    7:01 ("Android, latest version"), a screenshot after it — and a maintainer
    reacting to both the report and the screenshot. The bot used to answer
    twice, and ask which OS both times.
    """
    with Harness([_verdict(platform="android")]) as h:
        thread = FakeThread(
            [FakeMessage(REPORTER, "When I'm using the on-screen keyboard everything "
                                   "below the data entry window is covered by the keyboard.")],
            name="In QRQ Speed the UI might need to move up the screen.",
        )
        thread.post("Android, latest version.")
        screenshot = thread.post("I have a screenshot")
        screenshot.attachments = [FakeAttachment("qrq.png")]

        async def two_reactions_a_second_apart():
            first = asyncio.create_task(bot._triage_thread(thread, explicit=True))
            await asyncio.sleep(0.01)
            second = asyncio.create_task(bot._triage_thread(thread, explicit=True))
            await asyncio.gather(first, second)

        run(two_reactions_a_second_apart())

    assert len(h.transcripts) == 1, "reacting to two messages is one triage of the thread"
    assert len(thread.said) == 1, "the reporter gets one answer, not two"
    transcript = h.transcripts[0]
    # The OS was answered in message two, and the screen is in the post's title.
    assert "Android, latest version." in transcript
    assert "Thread title: In QRQ Speed" in transcript
    assert "the keyboard" in transcript


def test_a_bot_opened_threads_title_is_not_repeated():
    with Harness([_verdict()]) as h:
        thread = FakeThread([FakeMessage(REPORTER, "QSO sim freezes")],
                            name="Triage: QSO sim freezes")
        run(bot._triage_thread(thread, explicit=True))
    assert "Thread title:" not in h.transcripts[0]


def test_a_duplicate_pointer_is_given_once_not_on_every_reply():
    dup = _verdict(should_file=False, is_duplicate=True, duplicate_of=17,
                   reply="Looks like one we already have.")
    with Harness([dup, dup]) as h:
        thread = FakeThread([FakeMessage(REPORTER, "QSO sim freezes")])
        run(bot._triage_thread(thread, explicit=True))
        thread.post("any workaround?")
        run(bot._triage_thread(thread, explicit=True))

    assert thread.said == ["Looks like a duplicate of #17. 🔁"]
    assert not h.created


def test_the_filed_issue_is_stamped_with_its_thread():
    with Harness([_verdict()]) as h:
        thread = FakeThread([FakeMessage(REPORTER, "QSO sim freezes")])
        run(bot._triage_thread(thread, explicit=True))
    assert f"<!-- discord-thread:{thread.id} -->" in h.created[0]["body"]


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
