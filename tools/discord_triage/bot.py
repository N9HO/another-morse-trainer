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
from github_client import (
    GitHubError,
    check_repo_access,
    comment_issue,
    create_issue,
    list_open_issues,
)
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
    # Which repo the issue was filed in (platform-routed), so follow-up comments
    # land in the right place. None until an issue is filed for this thread.
    issue_repo: Optional[str] = None


# thread_id -> Pending
pending: dict[int, Pending] = {}


def _in_scope(channel_id: int, parent_id: Optional[int] = None) -> bool:
    if not settings.watch_channel_ids:
        return True
    if channel_id in settings.watch_channel_ids:
        return True
    # Threads (and forum posts) carry their own channel ids; WATCH_CHANNEL_IDS
    # names the parent channel, so a thread inherits its parent's scope.
    return parent_id is not None and parent_id in settings.watch_channel_ids


async def _safe_open_issues() -> list[dict]:
    # Dedup runs before the platform is known, so we check the default repo only.
    # Android issues live in a separate repo (settings.github_repo_android) and are
    # not yet cross-checked here — acceptable while that repo is small; revisit if
    # Android duplicate reports become common.
    try:
        return await list_open_issues()
    except Exception:
        log.exception("Failed to fetch open issues; proceeding without dedup")
        return []


# --- images -------------------------------------------------------------------


def _sniff_image_type(data: bytes) -> Optional[str]:
    """The image's real media type, read from its magic bytes.

    Discord's attachment content_type follows the upload's file extension and
    can lie (a PNG saved as .jpg is reported as image/jpeg); the vision API
    checks the actual bytes and rejects the mismatch, so the bytes decide.
    """
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


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
    media_type = _sniff_image_type(data)
    if media_type is None:
        log.info(
            "Skipping %s — declared %s but content is not a supported image",
            att.filename, content_type,
        )
        return None
    return media_type, base64.standard_b64encode(data).decode("ascii")


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
    if starter is None:
        try:
            if isinstance(thread.parent, discord.TextChannel):
                # A thread hanging off a channel message: the starter message
                # lives in the parent channel and shares the thread's id.
                starter = await thread.parent.fetch_message(thread.id)
            else:
                # A forum/media post: the parent channel holds no messages —
                # the starter message lives inside the thread itself.
                starter = await thread.fetch_message(thread.id)
        except discord.HTTPException:
            starter = None

    messages: list[discord.Message] = []
    if starter is not None:
        messages.append(starter)
    try:
        async for m in thread.history(limit=THREAD_HISTORY, oldest_first=True):
            # In forum posts the starter is part of the thread history — skip
            # it so it doesn't appear in the transcript twice.
            if starter is not None and m.id == starter.id:
                continue
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


async def _message_thread(
    message: discord.Message, *, fetch: bool = False
) -> Optional[discord.Thread]:
    """The thread this message lives in or already carries, if any.

    A reaction on a thread's STARTER message arrives on the parent channel (the
    starter belongs to it), yet the conversation the reporter is watching is the
    thread — which shares the message's id. Replies must go there: a
    channel-level reply is invisible in the thread view.

    Only active threads are cached; fetch=True also finds an archived one via
    the API. Either way an archived thread is woken before use — Discord
    rejects sends into archived threads, which would make the bot look deaf.
    """
    thread: Optional[discord.Thread] = None
    if isinstance(message.channel, discord.Thread):
        thread = message.channel
    elif message.guild is not None:
        thread = message.guild.get_thread(message.id)
        if thread is None and fetch:
            try:
                channel = await message.guild.fetch_channel(message.id)
            except discord.HTTPException:
                channel = None
            if isinstance(channel, discord.Thread):
                thread = channel
    if thread is not None and thread.archived:
        # Un-archiving a public thread needs only Send Messages.
        try:
            thread = await thread.edit(archived=False)
        except discord.HTTPException:
            log.exception("Couldn't unarchive thread %s", thread.id)
    return thread


async def _ensure_thread(message: discord.Message) -> Optional[discord.Thread]:
    existing = await _message_thread(message)
    if existing is not None:
        return existing
    name = (f"Triage: {(message.content or '').strip()}" or "Triage")[:90]
    try:
        return await message.create_thread(name=name, auto_archive_duration=1440)
    except discord.HTTPException as err:
        # 160004: the message already has a thread — it just wasn't in the
        # cache (archived, or created before the bot's last restart). Adopt it.
        if err.code == 160004:
            return await _message_thread(message, fetch=True)
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


