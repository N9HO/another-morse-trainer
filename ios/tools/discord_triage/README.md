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
- **Dedupes** against open issues *and* recently-closed ones before filing. A
  match on a closed issue is the more useful catch: it usually means the
  reporter is on a build from before the fix, so the bot says so and asks them
  to update instead of filing the same bug again.
- **Triages**: suggests labels (`bug` / `enhancement` / `needs-info`) and a
  severity, and tags every issue with a `from-discord` label.
- **Files first, asks second**: a genuine bug or feature is filed straight
  away even when it's still thin — labelled `needs-info`, with a
  "Still needed" section naming what's missing. It used to wait for the
  reporter before filing, which meant a reporter who went quiet left no record
  anywhere but Discord and the maintainer never learned the report existed.
  An incomplete issue you can see beats one you never hear about. Questions,
  noise and duplicates are still never filed.
- **Holds a conversation**: it opens a **thread**, asks for the missing detail
  (repro steps, platform, a **screenshot**), and watches that thread. If the
  reporter already opened a thread on their message, the bot adopts it — every
  reply (including "duplicate of #N") lands in that thread, where the reporter
  is looking, never as a channel-level reply. On each reply it re-reads the
  whole conversation — **viewing any attached screenshots via Claude's
  vision** — and adds what it learns to the issue as comments.
- **Remembers the thread**: a thread is ONE report, however many messages it
  took. See below.
- **Closes the loop**: replies with the issue link, a duplicate pointer, or a
  follow-up question.

Structured outputs (a Pydantic schema) mean a verdict arrives in the shape the
bot asked for. They do not guarantee one arrives: an answer that runs out of
output budget is truncated JSON, which fails validation and takes the report
with it — see *Troubleshooting: "I hit an error analyzing that report"* below.

## Thread memory

Reporters don't say it all in one message. They post the bug, then the device
they saw it on, then a screenshot, then answer the question the bot asked. So
**anything that happens inside a thread triages the whole thread**, never the
one message that triggered it:

- **Every trigger reads the entire conversation** — the thread's title, the
  original report, every follow-up, the bot's own earlier questions, and the
  answers to them. This holds for threads the bot has never seen before (one
  the reporter opened themselves, a forum post, a thread left over from before
  a restart), which is what stopped it re-asking questions that were answered
  three messages up.
- **The post's title counts as part of the report.** In a forum channel that's
  the reporter's headline — "In QRQ Speed the UI might need to move up the
  screen" names the screen that the messages under it never mention. (A thread
  the bot opened itself is named after the message it hangs off, so its title
  is skipped rather than repeated.)
- **A burst of triggers is coalesced into one pass.** Triggers landing within
  `TRIAGE_SETTLE_SECONDS` (default 8) of each other are read together, so three
  thoughts typed in a row — or a 🐛 on the report *and* on the screenshot under
  it — produce one considered answer instead of two or three racing triages,
  and can't file the same bug twice. The 👀 goes on immediately, so a
  maintainer still sees the trigger land.
- **It asks each question at most once.** The forced "which OS is this?" prompt
  fires once per thread; after that the model decides for itself whether
  anything is genuinely still missing, and the prompt tells it in as many words
  that re-asking an answered question is the worst thing it can do here.
- **Follow-ups add only what's new.** The transcript marks where the GitHub
  issue's knowledge ends, so a comment carries the new detail instead of
  restating the thread.
