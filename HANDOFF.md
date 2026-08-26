# Handoff: test the recovered CW decoder on this Mac

You are a local Claude Code session with Xcode. You're picking up work that
is CI-green but has never been run by a human or an agent — read this, run
the test plan, fix what you find, and report to the user. This file must not
merge to main: delete it (its own commit) once testing is done.

## What this branch is

`claude/cw-decoder-safeguard-2v6oo5` is a recovery branch. A previous
session was killed mid-task by a safety-filter false positive; a remote
Linux session rebuilt the lost tail of its work. History: the predecessor
branch `claude/morse-features-stories-waxype` (Short Stories features +
build prep, all previously CI-green) + a merge of main + two recovery
commits (`8640cc1` vendored core, `9bf1043` app wiring).

What the recovery commits contain:

- `Sources/CWDecoderCore/` — the Carrier Wave firmware's CW audio decoder:
  6 C99 files vendored **byte-identical** from
  `github.com/CarrierWaveApp/carrier_wave` @ `b00268c9` (branch
  `cw-decode-usb`). `PROVENANCE.md` there records per-file SHA-256s.
  Hard rule: never patch these files in place — core changes go upstream;
  wrapper-level fixes belong in `CWDecoderEngine.swift`.
- SPM target `CWDecoderCore` plus a deterministic audio bench appended to
  `Sources/MorseKitCheck/main.swift` (section "CW decoder core (vendored)"):
  renders hard-keyed CW as PCM and asserts decode through the real engine.
- App wiring: `MorseTrainerApp/CWDecoderEngine.swift` (mic tap → int16 →
  C core on the tap thread, telemetry hops to the main actor once per
  buffer via `CoreBox`), `CWDecoderView.swift` (sheet UI),
  a waveform button + sheet in `IntroView`'s top bar (between Vail and
  the book icon), the bridging-header import, and an extended
  `NSMicrophoneUsageDescription` in `Config/Info.plist`.

CI is green on this head — both the MorseKitCheck job and the iOS
simulator build:
https://github.com/N9HO/another-morse-trainer/actions/runs/32994920557
But CI never *runs* the app, and the authoring session had no macOS and
could not execute anything. Treat the Swift app code as
reviewed-but-never-executed; you are its first real run.

## Test plan

1. `swift run MorseKitCheck` — everything green, including the six checks
   under "CW decoder core (vendored)".
2. Build for the simulator (CI's invocation works):
   `xcodebuild build -project MorseTrainer.xcodeproj -scheme MorseTrainer
   -configuration Debug -sdk iphonesimulator -destination
   "generic/platform=iOS Simulator" CODE_SIGNING_ALLOWED=NO
   CODE_SIGNING_REQUIRED=NO`.
   Two warnings are expected and fine: the `recordPermission` deprecation
   (matches VoiceRecognizer's existing usage) and `.onChange(of:)`
   one-parameter form (min target is iOS 16). Errors are not.
3. Boot a simulator, install + launch, and drive it: intro screen → tap
   the waveform icon in the top bar → CW Decoder sheet → Start → mic
   permission prompt (its text should mention voice answers *and* the CW
   decoder). Screenshot the sheet for the user.
4. End-to-end audio loop — the real test. The simulator's mic is the
   Mac's default input. Lift `renderCW` from the bench (bottom of
   `Sources/MorseKitCheck/main.swift`) into a scratch script that writes
   a WAV (700 Hz, 20–25 WPM, high amplitude, e.g. "CQ CQ DE N9HO"), then
   `afplay` it with the volume up while the decoder listens. Expect the
   transcript to show the message (ambient-noise copy errors are fine),
   a WPM readout near the truth, and ~700 Hz. A silent transcript is a
   failure to investigate, starting with the notes below.
5. Layout check: on an iPhone SE-class simulator (375 pt wide), the intro
   top bar (Vail capsule + 5 icons) must not clip or wrap.

## Flagged as unverifiable from Linux — check these deliberately

- If Start appears to do nothing: `beginListening()` bails silently when
  audio-session setup or the input format guard fails (mirrors
  VoiceRecognizer). If you hit that on the simulator, add a short status
  line to the UI surfacing the failure — don't just paper over the
  simulator case.
- Threading contract in `CWDecoderEngine.swift`: the core is touched only
  on the tap thread while running, created/destroyed only while stopped.
  Keep any fix inside that shape.
- If the seeded-noise bench check ever fails on your toolchain, widen its
  margin (lower noise amplitude or looser tolerance) — never delete it.

## Reporting & follow-ups

- Fix what you find, commit with clear messages, and push to THIS branch:
  `git push origin claude/cw-decoder-safeguard-2v6oo5`.
- The user tests on a real iPhone themselves — leave device-only topics
  (signing, real-mic behavior, TestFlight) as notes, not guesses.
- No PR exists yet; the user decides when to open one.
- Done testing → report results to the user, then delete this file.
