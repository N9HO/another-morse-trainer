"""Claude-powered triage of a Discord message into a structured verdict.

Uses the Anthropic Messages API with structured outputs (a Pydantic schema), so
a verdict that arrives comes in the shape we asked for — no fragile string
scraping. It is not a guarantee that one arrives: an answer cut short by the
output budget is truncated JSON, which the SDK rejects, and a refusal carries no
verdict at all. Both raise TriageError so the caller can say so out loud.

The triage instructions live in a cached system prompt; the volatile per-message
content (the report text + the current open-issue list for dedup) goes in the
user turn.
"""

from __future__ import annotations

import asyncio
from typing import Literal, Optional

import anthropic
from pydantic import BaseModel, Field, ValidationError

from config import MAX_OUTPUT_TOKENS, settings

# One shared sync client; calls are dispatched off the event loop via asyncio.to_thread
# so they never block discord.py's loop.
_client = anthropic.Anthropic(api_key=settings.anthropic_api_key)


class TriageError(RuntimeError):
    """The model did not come back with a verdict we can act on.

    Raised rather than swallowed: a verdict we never got is not the same thing
    as a report that is noise, and quietly calling it noise is how a real bug
    disappears without anyone hearing about it. The caller logs this and (on an
    explicit trigger) says so in the thread, so a failure is visible instead of
    looking like the bot ignored the report.
    """


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
(AMT), which teaches Morse code. It is two apps with ONE feature set: an Apple app \
(iOS, iPadOS, macOS) and an Android app. Every feature is meant to exist on both, and \
a bug seen on one is checked on the other before its issue closes — so a feature \
request is always for both apps, whichever one the reporter uses, and a bug report \
records where it was SEEN, not where anyone guesses the fault lives.

Its modes: Journey, Characters (Koch ladder), Common Words, Abbreviations, Q-Codes, \
Prosigns, CW 77, Confusion Drill, Head Copy, Type It / QRQ Speed, Rapid Fire, \
Pileup Runner (the QSO simulator), Contest, Code Exam, Short Stories, Daily Dit, \
Listen & Learn, Sending Practice, Sending Analyzer, CW Decoder, Reference, and the \
arcade games (Morse Invaders, CW Galaga, Morse Defender, CW Dungeon, CW Frogger, \
CW Asteroids). Around them: answering by voice, keyboard or keying; on-screen \
paddles; hardware keys (Vail Adapter / MIDI, Bluetooth LE MIDI); the Vail repeater; \
the shared Leaderboard; Buddy streaks; Progress; and Settings (Sound, Speed & Timing \
including Farnsworth, and more).

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
"**Seen on:** <os> (version if known)" line near the TOP of the issue body, and add \
the matching platform label.
- For a FEATURE, the platform the reporter uses is context, not scope: the feature is \
for both apps. Say "**Requested from:** <os>" in the body if you know it, and set \
platform = 'multiple'.
- Do not write a parity or "Shipped on" checklist yourself — the bot appends the \
right one to every issue it files.
- Questions and noise are never filed.
- If a screenshot is attached, describe what it shows (error text, screen, UI state) \
in the issue body — the maintainer can't see the image, only your description.
- You are given the existing issues, each marked OPEN or CLOSED, with its labels and \
the first lines of its body. If this report is clearly already covered by one of them, \
set is_duplicate = true and duplicate_of to its number, and should_file = false. Only \
ever set is_duplicate = true together with the NUMBER in duplicate_of. If you can't \
point at a specific issue, it is not a duplicate — treat it as a new report and let it \
be filed. A vague "this feels familiar" loses the report entirely: nothing gets filed \
and the reporter gets no issue to follow.
- A CLOSED issue counts as a duplicate too, and it is the more useful catch: it \
usually means the reporter is on a build from before the fix. Point at it the same \
way — is_duplicate = true, duplicate_of = its number — and make 'reply' say it is \
already fixed and ask them to update to the latest version and report back if it \
persists. Do not claim a fix shipped in a specific version unless the issue says so. \
Two exceptions, where the right answer is a NEW report rather than a duplicate: the \
closed issue was closed as not planned, or the reporter has already said they are on \
the current version — in that case it is a regression, so set is_duplicate = false and \
let it file, and say in the body that it resurfaces the closed issue.
- Write title and body for a maintainer, not the reporter: turn casual phrasing into a \
precise, reproducible report. Use Markdown. For bugs, include Steps to reproduce, \
Expected, and Actual sections whenever the message gives you enough to fill them; if it \
doesn't, say what's missing and add a 'needs-info' label.
- Reference app areas by the mode names above when relevant (e.g. Pileup Runner, \
Confusion Drill, Speed & Timing settings, Characters).
- End the body with a line like: "_Reported via Discord by {author}._"
- labels: use 'bug' for bugs and 'enhancement' for features, plus 'needs-info' if the \
report is too thin to act on. When you know a bug's platform, also add a platform \
label: 'platform: ios', 'platform: ipados', 'platform: macos', 'platform: android', or \
'platform: multiple'. A feature is always 'platform: multiple'.
- reply: ALWAYS write a friendly, concise one-liner suitable to post back in the \
Discord thread — even when you are not filing. If you won't file, the reply should say \
why in a helpful way (e.g. what extra detail would let you file it, or that it reads \
like a question/duplicate). Never leave reply empty."""


