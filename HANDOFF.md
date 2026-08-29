# Handoff: upload build 17 to TestFlight (Mac, any clone)

Delete this file (its own commit) once build 17 is distributed.

## What build 17 is

The 2026-08-29 Discord-issues round, merged to main:

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

`CURRENT_PROJECT_VERSION` is already bumped to 17 (marketing version
stays 1.1). Nothing to edit — just build and ship.

## Ship it

    git checkout main && git pull
    source tools/asc-auth.sh
    ./tools/upload-testflight.sh

The script archives Release, uploads, waits for processing, assigns the
same testers as build 16, and submits for beta review (fast on the
already-approved 1.1 train).

## Add it to the Discord group

The external group + public link exist from build 15; the new build just
needs adding:

    python3 tools/asc-api.py groups          # note the "Discord" group id
    python3 tools/asc-api.py notify <id>     # add build 17 to it

The public join link doesn't change, so no new Discord announcement is
required — testers get TestFlight's own update notice. If an announcement
is wanted anyway, dispatch `.github/workflows/discord-release.yml` with
the existing `testflight_link`.

## Companion release

Android 1.5 (versionCode 7) ships the same round — plus mode-scoped
mid-session Settings on every training screen — via `android-release.yml`
(workflow_dispatch on main); the remote session handles that upload,
nothing to do from the Mac.
