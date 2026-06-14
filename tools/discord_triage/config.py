"""Configuration loaded from environment variables.

All secrets come from the environment — nothing is hardcoded or committed.
See .env.example for the full list and the README for how to set them on Fly.io.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Optional


def _required(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(
            f"Missing required environment variable: {name}. "
            f"Copy .env.example to .env and fill it in (or set Fly.io secrets)."
        )
    return value


def _csv_ints(name: str) -> set[int]:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return set()
    return {int(part) for part in raw.split(",") if part.strip()}


def _emoji_set(raw: str) -> frozenset[str]:
    # Accept several emojis separated by commas and/or whitespace; any of them
    # triggers a triage. Emojis contain neither commas nor spaces, so splitting
    # on both is safe. Falls back to 🐛 if nothing valid is given.
    parts = [p for p in raw.replace(",", " ").split() if p]
    return frozenset(parts) or frozenset({"🐛"})


@dataclass(frozen=True)
class Settings:
    # --- Discord ---
    discord_token: str
    # Channel IDs the bot listens in. Empty = every channel it can see.
    watch_channel_ids: set[int]
    # "react": only triage a message when someone reacts with one of the trigger
    #          emojis (the bot ignores everything else).
    # "auto":  triage every (non-bot) message posted in a watched channel.
    trigger_mode: str
    # One or more emojis; reacting with any of them triggers triage.
    trigger_emojis: frozenset[str]

    # --- Anthropic ---
    anthropic_api_key: str
    # Defaults to the most capable model. For lower cost on this high-volume,
    # low-complexity task, set ANTHROPIC_MODEL=claude-haiku-4-5 (cheapest) or
    # claude-sonnet-4-6 (mid). Your call — see the README cost note.
    model: str

    # --- GitHub ---
    github_token: str
    github_repo: str  # "owner/name" — default repo for all platforms…
    # …except Android, which is routed here when set. Empty string = no separate
    # Android repo, so Android bugs fall back to github_repo (the old behavior).
    # The GITHUB_TOKEN must have Issues: read/write on this repo too.
    github_repo_android: str
    # Apply this label to every issue the bot opens, so they're easy to find/filter.
    triage_label: str

    def repo_for(self, platform: Optional[str]) -> str:
        """Pick the destination repo for a verdict's platform.

        Android reports go to the dedicated Android repo when one is configured;
        everything else (iOS/iPadOS/macOS/multiple/unknown) goes to the default.
        """
        if platform == "android" and self.github_repo_android:
            return self.github_repo_android
        return self.github_repo

    @staticmethod
    def load() -> "Settings":
        trigger_mode = os.environ.get("TRIGGER_MODE", "react").strip().lower()
        if trigger_mode not in {"react", "auto"}:
            raise RuntimeError("TRIGGER_MODE must be 'react' or 'auto'")

        return Settings(
            discord_token=_required("DISCORD_BOT_TOKEN"),
            watch_channel_ids=_csv_ints("WATCH_CHANNEL_IDS"),
            trigger_mode=trigger_mode,
            trigger_emojis=_emoji_set(os.environ.get("TRIGGER_EMOJI", "🐛")),
            anthropic_api_key=_required("ANTHROPIC_API_KEY"),
            model=os.environ.get("ANTHROPIC_MODEL", "claude-opus-4-8"),
            github_token=_required("GITHUB_TOKEN"),
            github_repo=_required("GITHUB_REPO"),
            github_repo_android=os.environ.get("GITHUB_REPO_ANDROID", "").strip(),
            triage_label=os.environ.get("TRIAGE_LABEL", "from-discord"),
        )


settings = Settings.load()