def _format_issue_corpus(issues: list[dict]) -> str:
    """One line per issue: state, number, title, labels, and a body snippet.

    The state is what lets the model tell "already tracked" from "already
    fixed", so it leads the line. `state_reason` matters for exactly one case —
    a not-planned close is not a fix, and re-reporting it should file.
    """
    if not issues:
        return "(none)"
    lines = []
    for i in issues:
        state = str(i.get("state", "open")).upper()
        reason = i.get("state_reason") or ""
        if state == "CLOSED" and reason:
            state = f"CLOSED/{reason}"
        line = f"[{state}] #{i['number']}: {i['title']}"
        labels = i.get("labels") or []
        if labels:
            line += f"  (labels: {', '.join(labels)})"
        snippet = (i.get("snippet") or "").strip()
        if snippet:
            line += f"\n    {snippet}"
        lines.append(line)
    return "\n".join(lines)


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


# The Parity / Shipped on checklists from .github/ISSUE_TEMPLATE/bug.yml and
# feature.yml, worded the same. Every change ships on both apps (PARITY.md), and
# these boxes are how an issue says so before it closes; a bot-filed issue
# without them is the one kind that could close with only one side done.
_PARITY_CHECKLISTS = {
    "bug": (
        "### Parity\n"
        "_Filled in by whoever fixes it. Both boxes, or a note in PARITY.md, "
        "before this issue closes._\n"
        "- [ ] Fixed or confirmed absent on iOS\n"
        "- [ ] Fixed or confirmed absent on Android"
    ),
    "feature": (
        "### Shipped on\n"
        "_Tick as each side merges. A single pull request may tick both._\n"
        "- [ ] iOS / iPadOS / macOS\n"
        "- [ ] Android"
    ),
}


def with_parity_checklist(kind: str, body: str) -> str:
    """`body` with the checklist for `kind` appended, if it has one and lacks it.

    Applied at filing time rather than to the verdict, because a verdict's body
    also becomes the comment attached to an issue this report duplicates, and
    that issue already has its checklist.
    """
    checklist = _PARITY_CHECKLISTS.get(kind)
    if checklist is None or checklist.splitlines()[0] in body:
        return body
    return f"{body.rstrip()}\n\n{checklist}"


# What each trigger emoji says the maintainer thinks a report is. A hint, not a
# ruling: the model is told, and files it as something else only when the report
# clearly is something else (a ✨ on what is plainly a crash is still a bug). An
# emoji not listed here triggers a triage with no hint, as every emoji used to.
EMOJI_HINTS = {"🐛": "bug", "✨": "feature", "🔧": "tweak"}

_HINT_NOTES = {
    "bug": (
        "The maintainer marked this as a BUG (🐛). Classify it as kind = 'bug' "
        "unless the report clearly describes something else."
    ),
    "feature": (
        "The maintainer marked this as a FEATURE REQUEST (✨): something new the "
        "apps should do. Classify it as kind = 'feature' unless the report "
        "clearly describes something else."
    ),
    "tweak": (
        "The maintainer marked this as a TWEAK (🔧): a small change to something "
        "that already works — wording, layout, a default, a limit, an extra "
        "option. File it as kind = 'feature' (it is labelled 'tweak' "
        "automatically) unless the report clearly describes something else, and "
        "keep the issue as small as the change."
    ),
}

