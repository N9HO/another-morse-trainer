"""Turning a Discord thread into triage input: transcript, and issue recovery.

Deliberately free of discord.py and of the bot's configuration, so the parts
that decide *what Claude gets to remember* about a conversation can be unit
tested on their own (see test_conversation.py) without a token or a network.

Two jobs live here:

1. `render_transcript` — the whole conversation as one block of text, in order,
   including the bot's own questions, so a report split across several messages
   is triaged as the single report it is.
2. `find_issue_anchor` — read back out of the conversation which issue the bot
   already filed for it. The thread → issue map is in memory, so a restart used
   to lose it; the bot always announces the issue with its full URL, which makes
   the thread its own durable memory.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterable, Optional, Sequence

# Where the already-recorded part of a conversation ends. Everything after this
# line is what the reporter has added since the issue was last updated, so the
# model can write a comment that adds only the new detail instead of restating
# the whole thread.
RECORDED_MARKER = (
    "--- everything above is already recorded on the GitHub issue; "
    "what follows is new ---"
)
ELISION = "[... earlier messages omitted ...]"

# Bounds on what one triage call carries. Discord caps a message at 4000 chars,
# and a triage thread at 50 messages is already unusual, but a transcript is
# pasted into every follow-up call — so cap it rather than let a long thread
# grow the prompt without limit.
MAX_TURN_CHARS = 4000
MAX_TRANSCRIPT_CHARS = 40_000

# Threads the bot opens itself are named after the message they hang off, so
# their title would only repeat the first line of the transcript.
AUTO_THREAD_PREFIX = "Triage: "


@dataclass
class Turn:
    """One message in a triage conversation."""

    message_id: int
    author: str
    text: str = ""
    attachments: list[str] = field(default_factory=list)
    # Any bot (shown to the model, so it can tell its own questions from the
    # reporter's answers).
    is_bot: bool = False
    # This bot specifically — only our own replies are trusted to say which
    # issue the thread belongs to.
    is_self: bool = False


@dataclass
class IssueAnchor:
    """The issue a thread already has, recovered from the bot's own replies."""

    repo: str
    number: int
    # The last message that announced or updated that issue: everything up to
    # and including it is already reflected on GitHub.
    recorded_through: Optional[int] = None


def render_turn(turn: Turn) -> str:
    """One transcript line, or "" when the message carries nothing to read."""
    who = turn.author + (" [bot]" if turn.is_bot else "")
    text = turn.text.strip()
    if len(text) > MAX_TURN_CHARS:
        text = text[:MAX_TURN_CHARS] + " […]"
    atts = " ".join(f"[image: {name}]" for name in turn.attachments)
    body = " ".join(part for part in (text, atts) if part)
    return f"{who}: {body}" if body else ""


def _trim(lines: list[str], max_chars: int, keep_head: int = 1) -> list[str]:
    """Keep the transcript under budget, oldest first — but never drop the head.

    The opening lines are the title and the original report; the most recent
    lines are where the reporter's latest thoughts are. It's the middle that
    can go.
    """
    if sum(len(line) + 1 for line in lines) <= max_chars or len(lines) <= keep_head:
        return lines
    head, rest = lines[:keep_head], lines[keep_head:]
    budget = max_chars - sum(len(line) + 1 for line in head) - len(ELISION) - 2
    tail: list[str] = []
    for line in reversed(rest):
        if len(line) + 1 > budget:
            break
        tail.append(line)
        budget -= len(line) + 1
    return head + [ELISION] + list(reversed(tail))


