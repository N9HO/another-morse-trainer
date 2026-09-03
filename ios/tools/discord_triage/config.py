"""Configuration loaded from environment variables.

All secrets come from the environment — nothing is hardcoded or committed.
See .env.example for the full list and the README for how to set them on Fly.io.
"""

from __future__ import annotations

import os
import warnings
from dataclasses import dataclass, field
from typing import Optional


# Triage calls the Messages API without streaming, and the SDK rejects a
# non-streaming request whose max_tokens implies more than ten minutes of
# generation (3600 * max_tokens / 128000 > 600). 16000 is the largest budget
# that clears that bar, so it is both the default and the ceiling.
MAX_OUTPUT_TOKENS = 16_000


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


def _float(name: str, default: float) -> float:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return max(0.0, float(raw))
    except ValueError:
        raise RuntimeError(f"{name} must be a number of seconds, got {raw!r}")


def _int(name: str, default: int, maximum: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        raise RuntimeError(f"{name} must be a whole number of tokens, got {raw!r}")
    if not 1 <= value <= maximum:
        raise RuntimeError(f"{name} must be between 1 and {maximum}, got {value}")
    return value


def _emoji_set(raw: str) -> frozenset[str]:
    # Accept several emojis separated by commas and/or whitespace; any of them
    # triggers a triage. Emojis contain neither commas nor spaces, so splitting
    # on both is safe. Falls back to 🐛 if nothing valid is given.
    parts = [p for p in raw.replace(",", " ").split() if p]
    return frozenset(parts) or frozenset({"🐛"})


def _deprecated_android_repo() -> str:
    """Always "" — see Settings.github_repo_android.

    The Android app moved into the same repo as iOS, so a separate Android
    destination is always wrong now; the old repo is archived, which makes its
    issue tracker read-only. Warns rather than fails when GITHUB_REPO_ANDROID is
    still set, because a config change in git does not redeploy the bot — a live
    Fly.io secret can outlive this file, and a warning puts that in the logs
    instead of silently ignoring it.
    """
    stale = os.environ.get("GITHUB_REPO_ANDROID", "").strip()
    if stale:
        warnings.warn(
            f"GITHUB_REPO_ANDROID is set to {stale!r} but is no longer used: the "
            "Android app now lives in the same repo as iOS, so every platform "
            "files in GITHUB_REPO. Unset it with "
            "`fly secrets unset GITHUB_REPO_ANDROID`.",
            RuntimeWarning,
            stacklevel=2,
        )
    return ""


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
    # How long to let a thread settle before triaging it. Reporters routinely
    # split one thought across several messages a few seconds apart; waiting
    # coalesces the burst into a single triage over the whole conversation
    # instead of racing each fragment (which double-files and asks questions
    # the next message was already answering). 0 disables the wait.
    settle_seconds: float

    # --- Anthropic ---
    anthropic_api_key: str
    # Defaults to the most capable model. For lower cost on this high-volume,
    # low-complexity task, set ANTHROPIC_MODEL=claude-haiku-4-5 (cheapest) or
    # claude-sonnet-5 (mid). Your call — see the README cost note.
    model: str
    # Output budget for one triage call. It has to hold the WHOLE verdict — a
    # full Markdown issue body with Steps/Expected/Actual, a follow-up comment,
    # a title and a Discord reply — because a structured output that runs out of
    # room comes back as truncated JSON, which does not parse and takes the
    # report down with it (see triage.TriageError). On a model that thinks by
    # default the thinking is drawn from this same budget, so a small value is
    # not a saving: it is an outage. Only tokens actually generated are billed,
    # so a generous ceiling costs nothing on a short verdict.
    max_tokens: int

    # --- GitHub ---
    github_token: str
    github_repo: str  # "owner/name" — the repo every issue is filed in.
    # Always "" since the iOS and Android apps merged into one monorepo: there is
    # no second repo to route Android bugs to, so every platform files in
    # github_repo. Kept as a field (rather than deleted) because bot.py reads it
    # to decide whether to preflight a second repo's token scope; empty is
    # exactly the "single repo" answer it already knows how to handle.
    github_repo_android: str
    # Apply this label to every issue the bot opens, so they're easy to find/filter.
    triage_label: str

    def repo_for(self, platform: Optional[str]) -> str:
        """Pick the destination repo for a verdict's platform.

        One monorepo now holds both apps, so every platform files in the same
        place. The platform is still recorded on the issue via its label.
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
            settle_seconds=_float("TRIAGE_SETTLE_SECONDS", 8.0),
            anthropic_api_key=_required("ANTHROPIC_API_KEY"),
            model=os.environ.get("ANTHROPIC_MODEL", "claude-opus-5"),
            max_tokens=_int("ANTHROPIC_MAX_TOKENS", MAX_OUTPUT_TOKENS,
                            MAX_OUTPUT_TOKENS),
            github_token=_required("GITHUB_TOKEN"),
            github_repo=_required("GITHUB_REPO"),
            github_repo_android=_deprecated_android_repo(),
            triage_label=os.environ.get("TRIAGE_LABEL", "from-discord"),
        )


settings = Settings.load()
