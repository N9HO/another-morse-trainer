# Handoff: redeploy the Discord triage bot (Mac, any clone)

Delete this file (its own commit) once the bot is redeployed and a 🐛
re-react on the stuck report draws a 👀.

## What changed (2026-08-28, remote session)

Justin's "Unneeded info in training screen" thread exposed why the bot
ignored 🐛 on a report the reporter had already threaded: the reaction
arrives on the parent channel, and every reply path went there —
invisible from inside the thread — or died on Discord's
thread-already-exists error. Two merged rounds fix it (PR #68, PR #70):

- The bot adopts a reporter-created thread and replies **in it** —
  including "duplicate of #N" — instead of `message.reply` in the channel.
- Archived threads are fetched and **un-archived** before speaking
  (sends into archived threads fail silently otherwise), and reactions
  inside archived threads resolve instead of bailing.
- `WATCH_CHANNEL_IDS` scoping is thread-aware (threads/forum posts
  inherit the watched parent's scope).
- The bot reacts **👀 the moment it accepts a 🐛** — no 👀 means the
  event never reached the running code.

## Ship it

    git checkout main && git pull
    cd tools/discord_triage
    fly deploy

## Verify

Re-react 🐛 on the "Unneeded info in training screen" starter message:

- **👀 then a reply in the thread** — done. Expect "duplicate of #61":
  the report is really feedback on #61's own fix (build 16's full-width
  "155 words & calls" readout), so either reply in-thread that the
  readout itself feels extraneous and re-react to file it fresh, or let
  the dup verdict stand.
- **👀 but no reply** — triage-side error: `fly logs` shows a
  `Start triage …` line or a traceback right after.
- **No 👀** — the image is stale:
  `fly ssh console -C "grep -c _message_thread /app/bot.py"` returning 0
  means the deploy went out from an un-pulled checkout.

Old replies from the broken path (e.g. "Looks like a duplicate of #61 🔁")
may be sitting in #bugs-and-feature-requests under the original message —
channel-level, outside the thread.

## Companion release (done, no Mac action)

Android 1.4.1 (versionCode 6) is on the Play closed-testing track —
run 13 of `android-release.yml` — carrying the QSO/Contest send-box
keyboard fix (Android #24, from ellybean's report; the `key(rev)`
tick rebuilt the run UI every second and killed the field's focus).
Her existing testing link still works; the update arrives via Play.
