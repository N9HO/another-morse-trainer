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
    platform: Literal[
        "ios", "ipados", "macos", "android", "multiple", "unknown", "n/a"
    ] = Field(
        default="unknown",
        description=(
            "Which OS the report is about, when stated or clearly implied: 'ios', "
            "'ipados', 'macos', 'android', 'multiple' (affects more than one), or "
            "'unknown' if the reporter hasn't said. Use 'n/a' for questions/noise. "
            "AMT ships on all of these and bugs are often platform-specific, so "
            "an unknown platform is always worth asking about — but it no longer "
            "blocks filing."
        ),
    )
    reply: str = Field(
        description="A short, friendly one-line reply to post back in Discord."
    )
    needs_more_info: bool = Field(
        default=False,
        description=(
            "True if this is a real bug/feature that is still missing detail you "
            "are asking the reporter for (repro steps, platform, a screenshot, etc.). "
            "In that case 'reply' should be the question. It is filed regardless, "
            "labelled 'needs-info' — this flag shapes the issue and the reply, it "
            "does not withhold the report."
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
SYSTEM_PROMPT = """You are the issue-triage assistant for "Another Morse Trainer" \
(AMT), a cross-platform app that teaches Morse code. It ships on Apple platforms \
(iOS, iPadOS, macOS — Swift) and on Android (a separate port), and has practice \
drills, a QSO simulator, a confusion matrix, timing/Farnsworth settings, and \
progressive character training.

Your job: read a report from the project's Discord and decide whether it should \
become a GitHub issue, then produce a clean, well-structured issue if so.

You may be given a SINGLE message or an ongoing CONVERSATION (the original report \
plus follow-up replies and your own earlier questions). Screenshots may be attached \
as images — look at them and fold the relevant details into the issue.

A CONVERSATION IS ONE REPORT, NOT A SERIES OF THEM. Reporters routinely split a \
single thought across several consecutive messages, and answer your questions \
several messages after you ask them. So:
- Read the WHOLE transcript before deciding anything, and base every field on all of \
it together — never on the last line alone. Details stated anywhere in the \
conversation (or visible in an attached screenshot) are KNOWN, no matter which \
message they arrived in or how long ago.
- Lines marked [bot] are your own earlier messages; the messages after one of your \
questions are the answers to it.
- NEVER ask for something the conversation has already given you. Re-asking a \
question the reporter answered earlier in the thread is the worst thing you can do \
here — it reads as though you weren't listening, and it is the reason this \
instruction exists. Before you set needs_more_info or put a question in 'reply', \
re-read the transcript and confirm the detail really is absent from ALL of it. If \
everything you asked for has now arrived, set needs_more_info = false, acknowledge \
what they told you, and move on instead of asking again.
- A transcript may contain the line "--- everything above is already recorded on the \
GitHub issue; what follows is new ---". Everything above that line is context you \
must still take into account; what is below it is what the issue does not know yet.
- A transcript may open with "Thread title: …". In a forum channel that is the \
reporter's own headline for the report, and it often names the screen or feature \
that the messages under it never repeat. Treat it as part of the report.
- Several people may take part. The reporter is whoever opened the thread; the others \
are helping, and their diagnosis ("that means AMT is sending MIDI messages that \
change the adapter's settings") is often the most useful thing in the thread — put it \
in the issue body, credited, rather than dropping it because it didn't come from the \
reporter.
- A thread can carry more than one idea: a bug, plus a suggestion for the setting \
that would fix it. You produce ONE verdict, so file the primary report — the bug, if \
there is one — and record the related suggestion in the body under its own heading \
(e.g. "### Also requested") so it isn't lost.

Guidelines:
- Classify the report as exactly one of: bug, feature, question, noise.
  * bug      = something is broken or behaving wrong.
  * feature  = a request for new or changed functionality.
  * question = a support/usage question that should be answered, not filed.
  * noise    = chatter, greetings, off-topic, or empty content.
- Set should_file = true for genuine, actionable bugs or feature requests.
- If it's a real bug/feature but still thin, set needs_more_info = true and make \
'reply' a specific question for the missing detail (repro steps, platform/OS, a \
screenshot, expected vs actual). It gets FILED anyway, with a 'needs-info' label — \
a report that exists only in Discord is lost the moment the reporter stops replying, \
so we would rather hold an incomplete issue than none at all. Because it will be \
filed either way, ALWAYS write a usable title and body, and in the body say plainly \
what is still unknown under a "### Still needed" heading. Answers that arrive later \
are folded in as issue comments, so the issue gets completed rather than replaced.
- should_file = false is for things that must never become issues: questions, noise, \
and duplicates.
- ALWAYS ESTABLISH THE PLATFORM FOR BUGS. AMT runs on iOS, iPadOS, macOS, and \
Android, and bugs are frequently platform-specific, so which OS a bug is on is the \
single most valuable missing detail. Set the 'platform' field from what the reporter \
says (or clearly implies) ANYWHERE in the conversation — including a reply to an \
earlier question of yours, and including a device or OS version they mentioned in \
passing — and keep setting it on every later pass, so a platform established once \
isn't forgotten. \
If a bug doesn't state the platform, still file it, but set platform = \
'unknown' and needs_more_info = true, and make 'reply' ask specifically which OS \
they're reporting for — naming the options (iOS / iPadOS / macOS / Android) and \
asking for the OS/app version too. Once you know it, set 'platform', put a \
"**Platform:** <os> (version if known)" line near the TOP of the issue body, and add \
the matching platform label.
- Questions and noise are never filed.
- If a screenshot is attached, describe what it shows (error text, screen, UI state) \
in the issue body — the maintainer can't see the image, only your description.
- You are given the list of currently OPEN issues (number + title). If this report is \
clearly already covered by one of them, set is_duplicate = true and duplicate_of to its \
number, and should_file = false. Only ever set is_duplicate = true together with the \
NUMBER in duplicate_of. If you can't point at a specific open issue, it is not a \
duplicate — treat it as a new report and let it be filed. A vague "this feels \
familiar" loses the report entirely: nothing gets filed and the reporter gets no \
issue to follow. Note the list covers the main repo only, so an Android report may \
have a twin you cannot see.
- Write title and body for a maintainer, not the reporter: turn casual phrasing into a \
precise, reproducible report. Use Markdown. For bugs, include Steps to reproduce, \
Expected, and Actual sections whenever the message gives you enough to fill them; if it \
doesn't, say what's missing and add a 'needs-info' label.
- Reference app areas by name when relevant (e.g. QSO Simulator, Confusion Matrix, \
Timing, Progressive Characters).
- End the body with a line like: "_Reported via Discord by {author}._"
- labels: use 'bug' for bugs and 'enhancement' for features, plus 'needs-info' if the \
report is too thin to act on. When you know the platform, also add a platform label: \
'platform: ios', 'platform: ipados', 'platform: macos', 'platform: android', or \
'platform: multiple'.
- reply: ALWAYS write a friendly, concise one-liner suitable to post back in the \
Discord thread — even when you are not filing. If you won't file, the reply should say \
why in a helpful way (e.g. what extra detail would let you file it, or that it reads \
like a question/duplicate). Never leave reply empty."""


def _format_open_issues(open_issues: list[dict]) -> str:
    if not open_issues:
        return "(none)"
    return "\n".join(f"#{i['number']}: {i['title']}" for i in open_issues)


# GitHub label per platform. Missing labels are created automatically when the
# issue is filed via the REST API.
_PLATFORM_LABELS = {
    "ios": "platform: ios",
    "ipados": "platform: ipados",
    "macos": "platform: macos",
    "android": "platform: android",
    "multiple": "platform: multiple",
}

# Asked when a bug arrives without a platform. AMT is cross-platform, so we never
# file a bug without knowing which OS it's on.
PLATFORM_QUESTION = (
    "Thanks for the report! Which platform are you seeing this on — "
    "iOS, iPadOS, macOS, or Android? (Your OS and app version help too.)"
)


def _postprocess_platform(v: Verdict, ask_platform: bool = True) -> Verdict:
    """Enforce the platform policy regardless of the model's judgment.

    A bug whose platform we don't know is still filed — it just carries
    'needs-info' and gets the OS question asked in Discord. Withholding it used
    to leave the only record in Discord, so a reporter who never answered meant
    the maintainer never learned the report existed at all; an unrouted issue
    you can see beats one you never hear about. A known platform always gets its
    label so issues stay filterable.

    `ask_platform` is False once the thread has already been asked which OS it
    is. The question is worth forcing once; forcing it onto every later reply is
    how the bot ends up asking something the reporter answered three messages
    ago. The issue still gets flagged 'needs-info' — the model just gets to
    decide for itself whether anything is still worth asking out loud.
    """
    # A bug with no platform: file it, flag it, and (the first time) ask which OS.
    if v.kind == "bug" and v.platform in ("unknown", "n/a"):
        v.needs_more_info = True
        if "needs-info" not in v.labels:
            v.labels.append("needs-info")
        # Make sure the reply actually asks about the OS.
        low = v.reply.lower()
        if ask_platform and not any(
            p in low for p in ("ios", "ipados", "macos", "android", "platform")
        ):
            v.reply = PLATFORM_QUESTION

    # Anything still missing detail is labelled as such, whoever noticed.
    if v.needs_more_info and "needs-info" not in v.labels:
        v.labels.append("needs-info")

    # A thin report is filed too, so it needs a usable title and body either
    # way — an empty issue would be worse than the Discord message it replaces.
    if v.needs_more_info and not v.title.strip():
        v.title = f"[needs info] {v.kind} reported via Discord"
    if v.needs_more_info and not v.body.strip():
        v.body = (
            "A report came in via Discord that wasn't detailed enough to write up "
            "properly, filed so it isn't lost.\n\n"
            "### Still needed\n"
            "Repro steps, the platform and version, and what was expected versus "
            "what happened. The reporter has been asked in the Discord thread; "
            "answers will be added here as comments."
        )

    # Attach the platform label whenever we know it (rides along to create_issue).
    label = _PLATFORM_LABELS.get(v.platform)
    if label and label not in v.labels:
        v.labels.append(label)
    return v


def _triage_sync(
    author: str,
    content: str,
    open_issues: list[dict],
    explicit: bool = False,
    images: Optional[list[tuple[str, str]]] = None,
    has_issue: bool = False,
    ask_platform: bool = True,
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
        "again. If the conversation now carries information the issue does not have "
        "yet, put a concise comment in 'issue_update' covering ONLY what is new — the "
        "transcript marks where the recorded part ends, and repeating detail that is "
        "already on the issue just clutters it. Leave 'issue_update' empty when the "
        "latest messages add nothing (chatter, thanks, a question you can answer in "
        "'reply')."
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
    return _postprocess_platform(verdict, ask_platform)


async def triage(
    author: str,
    content: str,
    open_issues: list[dict],
    explicit: bool = False,
    images: Optional[list[tuple[str, str]]] = None,
    has_issue: bool = False,
    ask_platform: bool = True,
) -> Verdict:
    """Triage a report (single message or full thread transcript) off the event loop.

    `explicit`  = a maintainer directly asked for this (e.g. reacted with the trigger
                  emoji), which biases toward pursuing it.
    `images`    = list of (media_type, base64_data) screenshots to look at.
    `has_issue` = an issue was already filed for this thread, so produce issue_update
                  comments instead of filing again.
    `ask_platform` = False once this thread has already been asked which OS it is on,
                  so the forced OS question isn't repeated at every reply.
    """
    return await asyncio.to_thread(
        _triage_sync,
        author,
        content,
        open_issues,
        explicit,
        images,
        has_issue,
        ask_platform,
    )
