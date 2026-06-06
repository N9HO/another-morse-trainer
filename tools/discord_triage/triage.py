"""Claude-powered triage of a Discord message into a structured verdict.

Uses the Anthropic Messages API with structured outputs (a Pydantic schema), so
the response is guaranteed to parse — no fragile string scraping. The triage
instructions live in a cached system prompt; the volatile per-message content
(the report text + the current open-issue list for dedup) goes in the user turn.
"""

from __future__ import annotations

import asyncio
from typing import Literal, Optional

import anthropic
from pydantic import BaseModel, Field

from config import settings

# One shared sync client; calls are dispatched off the event loop via asyncio.to_thread
# so they never block discord.py's loop.
_client = anthropic.Anthropic(api_key=settings.anthropic_api_key)


class Verdict(BaseModel):
    """Structured triage result Claude must return."""

    kind: Literal["bug", "feature", "question", "noise"] = Field(
        description="What the message is. Only 'bug' and 'feature' become GitHub issues."
    )
    should_file: bool = Field(
        description="True only for a genuine, actionable bug report or feature request."
    )
    is_duplicate: bool = Field(
        description="True if an existing open issue already covers this."
    )
    duplicate_of: Optional[int] = Field(
        default=None,
        description="If is_duplicate, the number of the existing issue it duplicates.",
    )
    title: str = Field(description="A concise, specific issue title (<= 80 chars).")
    body: str = Field(
        description=(
            "A clean GitHub issue body in Markdown. For bugs include "
            "Steps to reproduce / Expected / Actual sections when the report "
            "supports them. End with an attribution line crediting the reporter."
        )
    )
    labels: list[str] = Field(
        default_factory=list,
        description="Suggested labels, e.g. 'bug', 'enhancement', 'needs-info'.",
    )
    severity: Literal["low", "medium", "high", "critical", "n/a"] = Field(
        description="Rough severity for a bug; 'n/a' for non-bugs."
    )
    reply: str = Field(
        description="A short, friendly one-line reply to post back in Discord."
    )
    needs_more_info: bool = Field(
        default=False,
        description=(
            "True if this is a real bug/feature but you don't yet have enough detail "
            "to file a good issue and are asking the reporter for more (repro steps, "
            "platform, a screenshot, etc.). In that case 'reply' should be the question."
        ),
    )
    issue_update: str = Field(
        default="",
        description=(
            "When an issue has ALREADY been filed for this thread and the latest reply "
            "adds new information (details, a screenshot you can describe, clarification), "
            "a concise Markdown note to post as a comment on that issue. Empty if there "
            "is nothing new to record."
        ),
    )


# Static instructions — kept stable so the prefix can be prompt-cached.
SYSTEM_PROMPT = """You are the issue-triage assistant for "Another Morse Trainer", \
an iOS/macOS app (Swift) that teaches Morse code: it has practice drills, a QSO \
simulator, a confusion matrix, timing/Farnsworth settings, and progressive character \
training.

Your job: read a report from the project's Discord and decide whether it should \
become a GitHub issue, then produce a clean, well-structured issue if so.

You may be given a SINGLE message or an ongoing CONVERSATION (the original report \
plus follow-up replies and your own earlier questions). Screenshots may be attached \
as images — look at them and fold the relevant details into the issue. When given a \
conversation, base your verdict on ALL of it together, not just the last line.

Guidelines:
- Classify the report as exactly one of: bug, feature, question, noise.
  * bug      = something is broken or behaving wrong.
  * feature  = a request for new or changed functionality.
  * question = a support/usage question that should be answered, not filed.
  * noise    = chatter, greetings, off-topic, or empty content.
- Set should_file = true ONLY for genuine, actionable bugs or feature requests that \
you have ENOUGH detail to write a useful issue for.
- If it's a real bug/feature but too thin to file well, set should_file = false and \
needs_more_info = true, and make 'reply' a specific question for the missing detail \
(repro steps, platform/OS, a screenshot, expected vs actual). Once the follow-ups \
give you enough, set should_file = true.
- Questions and noise are never filed.
- If a screenshot is attached, describe what it shows (error text, screen, UI state) \
in the issue body — the maintainer can't see the image, only your description.
- You are given the list of currently OPEN issues (number + title). If this report is \
clearly already covered by one of them, set is_duplicate = true and duplicate_of to its \
number, and should_file = false.
- Write title and body for a maintainer, not the reporter: turn casual phrasing into a \
precise, reproducible report. Use Markdown. For bugs, include Steps to reproduce, \
Expected, and Actual sections whenever the message gives you enough to fill them; if it \
doesn't, say what's missing and add a 'needs-info' label.
- Reference app areas by name when relevant (e.g. QSO Simulator, Confusion Matrix, \
Timing, Progressive Characters).
- End the body with a line like: "_Reported via Discord by {author}._"
- labels: use 'bug' for bugs and 'enhancement' for features, plus 'needs-info' if the \
report is too thin to act on.
- reply: ALWAYS write a friendly, concise one-liner suitable to post back in the \
Discord thread — even when you are not filing. If you won't file, the reply should say \
why in a helpful way (e.g. what extra detail would let you file it, or that it reads \
like a question/duplicate). Never leave reply empty."""


