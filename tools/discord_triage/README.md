# Discord → GitHub triage bot

A small always-on bot that watches the **Another Morse Trainer** Discord, uses
Claude to triage bug reports and feature requests, and opens clean, deduplicated
GitHub issues in `n9ho/another-morse-trainer` — replying in the thread with the
result.

```
Discord message ──▶ Claude triage ──▶ GitHub issue ──▶ reply in Discord
   (bug/feature)     classify + dedup     opened           "Logged it — #123 ✅"
                     + clean write-up
```

## What it does

- **Classifies** each message: `bug`, `feature`, `question`, or `noise`. Only
  bugs and feature requests become issues.
- **Cleans it up**: rewrites casual phrasing into a precise issue with
  Steps to reproduce / Expected / Actual, references app areas (QSO Simulator,
  Confusion Matrix, Timing, …), and credits the reporter.
- **Dedupes** against currently open issues before filing.
- **Triages**: suggests labels (`bug` / `enhancement` / `needs-info`) and a
  severity, and tags every issue with a `from-discord` label.
- **Holds a conversation**: when a report is too thin, it opens a **thread**,
  asks for the missing detail (repro steps, platform, a **screenshot**), and
  watches that thread. If the reporter already opened a thread on their
  message, the bot adopts it — every reply (including "duplicate of #N") lands
  in that thread, where the reporter is looking, never as a channel-level
  reply. On each reply it re-reads the whole conversation — **viewing any
  attached screenshots via Claude's vision** — until it has enough to file.
  Once filed, further details are added to the issue as comments.
- **Closes the loop**: replies with the issue link, a duplicate pointer, or a
  follow-up question.

Structured outputs (a Pydantic schema) guarantee Claude's verdict always parses.

> The thread → issue mapping is kept **in memory**, so a bot restart forgets
> in-progress threads. That's fine in practice — just re-trigger the report with
> a fresh 🐛 and dedup keeps it from filing twice.

## Trigger modes

Set via `TRIGGER_MODE`:

| Mode | Behavior | When |
|---|---|---|
| `react` *(default)* | Only triages a message when a maintainer reacts with 🐛 (`TRIGGER_EMOJI`). | Lowest noise & cost — recommended to start. |
| `auto` | Triages every non-bot message in the watched channels. | Fully hands-off intake, more API calls. |

Scope it to specific channels with `WATCH_CHANNEL_IDS` (comma-separated IDs).

## Setup

### 1. Create the Discord bot
1. https://discord.com/developers/applications → **New Application**.
2. **Bot** → copy the **token** (→ `DISCORD_BOT_TOKEN`).
3. Under **Privileged Gateway Intents**, enable **Message Content Intent**.
4. **OAuth2 → URL Generator**: scope `bot`, permissions *Read Messages/View
   Channels*, *Read Message History*, *Send Messages*, *Add Reactions*, plus
   **Create Public Threads** and **Send Messages in Threads** (required for the
   follow-up conversation flow). Open the URL to invite the bot to your server.

> If the bot lacks the thread permissions it falls back to a single-shot reply
> in the channel and can't gather follow-up info — so make sure those two are
> granted (re-run the invite URL to update permissions if needed).

### 2. Create a GitHub token
A fine-grained PAT with **Issues: Read and write** on **every repo the bot
files into** (→ `GITHUB_TOKEN`): `n9ho/another-morse-trainer`, plus
`n9ho/another-morse-trainer-android` if `GITHUB_REPO_ANDROID` is set — a token
scoped to the iOS repo alone gets a 404 filing Android reports. Note the
expiration you pick: fine-grained PATs expire silently, and an expired token
turns every filing into a 401. The bot verifies access to each configured repo
at startup and logs exactly what's wrong.

### 3. Get an Anthropic API key
From https://console.anthropic.com → **API Keys** (→ `ANTHROPIC_API_KEY`).

## Run locally

