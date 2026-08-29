# Handoff: upload build 18 to TestFlight (Mac, any clone)

Delete this file (its own commit) once build 18 is distributed.

Build 18 supersedes build 17 — `CURRENT_PROJECT_VERSION` went straight
from 17 to 18 without 17 being uploaded, so everything in the build-17
handoff ships here too. That list is repeated below.

## What build 18 is

The 2026-08-29 Discord-issues round (second pass), merged to main:

- Hardware-keyboard answering no longer reads a digit you *heard* as a
  position. One multi-character option — a prosign or abbreviation from
  a later Journey level's pool — used to renumber the whole grid 1–9, so
  in a numbers drill you pressed 4 and answered whatever sat fourth
  (Android repo #30, same defect here). A single-character option now
  always answers to its own key; only longer options take a digit, and
  never one an option has claimed
- Character speed reaches 60 WPM everywhere it is set (#79): QRQ gains
  50 and 60 presets alongside 35/40, and the QSO simulator's caller
  speeds share the ceiling. The main slider was already 15–60
- A background noise floor under everything, defaulting to Whisper
  (Android repo #29). Bluetooth earbuds power down during digital
  silence and wake a moment late, clipping the first character and
  quietly costing accuracy; a faint trickle of real audio never lets the
  link idle. Louder levels give band noise (QRN) to copy through, and
  Off is there for anyone who wants silence

...and everything build 17 carried:

- Journey no longer pits the letter K against the prosign <K>: sound-
  identical items never share a drill's options, and hearing a lone
  dah-di-dah, either answer counts (#75)
- A miss holds the correction: Replay is always offered there, the item
  re-sends once after a beat, and Next moves on when you're ready (#77)
- Hardware-keyboard answering in the choice drills and Head Copy — type
  the letter you heard, 1–9 for meaning options, Return for Next /
  Reveal, R to repeat in Head Copy (#69)
- A Lingo tab in the Reference (ragchew, pileup, elmer, fist, zero
  beat …) and search that spans the whole reference (#76)
- Mid-session Settings scoped to the running mode (#66); a mid-session
  mode pick is honored by both recap exits (#67); the session bar's
  passive "155 words & calls" readout is gone (#74)
- Q-codes: QRL is the busy statement, QRL? the question (Android
  repo #27)

`CURRENT_PROJECT_VERSION` is already bumped to 18 (marketing version
stays 1.1). Nothing to edit — just build and ship.

CI (`.github/workflows/ios.yml`) already built this commit with
`xcodebuild` on a macOS runner and ran the `MorseKitCheck` harness
green, so the archive should be uneventful.

## Ship it

    git checkout main && git pull
    source tools/asc-auth.sh
    ./tools/upload-testflight.sh

The script archives Release, uploads, waits for processing, assigns the
same testers as the last build, and submits for beta review (fast on the
already-approved 1.1 train).

## Add it to the Discord group

The external group + public link exist from build 15; the new build just
needs adding:

    python3 tools/asc-api.py groups          # note the "Discord" group id
    python3 tools/asc-api.py notify <id>     # add build 18 to it

The public join link doesn't change, so no new Discord announcement is
required — testers get TestFlight's own update notice. If an announcement
is wanted anyway, dispatch `.github/workflows/discord-release.yml` with
the existing `testflight_link`.

## Worth telling the testers

Two of these want a specific kind of feedback:

- The Bluetooth first-character clipping should be gone with the default
  Whisper floor. If anyone still loses the first character on earbuds,
  the next level up (Low) is the thing to try — and worth knowing about,
  because it means the floor is too quiet for that hardware to stay
  awake on.
- G4BFG's off-by-one on a Bluetooth keyboard should be fixed; a
  confirmation from that setup would close Android repo #30 properly.

## Companion release

Android 1.6 (versionCode 8) ships the same round via
`android-release.yml` (workflow_dispatch on main) — the remote session
handles that upload, nothing to do from the Mac.
