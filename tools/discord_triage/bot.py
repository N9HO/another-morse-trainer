"""Discord triage bot entry point.

When a bug report or feature request comes in, the bot triages it with Claude and
opens a clean GitHub issue. If it needs more detail (repro steps, a screenshot), it
opens a THREAD on the report, asks its question there, and watches that thread —
re-reading the whole conversation (including any screenshots, which it views via
Claude's vision) on every reply until it has enough to file, then files the issue or
adds the new details as a comment.

Trigger modes (TRIGGER_MODE):
  - "react": a maintainer reacts to a message with TRIGGER_EMOJI (default 🐛).
             Follow-ups inside a triage thread are only folded in when the
             trigger emoji is applied again — the bot waits for that prompt
             instead of reacting to every reply.
  - "auto":  every non-bot message in a watched channel is triaged, and every
             follow-up inside a triage thread is read automatically.

Note: the thread -> issue mapping is kept in memory, so a bot restart forgets
in-progress threads (the report can simply be re-triaged with a fresh 🐛).
"""

from __future__ import annotations

import base64
import logging
from dataclasses import dataclass
from typing import Optional

import discord

from config import settings
from github_client import comment_issue, create_issue, list_open_issues
from triage import triage

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("discord-triage")

# Vision input limits.
_ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/gif", "image/webp"}
MAX_IMAGES = 4
MAX_IMAGE_BYTES = 4_000_000
THREAD_HISTORY = 50

intents = discord.Intents.default()
intents.message_content = True  # needed to read message text + attachments
intents.reactions = True
client = discord.Client(intents=intents)


@dataclass
class Pending:
    """State for one in-progress triage thread."""

    issue_number: Optional[int] = None


# thread_id -> Pending
pending: dict[int, Pending] = {}


def _in_scope(channel_id: int) -> bool:
    return not settings.watch_channel_ids or channel_id in settings.watch_channel_ids


async def _safe_open_issues() -> list[dict]:
    try:
        return await list_open_issues()
    except Exception:
        log.exception("Failed to fetch open issues; proceeding without dedup")
        return []


# --- images -------------------------------------------------------------------


async def _download_image(att: discord.Attachment) -> Optional[tuple[str, str]]:
    """Return (media_type, base64) for an image attachment, or None if unusable."""
    content_type = (att.content_type or "").split(";")[0].strip().lower()
    if content_type not in _ALLOWED_IMAGE_TYPES:
        return None
    if att.size and att.size > MAX_IMAGE_BYTES:
        log.info("Skipping oversized image %s (%d bytes)", att.filename, att.size)
        return None
    try:
        data = await att.read()
    except discord.HTTPException:
        log.exception("Failed to download attachment %s", att.filename)
        return None
    return content_type, base64.standard_b64encode(data).decode("ascii")


async def _images_from(messages: list[discord.Message]) -> list[tuple[str, str]]:
    images: list[tuple[str, str]] = []
    for message in messages:
        for att in message.attachments:
            if len(images) >= MAX_IMAGES:
                return images
            part = await _download_image(att)
            if part:
                images.append(part)
    return images


# --- thread gathering ---------------------------------------------------------


async def _gather_thread(thread: discord.Thread) -> tuple[str, str, list[tuple[str, str]]]:
    """Return (author, transcript, images) for the whole triage conversation."""
    starter = thread.starter_message
    if starter is None and thread.parent is not None:
        try:
            starter = await thread.parent.fetch_message(thread.id)
        except discord.HTTPException:
            starter = None

    messages: list[discord.Message] = []
    if starter is not None:
        messages.append(starter)
    try:
        async for m in thread.history(limit=THREAD_HISTORY, oldest_first=True):
            messages.append(m)
    except discord.HTTPException:
        log.exception("Failed to read thread history")

    lines: list[str] = []
    for m in messages:
        who = m.author.display_name + (" [bot]" if m.author.bot else "")
        text = (m.content or "").strip()
        atts = " ".join(f"[image: {a.filename}]" for a in m.attachments)
        body = " ".join(part for part in (text, atts) if part)
        if body:
            lines.append(f"{who}: {body}")

    author = starter.author.display_name if starter else "unknown"
    images = await _images_from(messages)
    return author, "\n".join(lines), images