```bash
cd tools/discord_triage
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # fill it in
set -a; source .env; set +a # export the vars
python bot.py
```

## Deploy on Fly.io

```bash
cd tools/discord_triage
fly launch --no-deploy        # accept the included fly.toml; pick an app name/region

# Secrets (never put these in fly.toml or .env in git):
fly secrets set \
  DISCORD_BOT_TOKEN=...        \
  ANTHROPIC_API_KEY=...        \
  GITHUB_TOKEN=...

# Optional non-secret overrides also work via `fly secrets set`, e.g.:
# fly secrets set WATCH_CHANNEL_IDS=123,456 TRIGGER_MODE=auto

fly deploy
fly logs                      # watch it connect
```

The bot has no inbound HTTP, so `fly.toml` has no `[http_service]` — it runs as a
single always-on machine holding the Discord gateway connection. On a
`shared-cpu-1x` / 256 MB machine this costs roughly **$0–2/month**.

## Cost note (model choice)

`ANTHROPIC_MODEL` defaults to `claude-opus-4-8` (most capable). Triage is a
high-volume, low-complexity task, so if you want to cut cost set:

- `claude-haiku-4-5` — cheapest, fast, fine for classification.
- `claude-sonnet-4-6` — middle ground.

Each triage is a single short request, and the instruction prompt is cached, so
even on Opus the per-message cost is small — but Haiku is the economical default
for a busy server.

## Troubleshooting: "hit an error filing the issue"

That reply means triage itself worked (Claude classified the report and wrote
the issue) and the **GitHub** create-issue call failed — so it's the
`GITHUB_TOKEN`, not the Anthropic key or credits. The reply now includes the
GitHub status, and `fly logs` has the full story, including a startup probe of
every configured repo. The usual suspects:

- **401 Bad credentials** — the PAT expired or was revoked. Generate a new one
  (covering both repos) and `fly secrets set GITHUB_TOKEN=...`.
- **404 Not Found** — the repo isn't granted to the PAT. Classic case: routing
  was enabled via `GITHUB_REPO_ANDROID` but the token from the original setup
  only covers the iOS repo. Edit the PAT's repository access to add the
  Android repo.
- **403 Resource not accessible** — the repo is granted but the token lacks
  the **Issues: Read and write** permission on it.

When the *routed* (Android) repo rejects the token with 403/404, the bot falls
back to filing in `GITHUB_REPO` — with the `platform: android` label and a ⚠️
note in the Discord reply — so the report isn't lost while the token gets
fixed.

## Resolution notifications ("this is fixed")

When an issue the bot filed is **closed** (e.g. a fix is merged), the bot posts a
message back into the original Discord thread. This is handled by a GitHub Action
(`.github/workflows/notify-discord-on-close.yml`), not the bot process itself —
the bot stamps each issue it opens with a hidden `discord-thread:<id>` marker, and
the Action reads it on close and posts via a Discord webhook.

To enable it:
1. **Create a Discord channel webhook**: in the channel your triage threads live
   in → **Edit Channel → Integrations → Webhooks → New Webhook → Copy Webhook URL**.
2. **Add it as a GitHub repo secret**: Settings → Secrets and variables → Actions →
   new secret named **`DISCORD_WEBHOOK_URL`**.
3. The workflow must be on the **default branch (`main`)** to fire on issue events.

(Only issues filed *after* this is deployed carry the marker, and posting targets
the thread, which Discord keeps for the thread's auto-archive window.)

## Files

| File | Purpose |
|---|---|
| `bot.py` | Discord client + event handlers (the entry point). |
| `triage.py` | Claude triage call + the structured `Verdict` schema. |
| `github_client.py` | List open issues / create issues via the GitHub REST API. |
| `config.py` | Environment-variable configuration. |
| `Dockerfile` / `fly.toml` | Container + Fly.io deployment. |
| `.env.example` | Template for the required environment variables. |
