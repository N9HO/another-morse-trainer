"""Discord triage bot entry point.

When a bug report or feature request comes in, the bot triages it with Claude and
opens a clean GitHub issue. If it needs more detail (repro steps, a screenshot), it
opens a THREAD on the report, asks its question there, and watches that thread —
re-reading the whole conversation (including any screenshots, which it views via
Claude's vision) on every reply until it has enough to file, then files the issue or
adds the new details as a comment.

Everything inside a thread is triaged as ONE report — its title included, which in
a forum channel is where the reporter says which screen they're on. A reporter
rarely says it all in one message: they add a second thought, then a screenshot,
then answer the question the bot asked. So any trigger inside a thread re-reads the
whole thread rather than the message that triggered it, and triggers that land
within TRIAGE_SETTLE_SECONDS of each other — a burst of replies, or a maintainer
reacting to both the report and the screenshot under it — are coalesced into a
single pass instead of one triage (and one answer) each.

Trigger modes (TRIGGER_MODE):
  - "react": a maintainer reacts to a message with TRIGGER_EMOJI (default 🐛).
             Follow-ups inside a triage thread are only folded in when the
             trigger emoji is applied again — the bot waits for that prompt
             instead of reacting to every reply.
  - "auto":  every non-bot message in a watched channel is triaged, and every
             follow-up inside a watched thread is read automatically.

The thread -> issue mapping is kept in memory, but a restart is no longer amnesia:
the bot announces every issue it files with its full URL, and stamps the issue body
with the thread id, so a forgotten thread's issue is recovered from its own
transcript (or, failing that, from GitHub) the next time the thread is triaged.
"""

from __future__ import annotations

import asyncio
import base64
import itertools
import logging
from dataclasses import dataclass, field
from typing import Optional

import discord