async def _ensure_thread(message: discord.Message) -> Optional[discord.Thread]:
    if isinstance(message.channel, discord.Thread):
        return message.channel
    name = (f"Triage: {(message.content or '').strip()}" or "Triage")[:90]
    try:
        return await message.create_thread(name=name, auto_archive_duration=1440)
    except discord.HTTPException:
        log.exception(
            "Couldn't create a thread — the bot likely lacks the "
            "'Create Public Threads' / 'Send Messages in Threads' permission."
        )
        return None


# --- verdict application ------------------------------------------------------


async def _say(channel: discord.abc.Messageable, text: str) -> None:
    try:
        await channel.send(text)
    except discord.HTTPException:
        log.exception("Failed to send message in Discord")


async def _apply_verdict(thread: discord.Thread, verdict, key: int) -> None:
    """Act on a verdict inside a triage thread (file, comment, or ask)."""
    p = pending.setdefault(key, Pending())

    # Already filed for this thread -> any new detail is a refinement of THIS
    # issue, never a fresh report. Handle this before the duplicate check: the
    # thread's own issue is in the open-issue list, so the model frequently
    # flags the follow-up as a "duplicate" of itself — which must not abort the
    # update.
    if p.issue_number is not None:
        if verdict.issue_update.strip():
            try:
                await comment_issue(
                    p.issue_number, f"{verdict.issue_update}\n\n_Added via Discord._"
                )
                await _say(
                    thread,
                    f"{verdict.reply or 'Got it'} — updated #{p.issue_number}. ✅",
                )
                return
            except Exception:
                log.exception("Failed to comment on issue")
        await _say(thread, verdict.reply or "👍")
        return

    if verdict.is_duplicate and verdict.duplicate_of:
        await _say(thread, f"Looks like a duplicate of #{verdict.duplicate_of}. 🔁")
        return

    # Enough detail and not yet filed (the filed case returned above) -> open it.
    if verdict.should_file:
        # Stamp the Discord thread id into the issue (hidden HTML comment) so the
        # "issue closed" GitHub Action can post the resolution back to this thread.
        body = f"{verdict.body}\n\n<!-- discord-thread:{thread.id} -->"
        try:
            issue = await create_issue(verdict.title, body, verdict.labels)
        except Exception:
            log.exception("Failed to create issue")
            await _say(thread, "I tried to log that but hit an error filing the issue. 😬")
            return
        p.issue_number = issue["number"]
        await _say(
            thread,
            f"{verdict.reply or 'Logged it'} — opened #{issue['number']}: "
            f"{issue['html_url']} ✅",
        )
        return

    # Not filed yet, not a duplicate -> asking for more info (or declining).
    await _say(thread, verdict.reply or "Thanks — could you add a bit more detail?")


# --- entry flows --------------------------------------------------------------