async def _file_issue(verdict, body: str) -> tuple[Optional[dict], Optional[str], str]:
    """Create the issue in the platform-routed repo, with a safety net.

    If the routed repo rejects the token (403/404 — the PAT doesn't cover it),
    fall back to the default repo so the report is never dropped; the platform
    label still marks where it belongs.

    Returns (issue, repo, note): the created issue and the repo it landed in
    (None, None when every attempt failed), plus a short note for the Discord
    reply explaining any fallback or failure ("" when there's nothing to say).
    """
    repo = settings.repo_for(verdict.platform)
    try:
        issue = await create_issue(verdict.title, body, verdict.labels, repo=repo)
        return issue, repo, ""
    except GitHubError as err:
        log.error("Failed to create issue in %s: %s", repo, err)
        if repo != settings.github_repo and err.status in (403, 404):
            log.warning(
                "Falling back to %s — grant the bot's GITHUB_TOKEN 'Issues: Read "
                "and write' on %s to file this platform's reports there.",
                settings.github_repo, repo,
            )
            try:
                issue = await create_issue(
                    verdict.title, body, verdict.labels, repo=settings.github_repo
                )
                note = (
                    f" ⚠️ Filed in {settings.github_repo} because the bot's token "
                    f"can't reach {repo} (GitHub {err.status})."
                )
                return issue, settings.github_repo, note
            except Exception:
                log.exception("Fallback create_issue in %s also failed", settings.github_repo)
        return None, None, f" (GitHub {err.status} on {repo}: {err.detail})"
    except Exception:
        log.exception("Failed to create issue in %s", repo)
        return None, None, ""


def _should_file_now(verdict) -> bool:
    """Does this verdict become a GitHub issue right now?

    A genuine bug or feature does, even when it is still missing detail. The
    old rule filed only on `should_file`, which the triage prompt clears while
    it waits on the reporter — so a report whose reporter never came back left
    no trace outside Discord, and the maintainer never learned of it. Questions,
    noise and duplicates are still never filed.
    """
    if verdict.kind not in ("bug", "feature"):
        return False
    if verdict.is_duplicate:
        return False
    return verdict.should_file or verdict.needs_more_info


def _filed_reply(verdict, issue: dict, note: str) -> str:
    """What to say in Discord once the issue exists.

    A thin report keeps the question as the headline — the reporter still needs
    to answer it — with the issue mentioned as reassurance rather than as a
    sign-off, so it doesn't read like the conversation is over.
    """
    if verdict.needs_more_info:
        return (
            f"{verdict.reply or 'Could you add a bit more detail?'}\n"
            f"Logged it as #{issue['number']} either way so it isn't lost: "
            f"{issue['html_url']} 📝{note}"
        )
    return (
        f"{verdict.reply or 'Logged it'} — opened #{issue['number']}: "
        f"{issue['html_url']} ✅{note}"
    )


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
                    p.issue_number,
                    f"{verdict.issue_update}\n\n_Added via Discord._",
                    repo=p.issue_repo,
                )
                await _say(
                    thread,
                    f"{verdict.reply or 'Got it'} — updated #{p.issue_number}. ✅",
                )
                return
            except Exception:
                log.exception(
                    "Failed to comment on issue #%s in %s",
                    p.issue_number, p.issue_repo or settings.github_repo,
                )
                await _say(
                    thread,
                    f"I couldn't add that to #{p.issue_number} — hit a GitHub "
                    f"error (details in the bot logs). 😬",
                )
                return
        await _say(thread, verdict.reply or "👍")
        return

    if verdict.is_duplicate and verdict.duplicate_of:
        await _say(thread, f"Looks like a duplicate of #{verdict.duplicate_of}. 🔁")
        return

    # A real report is filed even while it is still thin. Holding it back until
    # the reporter answered left the only record in Discord, so a reporter who
    # went quiet meant the maintainer never learned the report existed. It goes
    # in labelled 'needs-info', the question still gets asked here, and the
    # answer lands on the issue through the has_issue path above.
    if _should_file_now(verdict):
        # Stamp the Discord thread id into the issue (hidden HTML comment) so the
        # "issue closed" GitHub Action can post the resolution back to this thread.
        body = f"{verdict.body}\n\n<!-- discord-thread:{thread.id} -->"
        issue, repo, note = await _file_issue(verdict, body)
        if issue is None:
            await _say(
                thread,
                f"I tried to log that but hit an error filing the issue.{note} 😬",
            )
            return
        p.issue_number = issue["number"]
        p.issue_repo = repo
        await _say(thread, _filed_reply(verdict, issue, note))
        return

    # Question, noise, or a duplicate we couldn't name -> just reply.
    await _say(thread, verdict.reply or "Thanks — could you add a bit more detail?")


# --- entry flows --------------------------------------------------------------