# Added to a feature filed from a 🔧, so the small ones can be found and batched.
TWEAK_LABEL = "tweak"


def hint_for(emoji: str) -> Optional[str]:
    """The kind a trigger emoji hints at, or None.

    Ignores the variation selector a client may append (✨ arrives as both
    "\u2728" and "\u2728\ufe0f"), so either spelling maps the same way.
    """
    return EMOJI_HINTS.get(emoji.replace("\ufe0f", ""))


def _apply_hint(v: Verdict, hint: Optional[str]) -> Verdict:
    """A tweak the model agreed was a feature gets its label; nothing else.

    The label follows the model's classification, not the emoji: a 🔧 on what
    turned out to be a bug files a bug, and a 'tweak' label on a bug would say
    the opposite of what the issue is.
    """
    if hint == "tweak" and v.kind == "feature" and TWEAK_LABEL not in v.labels:
        v.labels.append(TWEAK_LABEL)
    return v


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
    # A feature is for both apps whichever one it was asked for on, so it is
    # labelled that way — as .github/ISSUE_TEMPLATE/feature.yml does — and never
    # with the reporter's own platform, which would read as a one-app feature.
    if v.kind == "feature":
        v.platform = "multiple"
        v.labels = [l for l in v.labels if not l.startswith("platform:")]

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
    issues: list[dict],
    explicit: bool = False,
    images: Optional[list[tuple[str, str]]] = None,
    has_issue: bool = False,
    ask_platform: bool = True,
    hint: Optional[str] = None,
) -> Verdict:
    hint_note = f"\n\nNOTE: {_HINT_NOTES[hint]}" if hint in _HINT_NOTES else ""
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
        f"Existing issues (for duplicate detection):\n"
        f"{_format_issue_corpus(issues)}"
        f"{explicit_note}"
        f"{hint_note}"
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

    try:
        response = _client.messages.parse(
            model=settings.model,
            # The whole verdict has to fit here — see Settings.max_tokens. A
            # budget too small for it does not truncate the issue body, it
            # truncates the JSON carrying it, and the SDK validates that JSON
            # on the way out: parse() raises instead of returning a verdict,
            # and the report is lost. This used to be a hardcoded 2048.
            max_tokens=settings.max_tokens,
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
    except ValidationError as err:
        # A structured output that does not validate is almost always a reply
        # that ran out of room mid-JSON. Say so, with the number to raise:
        # "invalid JSON" alone sends whoever reads the log looking at the
        # schema instead of at the budget.
        raise TriageError(
            "the model's answer did not parse as a verdict — usually its reply "
            f"was cut off by ANTHROPIC_MAX_TOKENS ({settings.max_tokens}); "
            f"raise it (max {MAX_OUTPUT_TOKENS}) or shorten the transcript"
        ) from err

    verdict = response.parsed_output
    if verdict is None:
        # No text block at all: a refusal, or an answer that was nothing but
        # thinking. Either way there is no verdict, which is a failure to
        # report — not a report to classify as noise.
        raise TriageError(
            "the model returned no verdict "
            f"(stop_reason={response.stop_reason!r}, model={response.model!r})"
        )
    return _apply_hint(_postprocess_platform(verdict, ask_platform), hint)


async def triage(
    author: str,
    content: str,
    issues: list[dict],
    explicit: bool = False,
    images: Optional[list[tuple[str, str]]] = None,
    has_issue: bool = False,
    ask_platform: bool = True,
    hint: Optional[str] = None,
) -> Verdict:
    """Triage a report (single message or full thread transcript) off the event loop.

    `explicit`  = a maintainer directly asked for this (e.g. reacted with the trigger
                  emoji), which biases toward pursuing it.
    `images`    = list of (media_type, base64_data) screenshots to look at.
    `has_issue` = an issue was already filed for this thread, so produce issue_update
                  comments instead of filing again.
    `ask_platform` = False once this thread has already been asked which OS it is on,
                  so the forced OS question isn't repeated at every reply.
    `hint`      = what the trigger emoji says this is ('bug', 'feature', 'tweak'),
                  or None. See EMOJI_HINTS.
    """
    return await asyncio.to_thread(
        _triage_sync,
        author,
        content,
        issues,
        explicit,
        images,
        has_issue,
        ask_platform,
        hint,
    )