from config import settings
from conversation import (
    AUTO_THREAD_PREFIX,
    Turn,
    asked_about_platform,
    bot_already_said,
    find_issue_anchor,
    render_transcript,
    render_turn,
    thread_title,
)
from github_client import (
    GitHubError,
    check_repo_access,
    comment_issue,
    create_issue,
    find_issue_for_thread,
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
    # Id of the newest thread message whose content already reached the issue
    # (in the filed body, or in a follow-up comment). Everything after it is
    # what the issue doesn't know yet, which is what the next update should say.
    recorded_through: Optional[int] = None
    # Whether we've already paid for the GitHub lookup that recovers a
    # forgotten thread's issue. Once per thread is enough.
    searched_github: bool = False


@dataclass
class Conversation:
    """A whole triage thread, ready to hand to the model."""

    author: str
    turns: list[Turn] = field(default_factory=list)
    images: list[tuple[str, str]] = field(default_factory=list)
    last_message_id: Optional[int] = None
    # The thread's own name, when it carries information the messages don't —
    # in a forum channel that's the reporter's headline.
    title: Optional[str] = None

    @property
    def has_content(self) -> bool:
        """Is there anything here a human wrote that we can actually read?"""
        if self.images or self.title:
            return True
        return any(render_turn(turn) for turn in self.turns if not turn.is_bot)


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


def _issue_repos() -> list[str]:
    repos = [settings.github_repo]
    if settings.github_repo_android:
        repos.append(settings.github_repo_android)
    return repos


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
    """Up to MAX_IMAGES screenshots from these messages, in posting order.

    Gathered newest-first: in a long conversation the screenshot that matters
    is the one just posted, so images from the opening messages must not crowd
    out the one the reporter is talking about right now.
    """
    images: list[tuple[str, str]] = []
    for message in reversed(messages):
        for att in reversed(message.attachments):
            if len(images) >= MAX_IMAGES:
                return list(reversed(images))
            part = await _download_image(att)
            if part:
                images.append(part)
    return list(reversed(images))


# --- thread gathering ---------------------------------------------------------


def _turn_from(message: discord.Message) -> Turn:
    return Turn(
        message_id=message.id,
        author=message.author.display_name,
        text=(message.content or "").strip(),
        attachments=[a.filename for a in message.attachments],
        is_bot=message.author.bot,
        is_self=client.user is not None and message.author.id == client.user.id,
    )


async def _gather_thread(thread: discord.Thread) -> Conversation:
    """Read the whole triage conversation: every message, in order, with images."""
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

    turns = [_turn_from(m) for m in messages]
    author = starter.author.display_name if starter else "unknown"
    return Conversation(
        author=author,
        turns=turns,
        images=await _images_from(messages),
        last_message_id=messages[-1].id if messages else None,
        title=thread_title(thread.name),
    )


async def _wake(thread: discord.Thread) -> discord.Thread:
    """Un-archive a thread before using it — Discord rejects sends into archived
    threads, which would make the bot look deaf. Needs only Send Messages."""
    if not thread.archived:
        return thread
    try:
        return await thread.edit(archived=False)
    except discord.HTTPException:
        log.exception("Couldn't unarchive thread %s", thread.id)
        return thread


async def _message_thread(
    message: discord.Message, *, fetch: bool = False
) -> Optional[discord.Thread]:
    """The thread this message lives in or already carries, if any.

    A reaction on a thread's STARTER message arrives on the parent channel (the
    starter belongs to it), yet the conversation the reporter is watching is the
    thread — which shares the message's id. Replies must go there: a
    channel-level reply is invisible in the thread view.

    Only active threads are cached; fetch=True also finds an archived one via
    the API. Either way an archived thread is woken before use.
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
    if thread is not None:
        thread = await _wake(thread)
    return thread


async def _ensure_thread(message: discord.Message) -> Optional[discord.Thread]:
    existing = await _message_thread(message)
    if existing is not None:
        return existing
    name = (f"{AUTO_THREAD_PREFIX}{(message.content or '').strip()}" or "Triage")[:90]
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


# --- thread memory ------------------------------------------------------------


async def _thread_state(thread: discord.Thread, convo: Conversation) -> Pending:
    """This thread's triage state, recovered if we've forgotten it.

    `pending` lives in memory, so a restart (or a thread the reporter opened
    themselves, which the bot never registered) leaves a conversation whose
    issue we no longer know about — and an unknown issue means the bot files a
    second one and starts asking its opening questions all over again.

    The conversation carries the answer: the bot announces every issue it files
    with the issue's full URL, so its own replies say which issue this thread
    belongs to, and where the record last caught up with the conversation. If
    that message is gone (deleted, or older than THREAD_HISTORY), the hidden
    `discord-thread:<id>` stamp in the issue body is the backstop.
    """
    p = pending.get(thread.id)
    if p is None:
        p = pending[thread.id] = Pending()
    if p.issue_number is not None:
        return p

    anchor = find_issue_anchor(convo.turns)
    if anchor is not None:
        p.issue_number = anchor.number
        p.issue_repo = anchor.repo
        p.recorded_through = anchor.recorded_through
        log.info(
            "Recovered issue #%s (%s) for thread %s from the conversation",
            p.issue_number, p.issue_repo, thread.id,
        )
        return p

    # Only worth asking GitHub if we ever spoke here: a thread the bot has
    # never replied in has no issue of ours to find.
    if p.searched_github or not any(turn.is_self for turn in convo.turns):
        return p
    p.searched_github = True
    found = await find_issue_for_thread(thread.id, _issue_repos())
    if found:
        p.issue_number = found["number"]
        p.issue_repo = found["repo"]
        log.info(
            "Recovered issue #%s (%s) for thread %s from its GitHub stamp",
            p.issue_number, p.issue_repo, thread.id,
        )
    return p


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


def _engages(verdict) -> bool:
    """Is this worth saying anything about, unprompted?

    In auto mode the bot sees every message in the channels and threads it
    watches; chatter it can't act on gets silence, not a reply. A duplicate
    counts: pointing at the existing issue is the useful answer.
    """
    if verdict.is_duplicate and verdict.duplicate_of:
        return True
    return verdict.should_file or verdict.needs_more_info or verdict.kind == "question"


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


async def _apply_verdict(
    thread: discord.Thread,
    verdict,
    p: Pending,
    convo: Conversation,
    *,
    explicit: bool,
) -> None:
    """Act on a verdict inside a triage thread (file, comment, or ask)."""
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
            # The conversation up to here is now on the issue, so the next
            # update only has to carry what comes after it.
            p.recorded_through = convo.last_message_id
            await _say(
                thread,
                f"{verdict.reply or 'Got it'} — updated #{p.issue_number}. ✅",
            )
            return
        # Nothing new to record. Answer when we're addressed directly or when
        # there's a genuine question; otherwise stay quiet rather than chiming
        # in on every "thanks!" in a thread we're watching.
        if explicit or verdict.kind == "question":
            await _say(thread, verdict.reply or "👍")
        return

    if verdict.is_duplicate and verdict.duplicate_of:
        # Say it once. Every message re-triages the whole thread, so the same
        # verdict comes back on every follow-up — and a bot that keeps
        # repeating its last answer is the thing we're fixing.
        pointer = f"duplicate of #{verdict.duplicate_of}"
        if not bot_already_said(convo.turns, pointer):
            await _say(thread, f"Looks like a {pointer}. 🔁")
        return

    # A real report is filed even while it is still thin. Holding it back until
    # the reporter answered left the only record in Discord, so a reporter who
    # went quiet meant the maintainer never learned the report existed. It goes
    # in labelled 'needs-info', the question still gets asked here, and the
    # answer lands on the issue through the has_issue path above.
    if _should_file_now(verdict):
        # Stamp the Discord thread id into the issue (hidden HTML comment) so the
        # "issue closed" GitHub Action can post the resolution back to this thread —
        # and so the bot can find this issue again if it forgets the thread.
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
        p.recorded_through = convo.last_message_id
        await _say(thread, _filed_reply(verdict, issue, note))
        return

    # Question, noise, or a duplicate we couldn't name -> just reply.
    await _say(thread, verdict.reply or "Thanks — could you add a bit more detail?")


# --- thread triage ------------------------------------------------------------

# A reporter's "several thoughts" arrive as several messages seconds apart. Each
# one used to start its own triage over its own snapshot of the thread, which
# raced (two passes could both file, or answer a question the next message was
# already answering). Now every request for a thread is stamped with a token:
# the newest token wins, and the winner reads the settled conversation once.
_triage_locks: dict[int, asyncio.Lock] = {}
_latest_request: dict[int, int] = {}
_request_tokens = itertools.count(1)


def _lock_for(thread_id: int) -> asyncio.Lock:
    lock = _triage_locks.get(thread_id)
    if lock is None:
        lock = _triage_locks[thread_id] = asyncio.Lock()
    return lock


async def _triage_thread(thread: discord.Thread, *, explicit: bool) -> None:
    """Triage a whole thread, coalescing a burst of triggers into one pass."""
    token = next(_request_tokens)
    _latest_request[thread.id] = token

    # Wait for the thread to settle, whoever triggered it. A reporter is still
    # typing their next thought; a maintainer reacting 🐛 to the report AND to
    # the screenshot under it means "triage this thread", not "answer me twice"
    # — and two answers to one conversation is the behavior being fixed. The 👀
    # already went on, so nobody is left wondering whether it landed.
    if settings.settle_seconds > 0:
        await asyncio.sleep(settings.settle_seconds)
        if _latest_request.get(thread.id) != token:
            return  # superseded — the newer trigger reads this message too

    async with _lock_for(thread.id):
        if _latest_request.get(thread.id) != token:
            return
        await _run_triage(thread, explicit=explicit)


async def _run_triage(thread: discord.Thread, *, explicit: bool) -> None:
    """Read the whole conversation and act on it."""
    convo = await _gather_thread(thread)
    if not convo.has_content:
        if explicit:
            await _say(thread, "I can't read any text or image here to triage. 🤔")
        return

    p = await _thread_state(thread, convo)
    transcript = render_transcript(
        convo.turns, recorded_through=p.recorded_through, title=convo.title
    )
    # Drop this thread's own issue from the dedup list so a refinement isn't
    # judged a duplicate of the very issue it's refining.
    open_issues = [
        i for i in await _safe_open_issues() if i.get("number") != p.issue_number
    ]
    try:
        verdict = await triage(
            convo.author,
            transcript,
            open_issues,
            explicit=explicit,
            images=convo.images,
            has_issue=p.issue_number is not None,
            # Ask which OS at most once per thread; after that, re-asking is
            # exactly the "weren't you listening?" behavior we're avoiding.
            ask_platform=not asked_about_platform(convo.turns),
        )
    except Exception:
        log.exception("Triage failed for thread=%s", thread.id)
        if explicit:
            await _say(
                thread, "I hit an error analyzing that — details are in the bot logs. 😬"
            )
        return
    log.info(
        "Triage thread=%s msgs=%d kind=%s should_file=%s needs_info=%s has_issue=%s explicit=%s",
        thread.id, len(convo.turns), verdict.kind, verdict.should_file,
        verdict.needs_more_info, p.issue_number is not None, explicit,
    )

    # In auto mode, a thread we're watching is still a conversation between
    # humans: only speak up when there's something to do.
    if not explicit and p.issue_number is None and not _engages(verdict):
        return
    await _apply_verdict(thread, verdict, p, convo, explicit=explicit)


# --- entry flows --------------------------------------------------------------


async def _start_triage(message: discord.Message, explicit: bool) -> None:
    """Triage a channel message — or the thread it already belongs to."""
    # Where the conversation already lives: the thread the message sits in, or
    # one the reporter opened on their own message. Whenever there is one, the
    # report is the whole thread, not this message — so every earlier thought,
    # screenshot and answer counts, and replies land where the reporter is
    # looking. An explicit trigger is worth an API lookup so an archived thread
    # is found (and woken) too; auto mode stays cache-only to avoid a call per
    # message.
    home = await _message_thread(message, fetch=explicit)
    if home is not None:
        await _triage_thread(home, explicit=explicit)
        return

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
    try:
        verdict = await triage(author, content, open_issues, explicit=explicit, images=images)
    except Exception:
        # Never leave a 👀 hanging: an analysis failure gets said out loud.
        log.exception("Triage failed for msg=%s", message.id)
        if explicit:
            await message.reply(
                "I hit an error analyzing that report — details are in the bot logs. 😬",
                mention_author=False,
            )
        return
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
    if not _engages(verdict) and not explicit:
        return

    thread = await _ensure_thread(message)
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

    convo = Conversation(
        author=author, turns=[_turn_from(message)], images=images, last_message_id=message.id
    )
    p = pending.setdefault(thread.id, Pending())
    await _apply_verdict(thread, verdict, p, convo, explicit=explicit)


# --- events -------------------------------------------------------------------


@client.event
async def on_ready() -> None:
    log.info("Logged in as %s (mode=%s, emojis=%s, repo=%s, settle=%ss)",
             client.user, settings.trigger_mode,
             " ".join(sorted(settings.trigger_emojis)), settings.github_repo,
             settings.settle_seconds)
    # Probe GitHub access up front so a bad token is one obvious log line at
    # startup instead of a mystery when the first report tries to file.
    for repo in _issue_repos():
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
    # A reply inside a thread. In auto mode we read every follow-up — always
    # re-reading the whole thread, so a report spread over several messages is
    # answered once, as one report. In react mode we wait for the trigger emoji
    # (handled in on_raw_reaction_add) so the bot doesn't fold in every passing
    # reply.
    if isinstance(message.channel, discord.Thread):
        if settings.trigger_mode == "auto" and _in_scope(
            message.channel.id, message.channel.parent_id
        ):
            await _triage_thread(message.channel, explicit=False)
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

    # Acknowledge the trigger immediately: the 👀 says "seen, triaging". If
    # this never appears, the event didn't reach the bot at all — which
    # separates delivery problems from triage problems at a glance.
    async def ack() -> None:
        try:
            await message.add_reaction("👀")
        except discord.HTTPException:
            pass

    # A trigger reaction inside a thread means "take everything here into
    # account" — the whole conversation, not the one message reacted to. That
    # holds whether or not we already know this thread: a thread we've
    # forgotten (a restart, or one the reporter opened themselves) recovers its
    # issue from the conversation instead of starting the report over.
    if isinstance(channel, discord.Thread):
        await ack()
        await _triage_thread(await _wake(channel), explicit=True)
        return

    if message.author.bot:
        return
    await ack()
    await _start_triage(message, explicit=True)


def main() -> None:
    client.run(settings.discord_token, log_handler=None)


if __name__ == "__main__":
    main()