- **Everyone in the thread is read, not just the reporter.** A helper's
  diagnosis ("that means AMT is sending MIDI messages that change the adapter's
  settings") is often the most useful thing in the thread, and it belongs in
  the issue. Mentions reach the model as names rather than raw ids.
- **A duplicate is only a duplicate when it can be named.** "Feels familiar"
  with no issue number used to file nothing and point the reporter at nothing,
  leaving the report in Discord — the exact failure *files first, asks second*
  exists to prevent. A possible duplicate you close in one click beats a report
  you never hear about. A number the model invents is treated the same way: a
  pointer that doesn't match an issue the bot actually showed it is discarded
  and the report is filed.
- **A duplicate report is attached, not just answered.** The bot comments on the
  issue it duplicates, crediting the new reporter and stamping that comment with
  their thread id — so when the issue closes, everyone who reported the bug gets
  told, not only whoever reported it first.
- **A restart is no longer amnesia.** The thread → issue map still lives in
  memory, but the bot announces every issue with its full URL and stamps the
  issue body with the thread id, so a forgotten thread recovers its issue from
  its own transcript — or, if that message is gone, by finding the
  `discord-thread:<id>` stamp on GitHub. A forgotten thread keeps commenting on
  the issue it already has instead of filing a second one. That GitHub lookup
  tries the search index first and falls back to scanning recent issue bodies,
  because search has been observed returning 422 for this query in production —
  and a scan of the plain issues endpoint works whenever filing does.

## Trigger modes

Set via `TRIGGER_MODE`:

| Mode | Behavior | When |
|---|---|---|
| `react` *(default)* | Only triages a message when a maintainer reacts with 🐛 (`TRIGGER_EMOJI`). | Lowest noise & cost — recommended to start. |
| `auto` | Triages every non-bot message in the watched channels. | Fully hands-off intake, more API calls. |

Scope it to specific channels with `WATCH_CHANNEL_IDS` (comma-separated IDs).

Either way, a trigger *inside a thread* means "re-read this whole conversation":
in `auto` mode every reply does that automatically, and in `react` mode a 🐛
anywhere in the thread does it on demand.

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
A fine-grained PAT with **Issues: Read and write** on the one repo the bot
files into (→ `GITHUB_TOKEN`): `n9ho/another-morse-trainer`. Since the Android
app moved into that same monorepo there is no second destination — every
platform files there, and the platform is recorded on the issue's label. Note
the expiration you pick: fine-grained PATs expire silently, and an expired
token turns every filing into a 401. The bot verifies access at startup and
logs exactly what's wrong.

> **`GITHUB_REPO_ANDROID` is retired.** It used to route Android reports to
> `n9ho/another-morse-trainer-android`; that repo is archived, so its issue
> tracker is read-only. `config.py` now ignores the variable and logs a
> `RuntimeWarning` if it is still set. **A config change in git does not
> redeploy the bot** — clear the live secret by hand:
>
> ```bash
> fly secrets unset GITHUB_REPO_ANDROID
> ```

### 3. Get an Anthropic API key
From https://console.anthropic.com → **API Keys** (→ `ANTHROPIC_API_KEY`).

## Run locally

```bash
cd ios/tools/discord_triage
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # fill it in
set -a; source .env; set +a # export the vars
python bot.py
```

### Tests

```bash
python3 test_conversation.py   # pure helpers — no dependencies, no tokens
python3 test_bot_flow.py       # thread memory, with fake Discord/GitHub/Claude
python3 test_triage_call.py    # the Anthropic call: budget + failure handling
python3 test_github_client.py  # stamp recovery, with a fake GitHub
```

They also run under `pytest`, which is what CI does:

```bash
pip install -r requirements-dev.txt   # requirements.txt + pytest
pytest
```

`pytest` is kept out of `requirements.txt` on purpose — the Dockerfile installs
that file into the production image, and `.dockerignore` keeps `test_*.py` out
of it, so a test runner in there would have nothing to run.

No live tokens are used: `test_bot_flow.py` and `test_triage_call.py` fill in
dummy environment variables and swap in fakes for Discord, GitHub, and the
model — the first faking the triage call wholesale to test what the bot does
with a verdict, the second faking only the Anthropic client to test the call
that produces one. `.github/workflows/triage-bot.yml` runs the suite on every
pull request that touches this directory, on Python 3.12 to match the
Dockerfile.

## Deploy on Fly.io

```bash
cd ios/tools/discord_triage
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

`ANTHROPIC_MODEL` defaults to `claude-opus-5` (most capable). Triage is a
high-volume, low-complexity task, so if you want to cut cost set:

- `claude-haiku-4-5` — cheapest, fast, fine for classification.
- `claude-sonnet-5` — middle ground.

Each triage is a single short request, and the instruction prompt is cached, so
even on Opus the per-message cost is small — but Haiku is the economical default
for a busy server.

`ANTHROPIC_MAX_TOKENS` (default 16000, hard ceiling 16000) is **not** a cost
knob and turning it down does not save money — only tokens actually generated
are billed, and a verdict for a thin report is a few hundred of them. What it
does control is whether the verdict has room to finish; see below.

## Troubleshooting: "I hit an error analyzing that report"

That reply (or, on a thread, "I hit an error analyzing that") means triage
itself failed — the report never reached GitHub, so there is nothing to look for
there. `fly logs` has the reason. In the order they have actually bitten:

- **`Your credit balance is too low to access the Anthropic API`** (a 400 from
  the Anthropic API) — the account behind `ANTHROPIC_API_KEY` is out of
  credits, so no request reaches the model at all. This has taken the bot down
  once, and from Discord it looks identical to every other analysis failure.
  Top up at [Plans & Billing](https://platform.claude.com/settings/billing),
  and turn auto-reload on so it doesn't recur. Watch for the trap that cost an
  afternoon: **credits have to be on the account that owns the key the bot
  uses**. Fly secrets are write-only, so check the key itself rather than
  assuming —

  ```bash
  fly ssh console -a morse-discord-triage
  python -c 'import anthropic; c=anthropic.Anthropic(); print(c.messages.create(model="claude-opus-5", max_tokens=16, messages=[{"role":"user","content":"ping"}]).usage)'
  ```

  A `Usage(...)` line means the key can reach the API and the fault is
  elsewhere; the same 400 means it genuinely has no credit. `python -c 'import
  os; k=os.environ["ANTHROPIC_API_KEY"]; print(k[:14], "...", k[-4:])'` prints
  enough to match the key against Console → API keys and find which account it
  belongs to. To repoint it: `fly secrets import -a morse-discord-triage`, then
  paste `ANTHROPIC_API_KEY=...` and Ctrl-D (stdin, so it stays out of shell
  history).
- **`the model's answer did not parse as a verdict`** — the verdict is a
  structured output whose `body` field is a whole Markdown issue write-up, so an
  answer that hits the output budget does not come back as a shorter issue: it
  comes back as JSON that stops mid-string, which fails validation and fails the
  triage. Every triage of that thread fails the same way, so the report sits in
  Discord and nothing is filed. The budget is `ANTHROPIC_MAX_TOKENS` (default
  16000, which is also the ceiling — above that the SDK requires streaming). It
  was a hardcoded 2048 until this was fixed, which was enough for a one-line
  report and not for a real one; on a model that thinks by default, the thinking
  came out of the same 2048 and nothing ever parsed.
- **`the model returned no verdict`** — a refusal, logged with its
  `stop_reason`. Rare, and it says so rather than filing the report as noise:
  a report that quietly becomes noise is one the maintainer never hears about.
- Anything else (a 401 from Anthropic, a rate limit, a network error) arrives as
  the SDK's own exception with the status in the log.

## Troubleshooting: "hit an error filing the issue"

That reply means triage itself worked (Claude classified the report and wrote
the issue) and the **GitHub** create-issue call failed — so it's the
`GITHUB_TOKEN`, not the Anthropic key or credits. The reply now includes the
GitHub status, and `fly logs` has the full story, including a startup probe of
every configured repo. The usual suspects:

- **401 Bad credentials** — the PAT expired or was revoked. Generate a new one
  and `fly secrets set GITHUB_TOKEN=...`.
- **404 Not Found** — the repo isn't granted to the PAT. Edit the PAT's
  repository access to include `n9ho/another-morse-trainer`.
- **403 Resource not accessible** — the repo is granted but the token lacks
  the **Issues: Read and write** permission on it.

The fallback path that re-filed a rejected Android report into `GITHUB_REPO`
is now a no-op: with a single repo, `GITHUB_REPO` is already the destination.

## Resolution notifications ("this is fixed")

When an issue the bot filed is **closed** (e.g. a fix is merged), the bot posts a
message back into **every** Discord thread that reported it. This is handled by a
GitHub Action (`.github/workflows/notify-discord-on-close.yml`), not the bot
process itself — the bot stamps each issue it opens with a hidden
`discord-thread:<id>` marker, and adds the same marker in a comment when it
attaches a later duplicate report. The Action reads the markers from the body and
from every comment on close, and posts to each thread via a Discord webhook.

To enable it:
1. **Get a webhook on the forum your triage posts live in**: that channel's gear →
   **Integrations → Webhooks** → an existing one, or **New Webhook** → **Copy
   Webhook URL**. A webhook can only post into threads of its own channel, so it
   must be a webhook *on the forum* — not the release announcer's.
2. **Add it as a GitHub repo secret**: Settings → Secrets and variables → Actions →
   new secret named **`DISCORD_TRIAGE_WEBHOOK_URL`** (`DISCORD_WEBHOOK_URL` is the
   release announcer's, on a different channel).
3. The workflow must be on the **default branch (`main`)** to fire on issue events.
4. A close whose run failed can be announced again by hand: Actions → *Notify
   Discord on issue close* → Run workflow → the issue number.

(Only issues filed *after* this is deployed carry the marker, and posting targets
the thread, which Discord keeps for the thread's auto-archive window.)

## Files

| File | Purpose |
|---|---|
| `bot.py` | Discord client + event handlers (the entry point). |
| `conversation.py` | Thread → transcript, and recovering a thread's issue. |
| `triage.py` | Claude triage call + the structured `Verdict` schema. |
| `github_client.py` | Read the issue corpus (open + recently closed) / create and comment on issues via the GitHub REST API. |
| `config.py` | Environment-variable configuration. |
| `test_conversation.py` | Transcript + issue-recovery tests (no deps needed). |
| `test_bot_flow.py` | Thread-memory tests against fake Discord/GitHub/Claude. |
| `test_triage_call.py` | The model call itself: output budget, and the two ways a verdict fails to arrive. |
| `test_github_client.py` | Recovering a thread's issue from its stamp, against a fake GitHub. |
| `Dockerfile` / `fly.toml` | Container + Fly.io deployment. |
| `.env.example` | Template for the required environment variables. |
