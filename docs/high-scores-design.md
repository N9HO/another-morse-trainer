# High scores and a shared leaderboard: design notes

Status: **step 1 (local personal bests) shipped 2026-09-08 (PR #211); step 2
(shared leaderboard) built 2026-09-09 — server deployed, both clients in
one PR — awaiting the first real-device attestation on TestFlight and a
Play internal test.** Written 2026-09-08 from a
maintainer discussion, so the next person (or the next Claude session) can
start on the code without redoing the survey. Step 1 landed as `score` on
the session record on both ports, a persisted per-mode bests map
(`SessionHistory.bestScores` / `Stats.bestScores`) whose fold rule is pinned
by `fixtures/mode-bests.json`, and rows on each app's Personal bests card;
Rapid Fire's number is its correct count, Pileup Runner's its QSO count. Every file reference below was
checked against the tree at `7324e6b`; line numbers drift, names do not.

The ask: someone suggested scorekeeping and high scores for the games and
some of the other modes. The maintainer wants it as cheap as possible, wants
a leaderboard shared across both apps, and wants it hard to cheat.

Decisions taken:

1. **Local personal bests first**, on both apps, with no server. Most of the
   user-visible value, zero running cost, no privacy-policy change.
2. **A shared leaderboard on Cloudflare Workers + D1** as the second step,
   with device attestation and server-side grading as the anti-cheat.
   The server lives in **its own repository**, not here (see §7).
3. **Never trust a client-computed score.** The board ranks on metrics the
   server can grade from a transcript.

---

## 1. What the code does today

Both ports already end every run with a session record and already have a
"Personal bests" card. The mode-specific numbers a player would call a
*score* are computed, shown once, and discarded.

### iOS (`ios/`)

- Every mode in `TrainingMode` (`ios/MorseTrainerApp/AppModel.swift`,
  top of file) ends through one path: `AppModel.buildSessionRecord()`
  (same file, about line 2047), called from the session-end block about
  line 2026. It produces a `SessionRecord`
  (`ios/Sources/MorseKit/SessionHistory.swift`) with `mode`,
  `characterWPM`, `effectiveWPM`, `attempts`, `correct`, `fastestTTR`,
  `medianTTR`, `durationSeconds`, per-character results. `accuracy` is
  derived; `isScored` is false for the passive modes `listen` and `story`.
- Mode scores that are **not** persisted:
  - Contest: `score(qsoCount:multipliers:)` and `pointsPerQSO` in
    `ios/Sources/MorseKit/Contest.swift` (about lines 95 and 126).
  - Pileup: `qsoCount` / `bustCount` in
    `ios/Sources/MorseKit/PileupQSO.swift` (about line 301).
  - Arcade games: `score`, `wave`, `bestCombo`, `hits`, `misses`,
    `bestWpm` in `ios/Sources/MorseKit/Invaders.swift` (about line 229);
    Galaga, Defender, Dungeon, Frogger and Asteroids are analogous.
- Persistence is **UserDefaults only**: `SessionHistory` (capped at
  `SessionHistory.limit`, 100), `ActivityLedger`, `CharacterStats`,
  `PracticeStreak`, `ConfusionMatrix`. No CoreData, SwiftData or files.
- Stats UI: `ios/MorseTrainerApp/StatsView.swift`; the Personal bests
  rows are in `ios/MorseTrainerApp/BragSheetView.swift` (`personalBests`).
- Networking that already exists: `URLSession` in
  `ios/MorseTrainerApp/VailClient.swift` (WebSocket) and
  `NewsFetcher.swift`. **No entitlements file exists** and
  `CODE_SIGN_ENTITLEMENTS` is absent from the project; App Attest will be
  the first capability that adds one.

### Android (`android/`)

- `TrainingMode` in `SessionMenus.kt`, routed in `MainActivity.kt`. Each
  screen calls `Stats.record(...)`
  (`android/app/src/main/java/app/anothermorsetrainer/Stats.kt`, about
  line 158) itself, so there is no single end-of-run hook. Call sites:
  `PileupScreen`, `ContestScreen`, `RapidFireScreen`, `CodeExamScreen`,
  `ListenService` (passive), `InvadersScreen`, `GalagaScreen`,
  `DefenderScreen`, `DungeonScreen`, `FroggerScreen`, `AsteroidsScreen`,
  `QuizScreen`, `JourneyScreen`, `HeadCopyScreen`,
  `SendingPracticeScreen`, `StoryScreen`, `DailyDitStore`. Each has two
  call sites: the normal end and the process-death recovery path (see
  CLAUDE.md, "Session state on Android survives process death as a
  score").
- `Stats.record` takes `attempts`, `correct`, `bestTtrMs`,
  `durationSeconds`, `characterWpm`, `medianTtrMs`, `effectiveWpm`, char
  results. The game screens show `score` / `wave` / `bestCombo` /
  `bestWpm` on their summary and drop them.
- Persistence is **SharedPreferences only**, JSON via `org.json`:
  `Stats.kt` (`object Stats`, `SessionSummary`, `SessionRecord`,
  `history`, `bestTtrMs`), plus `EngineStore`, `Settings`, `JourneyStore`,
  `DailyDitStore`, `PileupSettings`, `VoiceProfileStore`, and per-game
  prefs that hold settings, not scores. No Room, no DataStore.
- Stats UI: `StatsScreen.kt` (`// MARK: - Personal bests` about line 636).
- Networking that already exists: OkHttp 4.12.0 (`android/app/build.gradle.kts`)
  used by `vail/VailClient.kt` (WebSocket) and `NewsFetcher.kt`.
- The app ships through Play Console (`android/RELEASE.md`), which Play
  Integrity requires.

### Neither side has

Firebase, CloudKit, GameKit / Game Center, Play Games Services, Retrofit,
`google-services.json`, iCloud, push entitlements, or any mention of
"high score", "leaderboard" or "personal best" outside the two Personal
bests cards.

### The seed question

The engines are seedable (`RapidFire`, `Invaders`, … take an injected
`RandomNumberGenerator` on iOS; the Kotlin ports take `kotlin.random.Random`)
but the two ports use **different generators**, so a server-issued seed
would not produce the same run on both apps. The fixtures in `fixtures/`
pin rules and derived values, not RNG streams. Do not try to unify the
generators for this feature: the transcript design in §3 avoids needing to.

---

## 2. Options that were weighed

| Option | Money | Ongoing work | Verdict |
|---|---|---|---|
| **1. Local bests only** | $0 | none | Do first. |
| 2. Game Center + Play Games Services | $0 | store console config, two SDKs | Two separate boards; iPhone players never see Android scores. Only worth it for the store-native achievements. Not chosen. |
| **3. Own tiny service** (Cloudflare Worker + D1) | $0 to $5/month | anti-cheat, name moderation, data deletion, uptime | One board across both apps. Chosen for step two. |

The hidden cost of option 3 is not hosting. The moment scores leave the
device you own an abuse surface, a display-name filter, a privacy
disclosure on both stores, and a way to delete a user's data. §3 and §6
are the answers to that.

---

## 3. Anti-cheat design

### The one fact that shapes everything

**The app is GPL, so nothing in the client can be a secret.** An HMAC key
baked into the binary is on GitHub. (Closed-source apps lose theirs in an
afternoon too.) Trust has to come from things a cheater cannot copy: the
maintainer's code-signing identity, and the server's own clock.

### Layers

1. **Device attestation is the workhorse.** App Attest (iOS, `DeviceCheck`
   framework) and Play Integrity (Android). Both free. Both prove a request
   came from a genuine, unmodified build signed by the maintainer, on a
   real device. The trust root is the Apple Team ID and the Play signing
   certificate, not the source code, so a self-built copy from the GPL
   source fails attestation, as does a patched APK or a curl script.
   - App Attest: generate a key once per install, attest it once with a
     server nonce, then send an *assertion* with every write. The
     server verifies the attestation certificate chain against Apple's
     App Attest root and each assertion's P-256 signature and counter.
     Needs the App Attest capability (entitlement
     `com.apple.developer.devicecheck.appattest-environment`). Real
     devices only; the simulator cannot attest.
   - Play Integrity: request a token with a server nonce, send it with
     the write. The server decrypts and verifies it either through
     Google's decrypt endpoint (needs a Google Cloud project and service
     account, free) or locally with keys downloaded from Play Console.
     Check the app-recognition and device-integrity verdicts. Sideloaded
     builds report unrecognised; decide whether to accept them (default:
     no).
2. **The server owns the run, not the client.** `POST /run/start` returns a
   one-time token with a short expiry (say 30 minutes). `POST /run/submit`
   must present it. The server recorded when the token was issued, so it
   knows the real elapsed wall time and rejects a token reused or
   submitted too early. A run that claims N characters at W WPM has a
   minimum physical duration from the PARIS formula already pinned in
   `fixtures/timing.json` (`unitSeconds = (1200 / characterWpm) / 1000`,
   `dah = 3u`, gaps as documented there). A submission that beats that
   clock is a fake.
3. **Never trust a client-computed score.** The client submits a
   *transcript*: per item, what was sent, what was answered, and the
   reaction time. The server grades it and computes the ranked metric
   itself. Plausibility bounds live here: WPM within the range the app can
   actually play, per-item reaction time not shorter than that item's
   playback time, item count consistent with total duration.
4. **Identity comes from attestation, not accounts.** iOS: the App Attest
   key ID is a stable per-install identity. Android: hash of a random
   install ID, bound to the Play Integrity verdict. One best score per
   identity per mode; rate-limit `run/start` per identity. No account
   system to build or moderate.
5. **Keep raw submissions and give yourself a delete button.** A rooted
   phone running a memory editor inside the genuine app gets through every
   layer above (true of Game Center too). Store the transcript, add an
   admin endpoint that deletes a row and shadow-bans the identity. The goal
   is to make cheating cost more than a curl one-liner, not to make it
   impossible.

### What each layer stops

| Attack | Stopped by |
|---|---|
| curl with a made-up score | Attestation |
| Modified or self-built app | Attestation |
| Replaying a real submission | One-time run token |
| Genuine app, fabricated instant run | Server clock plus PARIS timing bound |
| Genuine app, inflated score field | Server grades the transcript |
| Rooted device, memory editing | Nothing. Manual delete only. |

### Which modes are eligible

- **Rapid Fire, Contest, Pileup** produce transcripts the server can grade
  cleanly (sent text vs answered text, per-item timing). Start here.
- **Arcade games** (Invaders, Galaga, Defender, Dungeon, Frogger,
  Asteroids) have scores that depend on in-engine state the server cannot
  see (waves, combos, spawn timing). Rank them on server-graded hits,
  misses and speed if wanted, and accept that the board number will not
  match the score the player saw on screen. Or leave them local-only.
- **Passive modes** (`listen`, `story`) and Code Exam (pass/fail) are not
  leaderboard material.

---

## 4. Server design (own repository)

Three public endpoints, one admin endpoint, one Worker, one D1 database.
Both apps already have an HTTP client (URLSession / OkHttp), so no new
client dependency.

```
POST /v1/run/start
  body:   { platform, mode, characterWpm, effectiveWpm, attestation }
  action: verify attestation; rate-limit identity; insert run_token row
  reply:  { runToken, expiresAt }

POST /v1/run/submit
  body:   { runToken, displayName, transcript: [{ sent, answered, reactionMs }], attestation }
  action: verify attestation; load + consume token (single use, unexpired);
          check elapsed >= minimum PARIS duration for the transcript at the
          claimed WPM; grade transcript; compute metric; upsert best-per-
          identity-per-mode; store raw submission
  reply:  { accepted, rank, metric, reason? }

GET  /v1/board/{mode}?limit=50
  action: top N by metric; serve from edge cache with ~60 s TTL
  reply:  [{ rank, displayName, metric, platform, date }]

DELETE /v1/admin/score/{id}      (bearer secret held only on the server)
  action: delete row, flag identity as shadow-banned
```

Schema sketch (D1 / SQLite):

```sql
CREATE TABLE identities (id TEXT PRIMARY KEY, platform TEXT, created_at INTEGER,
                         banned INTEGER DEFAULT 0);
CREATE TABLE run_tokens (token TEXT PRIMARY KEY, identity_id TEXT, mode TEXT,
                         character_wpm INTEGER, effective_wpm INTEGER,
                         issued_at INTEGER, expires_at INTEGER, used INTEGER DEFAULT 0);
CREATE TABLE scores (identity_id TEXT, mode TEXT, metric REAL, display_name TEXT,
                     platform TEXT, submitted_at INTEGER, submission_id TEXT,
                     PRIMARY KEY (identity_id, mode));
CREATE INDEX scores_board ON scores (mode, metric DESC);
CREATE TABLE submissions (id TEXT PRIMARY KEY, identity_id TEXT, mode TEXT,
                          transcript TEXT, submitted_at INTEGER);  -- prune after 30 days unless flagged
```

Display names: allow-list of characters, length cap, a short profanity
list, and the admin delete above. Callsign-shaped names are the natural
default for this audience.

Attestation verification runs fine in a Worker with WebCrypto; libraries
exist for both formats. Apple's App Attest server-side steps and Google's
Play Integrity verdict format are documented by each vendor and are the
source of truth for the verification code; do not paraphrase them from
memory when writing it.

---

## 5. Client work (both apps, same PR or paired issues)

Per CLAUDE.md, this is two edits per change, one per tree, in that tree's
idiom, and both READMEs and the guide move with it.

### Step 1: local personal bests (no server)

- iOS: add an optional `score: Int?` (or a small `ModeScore` struct) to
  `SessionRecord` in `ios/Sources/MorseKit/SessionHistory.swift`; fill it
  in `buildSessionRecord()` from the live engine for Contest, Pileup,
  Rapid Fire and the games; extend `personalBests` in `BragSheetView.swift`
  with a per-mode best.
- Android: add the same optional field to `SessionSummary` /
  `SessionRecord` in `Stats.kt` and a parameter on `Stats.record`; pass it
  from each game/contest/pileup screen's **two** call sites; extend the
  Personal bests card in `StatsScreen.kt`.
- Fixture: if the per-mode score formula is anything beyond "the engine's
  own number", pin it in a new `fixtures/*.json` per CLAUDE.md rule 1, and
  run the negative control (rule 2).
- Guide: the Stats section gains a line about per-mode bests.

### Step 2: shared leaderboard

- iOS: App Attest capability + entitlement; a `LeaderboardClient` actor
  modelled on `VailClient` (single `URLSession`, structured concurrency,
  strict concurrency is at *complete*); transcript capture in the engines
  (they already know sent/answered per item; reaction time is the
  existing TTR); opt-in toggle in Settings; board view.
- Android: Play Integrity dependency (`com.google.android.play:integrity`)
  and Play Console linking; a `LeaderboardClient` on OkHttp modelled on
  `vail/VailClient.kt`; same transcript capture; same opt-in; board screen.
  Written blind and verified by CI only, so keep the network layer small
  and the parsing in plain Kotlin that the JUnit suite can cover.
- Both: submission is **opt-in** and off by default; a display name is
  chosen at opt-in; a "delete my scores" action in Settings calls the
  server (needed for both stores' data-deletion requirements).
- Privacy: both store listings need the data disclosure updated (device
  identifier, gameplay data, display name). Settings › About gets the new
  third-party notice if the Play Integrity SDK carries one (CLAUDE.md,
  Licensing).
- PARITY.md: no exception expected. The board is the same on both apps;
  only the attestation mechanism differs, and that is "the platform's own
  idiom", not a divergence.

---

## 6. Cost model

Numbers as published in 2026 and gathered from third-party summaries
(Cloudflare's own pages were not reachable from the session that wrote
this). **Confirm on developers.cloudflare.com before relying on them.**

| | Free | Workers Paid ($5/month) |
|---|---|---|
| Worker requests | 100k / day (hard cap: requests fail past it) | 10M / month, then ~$0.30 per million |
| CPU time | 10 ms / request | 30M CPU-ms / month, then ~$0.02 per million |
| D1 rows read | 5M / day | 25B / month |
| D1 rows written | 100k / day | 50M / month, then ~$1 per million |
| D1 storage | 5 GB | 5 GB, then per-GB overage |

Estimate for **10k installed users**, generous day: 3k open the app, each
plays five ranked runs (two requests each) and reads the board five times.

| Per day | Estimate | Free limit |
|---|---|---|
| Requests | ~45k | 100k |
| D1 writes | ~30k | 100k |
| D1 rows read | ~1M | 5M |
| CPU | ~75k ms | fine |

Fits the free plan. The risk is the daily request **cap**, not money: if
all 10k played ten runs a day that is ~300k requests/day and the board
stops answering for the rest of the day. So pay the $5 as soon as there is
real traffic; it turns a cliff into an invoice, and at 10k users the Paid
plan still covers everything with nothing to add. **Expected bill: $0 to
$5/month.**

At **100k users** with the same behaviour: ~90M requests/month, ~6M
writes/day. Overage roughly $25 requests + a few dollars CPU + $10 writes,
call it **~$40/month**. Storage is the only line that grows without bound
if every transcript is kept: prune after 30 days, keep flagged ones.

Outside Cloudflare: App Attest is free. Play Integrity is free under a
daily quota this app will not approach, and needs a Google Cloud project
(free at this usage). Attestation verification is the heaviest CPU per
request (a few ms of signature checking).

Cheap levers if it grows: serve `GET /board` from the edge cache with a
one-minute TTL; keep run tokens in KV or a short-lived table so writes
stay under quota.

---

## 7. Where the code goes

- **Server: a separate repository.** CLAUDE.md is explicit that anything
  outside `ios/` and `android/` has to be hand-wired into
  `merge-gate.yml` and the path-filtered workflows. A Worker in its own
  repo keeps this repo's CI untouched and lets the server deploy on its
  own cadence (it will change more often than either app at first).
- **Client code: this repository**, both trees, per the parity rule.
- **This document** stays here as the record of the decision. The server
  repo exists: **[N9HO/another-morse-trainer-leaderboard](https://github.com/N9HO/another-morse-trainer-leaderboard)**
  (private until the first release). Its README is the live API contract;
  the scaffold there implements §4 with the attestation verifiers stubbed to
  reject until the Apple and Google accounts exist.

---

## 8. Open decisions and next steps

Decided 2026-09-08 by the maintainer:

- [x] Ranked at launch: Rapid Fire, Contest, Pileup Runner **and** the six
      arcade games. A game's board number is the server metric below, not
      its on-screen score; the UI and guide must say so.
- [x] Metric: the speed summed over correctly copied items (each item's
      own WPM in the ramping games, the run's effective WPM otherwise), so
      a fixed-speed run ranks on correct × effective WPM. Implemented and
      tested in the server repo's `src/grade.ts`; pin it in a fixture here
      when the clients start sending transcripts.
- [x] Android installs Play Integrity does not recognise: rejected.
- [x] Display names: 2 to 12 characters, letters, digits, space, `/`, `-`,
      uppercased, a short deny list (`src/names.ts`); the admin delete is
      the real moderation tool.
- [x] Server repository: `N9HO/another-morse-trainer-leaderboard`, on the
      maintainer's Cloudflare account (being created).

Still owed by the maintainer before deploy and attestation: the Cloudflare
account, App Attest enabled on the App ID, a Google Cloud project linked in
Play Console with a service-account key stored as a Worker secret.

Build order:

1. ~~Step 1 (local bests) on both apps, one PR, guide updated.~~ PR #211.
2. Server repo: ~~schema, three endpoints, admin delete, a test suite that
   feeds fabricated transcripts and expects rejection for each row of the
   "what each layer stops" table~~ (scaffold landed); attestation
   verification for both platforms still to write, against the vendors'
   documents, once the accounts exist and a real device can produce a
   sample to test against.
3. ~~Step 2 clients, both apps, opt-in off by default.~~ Both apps, one PR:
   transcript capture in the nine modes (the exact semantics are the table
   in the server README), App Attest / Play Integrity clients, opt-in with
   display name, a board screen, "Delete my scores". Keying mode in
   Invaders, Galaga and Asteroids plays no Morse, so those runs are not
   submitted; only hear-it runs rank. The Android client carries the Google
   Cloud project number (`LEADERBOARD_CLOUD_PROJECT_NUMBER`).
4. First real attestation: a TestFlight build on an iPhone and a Play
   internal-test build; then store listings' privacy disclosures (device
   identifier, gameplay data, display name), then release.

---

## Sources for the pricing figures

- https://www.srvrlss.io/provider/cloudflare/
- https://www.budgetforge.dev/tools/cloudflare-workers-pricing-2026
- https://freetier.co/articles/cloudflare-d1-free-tier-limits-pricing-and-alternatives
- https://toolradar.com/tools/cloudflare-d1/pricing
- Official (verify here): https://developers.cloudflare.com/workers/platform/pricing/
  and https://developers.cloudflare.com/d1/platform/pricing/
