# Buddy streaks: design notes

Status: **built** — one buddy in #219 (both apps, leaderboard Worker
`/v1/buddy/*`); several buddies in #237, decided below under "Several
buddies". Written 2026-09-11 for issue #219 (jsvana, via Discord): pair with
another user and keep a shared practice streak, in the spirit of Duolingo's
friend quests. Read with
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
   buddy line ("W1AW practised today · 12-day buddy streak", or "W1AW hasn't
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
   with the streak for a while. (More than one buddy was asked for: #237,
   below.)

## Several buddies (#237)

Asked for by the same reporter after living with one: a separate streak
with each of several people. Decided 2026-09-26:

- **A cap of ten.** A status call reads each buddy's practice days, so ten
  is eleven small reads; ten rows fit the Settings list on a phone; the
  reminder's one sentence stays readable; and it bounds what leaked codes
  can do (five invites a day, each single use). The cap is the server's
  (`MAX_BUDDIES`), reported as `maxBuddies` in every v2 status, so the apps
  never hard-code it and raising it needs no release. A full list refuses
  invites and joins on both sides of a pairing; the apps hide the invite
  controls instead.
- **One streak per pairing, same rule.** Each pairing's streak is the #219
  fold of the two calendars. One practice day counts toward every pairing:
  the app still reports a day once, not once per buddy.
- **Nudges aggregate; no push.** The home line and the daily reminder say
  one thing about the whole list — "All 3 buddies practised today · best:
  40-day buddy streak", or "W1AW, K1ABC and 2 more haven't practised yet
  today (as of 6:10 pm)" — never one line or notification per buddy. With
  one buddy the #219 wording is unchanged. Push stays out of scope.
- **No leaderboard surface.** Buddies are not shown on the board (#226);
  the list is private to the two sides of each pairing.
- **Pairing ids, never identities.** Each pairing has an opaque id, the
  same on both sides, which "leave" takes. Leaving one buddy ends that
  streak for both and keeps the rest.

The rules the apps apply to the list — reading both server shapes, the
digest behind the home line and reminder, and when an invite stays shown —
are pinned for both ports by `fixtures/buddy-list.json`.

### Server: `/v2/buddy`, `/v1` unchanged

The one-buddy routes are what every shipped app speaks, so they keep their
request and response shapes exactly. `/v2/buddy/invite|join|day|status|leave`
take the same bodies (leave adds `buddyId` and `today`) and answer with the
list: `{ buddies: [{ id, displayName, practisedToday, streak }], maxBuddies,
myStreak, practisedToday, today }` (join adds `joined`, the new pairing's
id). Both versions share the pairings, so a v1 app and a v2 app pair with
each other:

- a v1 status shows the oldest pairing;
- a v1 invite or join is refused while the caller has any buddy (as
  before);
- a v1 invite's code pairs its inviter only while it has none — its app
  could not show a second buddy — and a refused join never burns the code;
- a v1 leave ends every pairing, which is what a one-buddy app means.

Storage moves to `buddy_links` (one row per pairing, the two identities in
sorted order, an opaque id), added by an additive migration that also marks
which API issued each invite and copies the old one-buddy pairs across;
`buddy_pairs` is kept, unwritten, so rolling the Worker back still finds the
pairs that existed at migration time.

### Apps: v2 first, v1 when the Worker has no v2

Every buddy call tries `/v2`. A Worker without it answers the bare 404
"not found" its router gives any unknown path, before it reads the body, so
nothing is spent but a challenge; on exactly that answer the app repeats the
call on `/v1`, reads the one-buddy status as a list of at most one (cap 1),
and uses `/v1` for the rest of that launch. The fallback is not persisted,
so the first launch after the Worker is deployed uses v2. A cache saved by
the one-buddy app is read on upgrade as a list of one buddy with no pairing
id; the next status fills the id in, and leaving such an entry goes through
the v1 route.
