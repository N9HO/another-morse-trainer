"""Discord triage bot entry point.

Watches the project's Discord and, when a bug report or feature request comes in,
asks Claude to triage it and (if actionable) opens a clean GitHub issue, then
replies in the thread with the result.

Two trigger modes (TRIGGER_MODE):
  - "react": a maintainer reacts to a message with TRIGGER_EMOJI (default 🐛).
             Lowest noise / cheapest — recommended to start.
  - "auto":  every non-bot message in a watched channel is triaged.
"""

from __future__ import annotations

import logging

import discord

from config import settings
from github_client import create_issue, list_open_issues
from triage import triage

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("discord-triage")

intents = discord.Intents.default()
intents.message_content = True  # needed to read message text
intents.reactions = True
client = discord.Client(intents=intents)


def _in_scope(channel_id: int) -> bool:
    """True if we should act in this channel."""
    return not settings.watch_channel_ids or channel_id in settings.watch_channel_ids


async def _process(message: discord.Message, explicit: bool = False) -> None:
    """Triage one message and, if warranted, file an issue + reply.

    `explicit` = a maintainer reacted with the trigger emoji. In that case we bias
    toward filing and ALWAYS reply (silence on an explicit request is confusing).
    """
    author = message.author.display_name
    content = (message.content or "").strip()
    if not content:
        if explicit:
            await _reply(message, "I can't read any text on that message to triage. 🤔")
        return

    try:
        open_issues = await list_open_issues()
    except Exception:
        log.exception("Failed to fetch open issues; proceeding without dedup")
        open_issues = []

    verdict = await triage(author, content, open_issues, explicit=explicit)
    log.info("Triaged message %s: kind=%s should_file=%s dup=%s (explicit=%s)",
             message.id, verdict.kind, verdict.should_file, verdict.is_duplicate, explicit)

    if verdict.is_duplicate and verdict.duplicate_of:
        await _reply(message, f"Looks like a duplicate of #{verdict.duplicate_of}. 🔁")
        return

    if not verdict.should_file:
        # On an explicit request, always explain why we're not filing.
        # In auto mode, only nudge for borderline questions; stay silent on noise.
        if explicit:
            await _reply(message, verdict.reply or "I don't think this needs an issue. 👍")
        elif verdict.kind == "question" and verdict.reply:
            await _reply(message, verdict.reply)
        return

    try:
        issue = await create_issue(verdict.title, verdict.body, verdict.labels)
    except Exception:
        log.exception("Failed to create issue")
        await _reply(message, "I tried to log that but hit an error filing the issue. 😬")
        return

    note = verdict.reply or "Logged it"
    await _reply(message, f"{note} — opened #{issue['number']}: {issue['html_url']} ✅")


async def _reply(message: discord.Message, text: str) -> None:
    try:
        await message.reply(text, mention_author=False)
    except discord.HTTPException:
        log.exception("Failed to reply in Discord")


@client.event
async def on_ready() -> None:
    log.info("Logged in as %s (mode=%s, repo=%s)",
             client.user, settings.trigger_mode, settings.github_repo)


@client.event
async def on_message(message: discord.Message) -> None:
    if settings.trigger_mode != "auto":
        return
    if message.author.bot or not _in_scope(message.channel.id):
        return
    await _process(message)


@client.event
async def on_raw_reaction_add(payload: discord.RawReactionActionEvent) -> None:
    if settings.trigger_mode != "react":
        return
    if str(payload.emoji) != settings.trigger_emoji:
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
    await _process(message, explicit=True)


def main() -> None:
    client.run(settings.discord_token, log_handler=None)


if __name__ == "__main__":
    main()
