# Audit handoff

Notes for whoever picks this up next — human or a fresh Claude session with no
memory of the audit. Rewritten 2026-09-01 against `f9f9aca` on `main`.

This file is scaffolding, not documentation. **Delete it once the list below is
done or triaged into issues.** Nothing in the codebase refers to it.

Read `CLAUDE.md` first regardless — the two-ports rule and the vendored-decoder
rule apply to every item here.

## What this was

A six-part review of the repo: iOS practices, Android practices, LLM/agent
setup, tests, formatting and linting, and code seams/duplication. Everything
actionable from the original list has now merged, in four PRs:

| PR | What landed |
|---|---|
| [#113](https://github.com/N9HO/another-morse-trainer/pull/113) | The security fix, two Android state bugs, the allocation fix on both ports, the phantom WPM band, the privacy manifest, R8 — plus the CI to verify R8 |
| [#114](https://github.com/N9HO/another-morse-trainer/pull/114) | Triage bot: closed-issue dedup, duplicate attach, SDK pin — and follow-up **#7**, its tests in CI |
| [#115](https://github.com/N9HO/another-morse-trainer/pull/115) | Follow-up **#8**: Swift concurrency checking, and AGP lint on Android |
| [#116](https://github.com/N9HO/another-morse-trainer/pull/116) | compose-bom 2024.12.01 → 2026.06.01, which lint surfaced |

Their descriptions carry the detail; it is not repeated here.

## The thing that changed most: CI can now check things

The audit found a repo whose CI compiled two apps and verified almost nothing
about either. It now runs **seven jobs**, each covering something that
previously had none:

    ios.yml           MorseKit logic checks      418-check harness
                      Build MorseTrainer (iOS)   xcodebuild
    android-ci.yml    Build debug APK            unit tests + assembleDebug
                      Android lint               AGP lint, exclusions in app/lint.xml
                      Build release APK          the ONLY job that runs R8
                      Release smoke test         emulator: launches it, rotates it
    triage-bot.yml    Triage bot tests           40 pytest tests, on Linux

`merge-gate.yml` is the single required check; it derives what a PR needs from
the PR's own diff. Read its header before touching any `paths:` filter.

**Why this matters more than it looks: there is no Android hardware behind this
repo, and no JDK or Android SDK on the maintainer's machine.** `./gradlew`
cannot be run locally at all. The emulator smoke test's screenshots are the only
way anyone sees this app running, and they are what made the Compose bump
checkable rather than hopeful. Treat that job as the Android equivalent of
"install it and look".

The smoke test also rotates the device and asserts `MainActivity` is not
recreated, via the framework's own `wm_on_create_called` event. That assertion is
built not to pass for the wrong reason: it first confirms the launch emits the
tag at all, and it fails outright if the display did not actually change size. It
was checked against a deliberate negative control — removing `configChanges` made
it fail with exactly the expected output — so it is a check that has been seen to
fail, not only to pass.

Known gap: a fresh install lands on onboarding, so the smoke test covers launch,
the first screen, and rotation of that screen. Home and Settings are one tap
deeper and unexercised. Reaching them needs `adb shell input tap` on fixed
coordinates, which breaks whenever the onboarding copy moves.

## What #8 established, because it shaped the audio work

Follow-up #8 assumed `SWIFT_STRICT_CONCURRENCY` would catch the races it named.
**It does not, at any level.** Measured on clean builds: `targeted` produces zero
new warnings, and even `complete` says nothing about `MIDIInput.onEvent` or the
lock in `KeyerEngine`'s render callback, both of which compile. They are
callbacks from C APIs imported without `@Sendable`, so there is no concurrency
construct to check. `CLAUDE.md` records this.

Both were fixed by hand with the audio-session work — `MIDIInput`'s two callback
`var`s now go through its existing `stateLock`, and `ToneGenerator` uses
`OSAllocatedUnfairLock` like `MorsePlayer` already did. **Neither fix is
protected by anything.** A future edit can reintroduce either and every build
will stay green, so both sites carry a comment saying so.

## The audio-session and audio-focus work

The old items 3 and 4 are done, on both ports.

- **iOS**: `MorseTrainerApp/AudioSession.swift` is now the single owner of the
  shared `AVAudioSession`. The five call sites that each set a category — and
  hand-restored `.playback` on the way out — became claims on a stack, so the
  session falls back to whatever is *still held* rather than to a guess.
  Interruption, route-change and `mediaServicesWereReset` are observed for the
  first time: the Listen loop pauses instead of advancing in silence, and
  `MorsePlayer` rebuilds its engine after a reset.
- **Android**: `AudioFocus.kt` is the app's first-ever focus holder,
  reference-counted across `MorsePlayer`, `SidetoneGenerator` and
  `ListenService`. `BackgroundNoise` is a *listener*, not a holder, on purpose —
  the floor runs on the home screen, and taking focus to browse a menu would be
  wrong.

**One deliberate divergence to be aware of.** iOS uses `.duckOthers` (other audio
turns down); Android requests `AUDIOFOCUS_GAIN` (other audio pauses). That is the
Android idiom for a media-playback foreground service, and ducking would leave
the reported problem half-fixed, since copying Morse under music is the hard
case. It is a real difference between the ports; it is not drift.

Left undone, and worth knowing: Android quiz screens still have no equivalent of
the iOS listen-loop pause. They block on an answer, so they stall rather than run
away, which is why it was left — but a Compose-side pause is still missing.

The Android side was written blind, as everything here must be, and verified only
by CI: all four jobs green, lint unchanged at 0 errors / 29 warnings / 69 hints.
Note that **the "28 warnings" this file used to quote was already stale** — `main`
reported 29 before this change; the compose-bom bump in
[#116](https://github.com/N9HO/another-morse-trainer/pull/116) moved it and the
number was never re-read. Read the count off a run, not off this file.

## Rotation no longer restarts practice

`MainActivity` declares `android:configChanges` for orientation, screen size and
layout, density, font scale and UI mode, so Android stops recreating the activity
for any of them — asserted by the smoke test, against a negative control. A single-Activity Compose UI needs no recreation to re-lay-out —
Compose updates `LocalConfiguration` itself, so `isWideScreen()` and the theme
still track the new configuration — and this fixes all ten session screens at
once rather than one at a time. A side benefit: rotation used to blip the
background-noise floor off and on through `onStart`/`onStop`.

The nav state (`route` and the pending `SetupTarget`) is additionally on
`rememberSaveable` with string savers, so *where you were* survives process death
even though the session running there does not. An unrecognised tag restores as
`null`, which falls back to Home rather than crashing on a stale bundle.

**This is deliberately not the migration the audit asked for**, which was
`rememberSaveable`/ViewModels everywhere. It buys the whole rotation fix for two
files instead of twelve, in a codebase nobody can run locally. What it does not
buy is process death, which is item 3 below.

## Streaming synthesis

The audio is no longer pre-rendered. `MorseSynth` (in `MorseKit` and `morsekit`)
walks a segment list a sample at a time, so a passage costs a few hundred
segments instead of ~22 million samples — and on Android that array was also
blocking-written to a `MODE_STATIC` track from the main thread. iOS synthesises
in the existing `AVAudioSourceNode` callback; Android runs one persistent
`MODE_STREAM` track and one feeder thread, the shape `SidetoneGenerator` and
`BackgroundNoise` already used.

Two things to know before touching it:

- **The pileup path still materialises, on purpose.** Several voices summed,
  each with its own pitch, speed, QSB envelope and gain, then band noise and a
  peak normalisation over the finished mix — none decidable one sample ahead.
  Pileups are callsigns and short exchanges, so it is bounded by what a pileup
  *is*. Do not "finish the job" by streaming it.
- **`fixtures/render.json` is what makes this safe to change.** It pins segment
  lengths, total samples, probe values and a whole-signal sum for 8 cases on
  both ports. Regenerate it only when you *intend* the sound to change, and say
  so — otherwise it is the thing standing between a refactor and a silent
  regression nobody can hear in CI.

Moving the maths into MorseKit/morsekit is also what made it testable at all: in
the app targets it was unreachable by `MorseKitCheck` and by any Kotlin test,
which is why it had never been pinned.

Not verified by anything: the `AudioTrack` plumbing on Android, and how any of
it actually sounds. The fixture proves the samples are unchanged; nobody has
heard them.

## Still to do

Ordered by value. Each is self-contained. #7 and #8's tooling, and the two audio
items above, are done; what follows is the remainder.

**1. The punctuation divergence — a product decision, not a code fix.** Android
threads opted-in punctuation through a `studyOrder` ladder
(`TrainerEngine.kt:64,188`, `MorseCode.kt:54`, `Settings.kt:422`); iOS hardcodes
`kochOrder` (`TrainerEngine.swift:174`) and adds punctuation to the active set
immediately. Consequence: unlocking singles→pairs takes **40** characters on
Android and **37** on iOS (`ProgressiveCharacters.kt:128` vs `.swift:110`).
Decide which is intended, then port or remove. Do not "fix" this by making the
ports share code.

**2. Android session state through process death.** Rotation is handled — see
below — but a backgrounded app that Android reclaims still loses whatever
session was running, because every screen holds its tally in plain `remember`
and `Stats.record` only runs from the explicit exits. Ten screens carry that
shape: `QuizScreen`, `TypedQuizScreen`, `HeadCopyScreen`, `StoryScreen`,
`ContestScreen`, `PileupScreen`, `RapidFireScreen`, `SendingPracticeScreen`,
`CodeExamScreen`, `JourneyScreen`. The fix is `rememberSaveable` or a ViewModel
per screen, plus a `Saver` for `Tally` (`QuizScreen.kt:78`), whose `ttrsMs` list
and `charOutcomes` map are both private.

Weigh it against the cost before starting: it is ~12 files touched heavily, and
**nothing here can exercise it** — no local toolchain, no instrumented tests, and
the emulator smoke test never gets past onboarding. CI would compile it and tell
you nothing about whether it works.

**3. Test parity — the mechanism is fixed, the coverage is not.**
`fixtures/timing.json` now exists and both trees read it (`MorseTimingTest.kt`
via `org.json`, `MorseKitCheck/main.swift` via `JSONDecoder`), so timing is
pinned to one set of numbers instead of two hand-copied test files. The drift it
was written to catch is gone: iOS now pins the Farnsworth clamp, which only the
Kotlin side did. `CLAUDE.md` records why sharing data is not a crack in the
two-ports rule.

What remains is coverage, not mechanism. Android's core engine — `TrainerEngine`,
`ProgressiveCharacters`, `CharacterStats`, `PhraseQuiz`, persistence — still has
**no tests**, while the Swift twin has ~200 checks; the 12 Kotlin test classes
cover data tables, the CW decoder and the MIDI parser, none of the engine. And
`CwDecoderTest.kt` is still a line-for-line transcription of
`main.swift:1591-1694`, which is the next fixture to write. Follow the pattern
already there: derive expected values from the spec rather than from either
implementation, or the fixture just records whatever both ports happen to do.

**4. The app target's 42 concurrency warnings.** `SWIFT_STRICT_CONCURRENCY` is
`targeted` on `MorseTrainerApp/`; the package is already at `complete` and
pinned there. Complete checking on the app reported 43 distinct warnings across
10 files — 13 non-`Sendable` captures in `@Sendable` closures, 8
sending-risks-data-races, 15 main-actor isolation violations — concentrated in
`MorsePlayer`, `Haptics`, `SendingDrillView` and `NewsFetcher`. The audio-session
work took it to **42**, by collapsing two `allowBluetooth` deprecations into one;
it added no concurrency warnings, which was checked rather than assumed. Clearing
the rest is the path to Swift 6 language mode. Measure with:

    xcodebuild build -project MorseTrainer.xcodeproj -scheme MorseTrainer \
      -sdk iphonesimulator -destination "generic/platform=iOS Simulator" \
      -derivedDataPath /tmp/dd SWIFT_STRICT_CONCURRENCY=complete \
      CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO

Use a fresh `-derivedDataPath` — an incremental build silently reports a
fraction of the warnings and looks like good news.

**5. What Android lint found.** Currently 0 errors, 29 warnings, 69 hints.
Nothing latent. Worth acting on: `ListenService.kt:239` guards
`stopForeground(STOP_FOREGROUND_REMOVE)` behind `SDK_INT >= N` (API 24) while
`minSdk` *is* 24, so the branch is dead and its `@Suppress("DEPRECATION")`
suppresses nothing. The rest is 23 `UseKtx` and 69 `AutoboxingStateCreation`
(`mutableIntStateOf` over `mutableStateOf`) — decide whether to sweep them or
silence them in `lint.xml`, because leaving them keeps the report noisy.

Lint also flags `activity-compose` 1.9.3, `desugar_jdk_libs` 2.1.3 and the AGP
version. **The AGP one is a wall, not a nit:** compose-bom 2026.08.00 needs AGP
9.1 and compileSdk 37, so the Compose pin cannot move past 2026.06.01 until AGP
9 lands. That is its own piece of work.

**6. Smaller, verified, not urgent.** `Stats.kt:95-97` — `parseRecent` and
`parseChars` are unguarded while `parseHistory` is wrapped in `runCatching`, so
corrupt prefs are an unrecoverable launch crash. `SidetoneGenerator.kt:90` — an
overshoot guard of the form `a > b && b > a`, provably always false;
`BackgroundNoise.kt:119` already does it correctly. `MorseData.kt:186` — a
verbatim dead second copy of all ten story texts that nothing reads
(`MorseDataStories.kt` is the live one). Android has no `strings.xml` at all.
Audio threads on both platforms run at default priority.

## Things not to do

- Do not unify the two ports. No shared module, no KMP, no deduplicating the
  data tables. `CLAUDE.md` is explicit and it is the right call.
- Do not touch `ios/Sources/CWDecoderCore/` or `android/…/morsekit/cw/`. Their
  wrappers are fair game; the vendored files are not. `app/lint.xml` exempts the
  Kotlin one for the same reason.
- Do not restore the version numbers to the docs. They drifted within one
  release last time.
- Do not trust an incremental build when measuring warnings. Twice in this work
  a cached build reported a fraction of the real count and looked like progress.