async def _start_triage(message: discord.Message, explicit: bool) -> None:
    author = message.author.display_name
    content = (message.content or "").strip()
    images = await _images_from([message])

    if not content and not images:
        if explicit:
            await message.reply(
                "I can't read any text or image on that message to triage. 🤔",
                mention_author=False,
            )
        return

    open_issues = await _safe_open_issues()
    verdict = await triage(author, content, open_issues, explicit=explicit, images=images)
    log.info(
        "Start triage msg=%s kind=%s should_file=%s needs_info=%s dup=%s explicit=%s",
        message.id, verdict.kind, verdict.should_file,
        verdict.needs_more_info, verdict.is_duplicate, explicit,
    )

    if verdict.is_duplicate and verdict.duplicate_of:
        await message.reply(
            f"Looks like a duplicate of #{verdict.duplicate_of}. 🔁", mention_author=False
        )
        return

    # Decide whether to engage at all. In auto mode we stay silent on noise.
    engage = verdict.should_file or verdict.needs_more_info or verdict.kind == "question"
    if not engage and not explicit:
        return

    thread = await _ensure_thread(message)
    if thread is None:
        # No thread permission: degrade to one-shot (can't watch follow-ups).
        if verdict.should_file:
            try:
                issue = await create_issue(verdict.title, verdict.body, verdict.labels)
                await message.reply(
                    f"{verdict.reply or 'Logged it'} — opened #{issue['number']}: "
                    f"{issue['html_url']} ✅",
                    mention_author=False,
                )
            except Exception:
                log.exception("Failed to create issue")
                await message.reply("I hit an error filing the issue. 😬", mention_author=False)
        else:
            await message.reply(verdict.reply or "👍", mention_author=False)
        return

    pending[thread.id] = Pending()
    await _apply_verdict(thread, verdict, thread.id)


async def _continue_triage(thread: discord.Thread) -> None:
    p = pending.get(thread.id)
    if p is None:
        return
    author, transcript, images = await _gather_thread(thread)
    # Drop this thread's own issue from the dedup list so a refinement isn't
    # judged a duplicate of the very issue it's refining.
    open_issues = [
        i for i in await _safe_open_issues() if i.get("number") != p.issue_number
    ]
    verdict = await triage(
        author,
        transcript,
        open_issues,
        explicit=True,
        images=images,
        has_issue=p.issue_number is not None,
    )
    log.info(
        "Continue triage thread=%s kind=%s should_file=%s has_issue=%s",
        thread.id, verdict.kind, verdict.should_file, p.issue_number is not None,
    )
    await _apply_verdict(thread, verdict, thread.id)


# --- events -------------------------------------------------------------------


@client.event
async def on_ready() -> None:
    log.info("Logged in as %s (mode=%s, emojis=%s, repo=%s)",
             client.user, settings.trigger_mode,
             " ".join(sorted(settings.trigger_emojis)), settings.github_repo)


@client.event
async def on_message(message: discord.Message) -> None:
    if message.author.bot:
        return
    # A reply inside a triage thread we're tracking. In auto mode we read every
    # follow-up; in react mode we wait for the trigger emoji (handled in
    # on_raw_reaction_add) so the bot doesn't fold in every passing reply.
    if isinstance(message.channel, discord.Thread) and message.channel.id in pending:
        if settings.trigger_mode == "auto":
            await _continue_triage(message.channel)
        return
    # A fresh message in a watched channel — only in auto mode.
    if settings.trigger_mode == "auto" and _in_scope(message.channel.id):
        await _start_triage(message, explicit=False)


@client.event
async def on_raw_reaction_add(payload: discord.RawReactionActionEvent) -> None:
    if settings.trigger_mode != "react":
        return
    if str(payload.emoji) not in settings.trigger_emojis:
        return

    # A trigger reaction inside a thread we're already tracking means "fold this
    # new detail into the existing issue" — a refinement of the report, not a
    # fresh one. (pending is keyed by thread id, which is the reaction's
    # channel_id when the reaction is inside the thread.)
    if payload.channel_id in pending:
        thread = client.get_channel(payload.channel_id)
        if isinstance(thread, discord.Thread):
            await _continue_triage(thread)
        return

    if not _in_scope(payload.channel_id):
        return

    channel = client.get_channel(payload.channel_id)
    if channel is None:
        return
    try:
        message = await channel.fetch_message(payload.message_id)
    except discord.HTTPException:
        log.exception("Failed to fetch reacted message")
        return
    if message.author.bot:
        return
    await _start_triage(message, explicit=True)


def main() -> None:
    client.run(settings.discord_token, log_handler=None)


if __name__ == "__main__":
    main()
