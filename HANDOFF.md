# Handoff: upload build 16 to TestFlight (Mac, any clone)

Delete this file (its own commit) once build 16 is distributed.

## What build 16 is

The 2026-08-27 Discord-issues round, merged to main:

- Menu tiles launch their mode directly — the pinned Continue button is
  gone; each mode's options moved onto its pre-flight sheet (#60)
- Navy-on-teal labels everywhere a control fills with the brand teal —
  the white-on-cyan contrast failure (#59)
- The truncated "155 h…" toolbar bubble is now a full-width session
  readout in the session bar, and word-pool modes read "155 words &
  calls" (#61)
- Slashed zeros across every copy display, toggleable in Settings ▸
  Display (#62)

`CURRENT_PROJECT_VERSION` is already bumped to 16 (marketing version
stays 1.1). Nothing to edit — just build and ship.

## Ship it

    git checkout main && git pull
    source tools/asc-auth.sh
    ./tools/upload-testflight.sh

The script archives Release, uploads, waits for processing, assigns the
same testers as build 15, and submits for beta review (fast on the
already-approved 1.1 train).

## Add it to the Discord group

The external group + public link exist from build 15; the new build just
needs adding:

    python3 tools/asc-api.py groups          # note the "Discord" group id
    python3 tools/asc-api.py notify <id>     # add build 16 to it

The public join link doesn't change, so no new Discord announcement is
required — testers get TestFlight's own update notice. If an announcement
is wanted anyway, dispatch `.github/workflows/discord-release.yml` with
the existing `testflight_link`.

## Companion release

Android 1.4 (versionCode 5) ships the same round plus the CW decoder +
Short Stories port via `android-release.yml` on the Android repo — the
remote session handles that; nothing to do from the Mac.