def _format_open_issues(open_issues: list[dict]) -> str:
    if not open_issues:
        return "(none)"
    return "\n".join(f"#{i['number']}: {i['title']}" for i in open_issues)


def _triage_sync(
    author: str,
    content: str,
    open_issues: list[dict],
    explicit: bool = False,
    images: Optional[list[tuple[str, str]]] = None,
    has_issue: bool = False,
) -> Verdict:
    explicit_note = (
        "\n\nNOTE: A maintainer explicitly flagged this for triage. Treat it as worth "
        "pursuing unless it is a duplicate or clearly not a bug/feature (e.g. pure "
        "chatter). If it's a real bug/feature with enough detail, file it; if it's real "
        "but too thin, set needs_more_info and ask for the missing detail rather than "
        "declining outright."
        if explicit
        else ""
    )
    issue_note = (
        "\n\nNOTE: An issue has ALREADY been filed for this thread. Do not try to file "
        "again — instead, if the latest replies add new information, put a concise "
        "comment in 'issue_update' (otherwise leave it empty)."
        if has_issue
        else ""
    )
    user_text = (
        f"Discord report from {author}:\n"
        f"\"\"\"\n{content}\n\"\"\"\n\n"
        f"Currently open issues (for duplicate detection):\n"
        f"{_format_open_issues(open_issues)}"
        f"{explicit_note}"
        f"{issue_note}"
    )

    blocks: list[dict] = [{"type": "text", "text": user_text}]
    for media_type, data in images or []:
        blocks.append(
            {
                "type": "image",
                "source": {"type": "base64", "media_type": media_type, "data": data},
            }
        )

    response = _client.messages.parse(
        model=settings.model,
        max_tokens=2048,
        system=[
            {
                "type": "text",
                "text": SYSTEM_PROMPT,
                # Cache the stable instructions; the per-message turn stays uncached.
                "cache_control": {"type": "ephemeral"},
            }
        ],
        messages=[{"role": "user", "content": blocks}],
        output_format=Verdict,
    )

    verdict = response.parsed_output
    if verdict is None:
        # Refusal or schema miss — treat as non-actionable rather than crashing.
        return Verdict(
            kind="noise",
            should_file=False,
            is_duplicate=False,
            title="",
            body="",
            labels=[],
            severity="n/a",
            reply="",
        )
    return verdict


async def triage(
    author: str,
    content: str,
    open_issues: list[dict],
    explicit: bool = False,
    images: Optional[list[tuple[str, str]]] = None,
    has_issue: bool = False,
) -> Verdict:
    """Triage a report (single message or full thread transcript) off the event loop.

    `explicit`  = a maintainer directly asked for this (e.g. reacted with the trigger
                  emoji), which biases toward pursuing it.
    `images`    = list of (media_type, base64_data) screenshots to look at.
    `has_issue` = an issue was already filed for this thread, so produce issue_update
                  comments instead of filing again.
    """
    return await asyncio.to_thread(
        _triage_sync, author, content, open_issues, explicit, images, has_issue
    )