async def _start_triage(message: discord.Message, explicit: bool) -> None:
    # Where the conversation already lives, when the reporter opened a thread on
    # their own message. Every reply below must land there, not in the channel.
    # An explicit trigger is worth an API lookup so an archived thread is found
    # (and woken) too; auto mode stays cache-only to avoid a call per message.
    home = await _message_thread(message, fetch=explicit)
    if home is not None and home.id in pending:
        # Re-triggering the starter of a tracked thread folds the new detail
        # into the existing triage, same as re-reacting inside the thread.
        await _continue_triage(home)
        return

    author = message.author.display_name
    content = (message.content or "").strip()
    images = await _images_from([message])

    if not content and not images:
        if explicit:
            note = "I can't read any text or image on that message to triage. 🤔"
            if home is not None:
                await _say(home, note)
            else:
                await message.reply(note, mention_author=False)
        return

    open_issues = await _safe_open_issues()
    try:
        verdict = await triage(author, content, open_issues, explicit=explicit, images=images)
    except Exception:
        # Never leave a 👀 hanging: an analysis failure gets said out loud.
        log.exception("Triage failed for msg=%s", message.id)
        if explicit:
            note = "I hit an error analyzing that report — details are in the bot logs. 😬"
            if home is not None:
                await _say(home, note)
            else:
                await message.reply(note, mention_author=False)
        return
    log.info(
        "Start triage msg=%s kind=%s should_file=%s needs_info=%s dup=%s explicit=%s",
        message.id, verdict.kind, verdict.should_file,
        verdict.needs_more_info, verdict.is_duplicate, explicit,
    )

    if verdict.is_duplicate and verdict.duplicate_of:
        dup = f"Looks like a duplicate of #{verdict.duplicate_of}. 🔁"
        if home is not None:
            await _say(home, dup)
        else:
            await message.reply(dup, mention_author=False)
        return

    # Decide whether to engage at all. In auto mode we stay silent on noise.
    engage = verdict.should_file or verdict.needs_more_info or verdict.kind == "question"
    if not engage and not explicit:
        return

    thread = home or await _ensure_thread(message)
    if thread is None:
        # No thread permission: degrade to one-shot (can't watch follow-ups).
        if _should_file_now(verdict):
            issue, _, note = await _file_issue(verdict, verdict.body)
            if issue is None:
                await message.reply(
                    f"I hit an error filing the issue.{note} 😬", mention_author=False
                )
            else:
                await message.reply(
                    _filed_reply(verdict, issue, note), mention_author=False
                )
        else:
            await message.reply(verdict.reply or "👍", mention_author=False)
        return

    pending.setdefault(thread.id, Pending())
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
    try:
        verdict = await triage(
            author,
            transcript,
            open_issues,
            explicit=True,
            images=images,
            has_issue=p.issue_number is not None,
        )
    except Exception:
        log.exception("Triage failed for thread=%s", thread.id)
        await _say(thread, "I hit an error analyzing that — details are in the bot logs. 😬")
        return
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
    # Probe GitHub access up front so a bad token is one obvious log line at
    # startup instead of a mystery when the first report tries to file.
    repos = [settings.github_repo]
    if settings.github_repo_android:
        repos.append(settings.github_repo_android)
    for repo in repos:
        problem = await check_repo_access(repo)
        if problem:
            log.error(
                "GITHUB_TOKEN cannot access %s (%s) — filing issues there WILL "
                "fail. 401 = the PAT expired or was revoked; 404 = the PAT "
                "doesn't cover this repo; 403 = missing the 'Issues: Read and "
                "write' permission. Fix the token and `fly secrets set "
                "GITHUB_TOKEN=...`.", repo, problem,
            )
        else:
            log.info("GitHub access OK: %s", repo)


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
    parent_id = (
        message.channel.parent_id if isinstance(message.channel, discord.Thread) else None
    )
    if settings.trigger_mode == "auto" and _in_scope(message.channel.id, parent_id):
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

    channel = client.get_channel(payload.channel_id)
    if channel is None:
        # Not cached — e.g. a reaction inside an archived thread.
        try:
            channel = await client.fetch_channel(payload.channel_id)
        except discord.HTTPException:
            log.exception("Failed to fetch reacted channel %s", payload.channel_id)
            return
    parent_id = channel.parent_id if isinstance(channel, discord.Thread) else None
    if not _in_scope(payload.channel_id, parent_id):
        return
    try:
        message = await channel.fetch_message(payload.message_id)
    except discord.HTTPException:
        log.exception("Failed to fetch reacted message")
        return
    if message.author.bot:
        return
    # Acknowledge the trigger immediately: the 👀 says "seen, triaging". If
    # this never appears, the event didn't reach the bot at all — which
    # separates delivery problems from triage problems at a glance.
    try:
        await message.add_reaction("👀")
    except discord.HTTPException:
        pass
    await _start_triage(message, explicit=True)


def main() -> None:
    client.run(settings.discord_token, log_handler=None)


if __name__ == "__main__":
    main()
