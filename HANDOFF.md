# Handoff: upload build 19 to TestFlight (Mac, any clone)

Delete this file (its own commit) once build 19 is distributed.

Build 19 supersedes build 18, which supersedes build 17 — neither was
uploaded, so everything in both handoffs ships here too. Those lists are
repeated below.

## What build 19 is

A BLE MIDI key can reach the app at all (#81):

- iOS will not hand a Bluetooth MIDI key to *any* app until something
  connects it as a MIDI device, and pairing it in Settings > Bluetooth
  does not do that — CoreMIDI keeps reporting zero sources. The app had
  no way to do it, so a key that read "Connected" in Settings was
  invisible in both the Vail panel and Sending Practice. Both now offer
  iOS's own Bluetooth MIDI browser (`CABTMIDICentralViewController`), and
  re-scan when it closes. The Android port has always done the
  equivalent in `BleMidi.kt`
- The Vail panel's adapter row reported only *output* destinations whose
  name matched a Vail Adapter. A BLE MIDI key has no such destination, so
  it read "No MIDI adapter" even when it was keying fine. The row now
  names whatever source is actually attached, and Wake re-scans inputs
  as well as re-broadcasting the adapter wake
- Sending Practice separates "MIDI unavailable" (setup failed — a fault)
  from "no hardware key" (nothing connected yet — not one), instead of
  showing the fault message for both
- One CoreMIDI packet can hold several messages, and a BLE MIDI key
  batches them into one radio burst. Only the first three bytes were
  read, so a key-up sharing a burst with its key-down was dropped and
  the key stuck down. The whole packet is now walked, running status and
  interleaved real-time bytes included, in MorseKit's `MIDIKeyParser`
  where MorseKitCheck covers it. The Android repo carries the twin fix

...and everything build 18 carried:

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

`CURRENT_PROJECT_VERSION` is already bumped to 19 (marketing version
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