def render_transcript(
    turns: Sequence[Turn],
    recorded_through: Optional[int] = None,
    title: Optional[str] = None,
    max_chars: int = MAX_TRANSCRIPT_CHARS,
) -> str:
    """The conversation as text, with the already-recorded part marked off.

    `title` is the thread's own name. In a forum channel that is where the
    reporter writes their headline ("In QRQ Speed the UI might need to move up
    the screen"), which is often the one place the affected screen is named —
    reading only the messages threw it away.

    `recorded_through` is the id of the newest message whose content already
    reached the GitHub issue. Discord snowflake ids increase with time, so
    "newer than that id" is "posted after we last updated the issue" — and the
    marker survives a deleted message, which an index would not.
    """
    lines: list[str] = []
    if title:
        lines.append(f"Thread title: {title}")
    marked = recorded_through is None
    for turn in turns:
        if not marked and turn.message_id > recorded_through:
            lines.append(RECORDED_MARKER)
            marked = True
        rendered = render_turn(turn)
        if rendered:
            lines.append(rendered)
    # Protect the title and the opening report from being trimmed away.
    return "\n".join(_trim(lines, max_chars, keep_head=2 if title else 1))


def thread_title(name: Optional[str]) -> Optional[str]:
    """The thread's title, when it tells us something the messages don't.

    A forum post's title is the reporter's headline and belongs in the report.
    A thread the bot opened itself is named after the message it hangs off, so
    its title is just the transcript's first line again.
    """
    name = (name or "").strip()
    if not name or name.startswith(AUTO_THREAD_PREFIX):
        return None
    return name


_ISSUE_URL_RE = re.compile(
    r"https://github\.com/([A-Za-z0-9._-]+/[A-Za-z0-9._-]+)/issues/(\d+)"
)


def find_issue_anchor(turns: Iterable[Turn]) -> Optional[IssueAnchor]:
    """Which issue this thread already has, per the bot's own messages.

    Only the bot's own replies count: a reporter pasting an issue link is
    talking about some other issue, not declaring this thread's.
    """
    repo: Optional[str] = None
    number: Optional[int] = None
    for turn in turns:
        if not turn.is_self:
            continue
        match = _ISSUE_URL_RE.search(turn.text)
        if match:
            # The last one wins: if the bot ever re-filed, the newest link is
            # the live issue.
            repo, number = match.group(1), int(match.group(2))
    if repo is None or number is None:
        return None

    # The most recent message mentioning that issue is where the record last
    # caught up with the conversation ("opened #12: …" or "updated #12").
    recorded_through: Optional[int] = None
    needle = f"#{number}"
    for turn in turns:
        if turn.is_self and needle in turn.text:
            recorded_through = turn.message_id
    return IssueAnchor(repo=repo, number=number, recorded_through=recorded_through)


def bot_already_said(turns: Iterable[Turn], phrase: str) -> bool:
    """Has the bot already said this in the thread?

    Cheap protection against repeating ourselves: every trigger re-reads the
    whole conversation, so a verdict that doesn't change (a duplicate pointer,
    say) would otherwise be announced again on every follow-up message.
    """
    low = phrase.lower()
    return any(turn.is_self and low in turn.text.lower() for turn in turns)


def mentioned_issue(turns: Iterable[Turn], number: int) -> bool:
    """Has the bot already named issue `number` in this thread?

    `bot_already_said` matches a substring, which cannot be used for an issue
    number: "#41" is a substring of "#413", so pointing a reporter at #41 would
    silence a later, genuine pointer at #413. The digits have to end where the
    number ends.
    """
    pattern = re.compile(rf"#{number}(?!\d)")
    return any(turn.is_self and pattern.search(turn.text or "") for turn in turns)


_PLATFORM_NAMES = ("ios", "ipados", "macos", "android")


def asked_about_platform(turns: Iterable[Turn]) -> bool:
    """Has the bot already asked this thread which OS the bug is on?

    Once asked, asking again on every follow-up is the behavior that makes the
    bot look like it isn't reading the thread — so the forced platform question
    fires at most once, and after that the model decides for itself whether
    anything is still worth asking.
    """
    for turn in turns:
        if not turn.is_self:
            continue
        low = turn.text.lower()
        if "?" not in low:
            continue
        if "platform" in low or sum(name in low for name in _PLATFORM_NAMES) >= 2:
            return True
    return False
