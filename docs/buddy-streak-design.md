# Buddy streaks: design notes

Status: **proposal, nothing built.** Written 2026-09-11 for issue #219
(jsvana, via Discord): pair with another user and keep a shared practice
streak, in the spirit of Duolingo's friend quests. Read with
`docs/high-scores-design.md`: the leaderboard server (step 2) already gives
every opted-in device an attested, account-free identity, and that is what
makes this cheap.

## What it is

Two people pair up. Each day that **both** practised, the buddy streak goes
up by one; a day either of them missed resets it. Each sees the other's
display name, whether they have practised today, and the streak. That is
the whole feature: accountability, not competition.

## What it is not (first cut)

- Not a friends list, feed, chat or leaderboard of pairs.
- Not push notifications. The server has no APNs or FCM setup and adding it
  is a bigger project than the feature. The nudge is in-app (see below).
- Not a joint goal with a target ("copy 500 characters together"). The
  reporter mentioned friend quests; the streak is the simplest thing that
  gives the motivation. Quests can come later on the same plumbing.

## How it works

Every piece reuses the leaderboard's identity and server. Nothing here
requires an account, an email or a name.

1. **Opt in.** Settings › Buddy. Uses the leaderboard display name (or asks
   for one). Off by default; turning it on needs the same attestation as
   posting a score (App Attest / Play Integrity), so a buddy is a real
   install of the genuine app.
2. **Pairing by code.** "Invite a buddy" asks the server for a six-character
   code (letters and digits, valid 24 hours, single use). You send it however
   you like: Discord, text, on the air. The other person types it under
   "Join a buddy". The server links the two identities. One buddy per
   identity in the first cut; either side can unpair.
3. **Practice days.** When paired, the app tells the server "I practised
   today" the first time each local day that the existing streak logic
   marks a practice day (`markPracticedToday` on iOS, `Stats.record` /
   `PracticeStreak` on Android). The message carries the local calendar
   day as `yyyy-mm-dd`, not a timestamp, so time zones and midnight work
   the way the personal streak already does: your day is your day. A Daily
   Dit guess or a Listen & Learn session counts, exactly as for the
   personal streak; nothing new decides what "practised" means.
4. **The streak.** The server folds both calendars: the buddy streak is the
   number of consecutive days, ending today or yesterday, on which both
   sides reported a practice day. "Yesterday" keeps it alive until the
   later of the two has had their whole day. Both apps read the same
   number and the buddy's today status from one `GET`.
5. **The nudge.** In-app only: the home screen's streak card shows the
   buddy line ("N9HO practised today · 12-day buddy streak", or "N9HO hasn't
   practised yet today"), and the existing daily reminder notification's
   text gains a buddy sentence when the app last saw the buddy had not
   practised. That is a local notification built from the last fetch; it
   can be a few hours stale and says so ("as of 6:10 pm").

## Server (leaderboard repo)

Three routes and two tables on the existing Worker; all writes attested
like `/run/start`, all keyed by the leaderboard identity.

```
POST /v1/buddy/invite    { challenge, attestation }             → { code, expiresAt }
POST /v1/buddy/join      { challenge, attestation, code }       → { buddy: { displayName }, streak }
POST /v1/buddy/day       { challenge, attestation, day }        → { streak, buddyPractisedToday }
GET  /v1/buddy?...       attested (GET with a signed challenge)  → { buddy, streak, buddyPractisedToday, myPractisedToday }
POST /v1/buddy/leave     { challenge, attestation }             → { ok }

pairs        (a TEXT, b TEXT, created_at)           PRIMARY KEY (a), UNIQUE (b)
practice_days (identity_id TEXT, day TEXT)         PRIMARY KEY (identity_id, day); keep 120 days
invites      (code TEXT PRIMARY KEY, identity_id, expires_at, used)
```

`/v1/me/delete` also removes the pair, the invites and the practice days.
Cost: a few writes per user per day; nothing next to the leaderboard.

## Anti-abuse

A practice day is a self-report, and that is fine: the only person it can
mislead is your buddy, and the attestation means it at least comes from a
genuine install. Rate-limit invites (a few per day per identity). Codes
are single use and short-lived, so a code posted publicly pairs one
stranger at worst, and unpairing is one tap.

## Privacy

Adds to what the leaderboard already stores: the pair link and up to 120
days of yes/no practice days per identity. The buddy sees your display
name and whether you practised each day, nothing else. Both stores'
disclosures gain "App activity › App interactions" if not already declared
(it is, for the leaderboard) and the policy's leaderboard section gains a
paragraph. Delete my scores clears it all.

## Parity

Same feature on both apps, same server, same routes; only the attestation
mechanism differs, which is the platform's own idiom. Pairing across
platforms works because identities are platform-neutral on the server.

## Open questions for the reporter (asked on #219)

1. Does "any practice day" match what you expect, or should the buddy
   streak need something more (a session of at least N minutes, a
   finished drill)?
2. Is one buddy enough for a first version?
3. Is an in-app line plus a richer daily reminder enough, or is a real
   push ("your buddy is about to break the streak") the point? The latter
   is a separate project.
4. Pairing by a short code you share yourself: fine, or do you want the
   app to find people (Discord handle, contacts)? The code needs no
   directory and no new data.

## Build order, if approved

1. Server routes, tables and tests (one PR in the leaderboard repo).
2. Both clients in one PR: Settings › Buddy, the invite/join flow, the
   daily report at the existing practice-day mark, the home-card line and
   the reminder sentence; guide section; policy paragraph.
3. Quests, more than one buddy, push, only if people ask after living
   with the streak for a while.
