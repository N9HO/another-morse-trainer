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
                      Release smoke test         emulator: installs and launches it
    triage-bot.yml    Triage bot tests           40 pytest tests, on Linux

`merge-gate.yml` is the single required check; it derives what a PR needs from
the PR's own diff. Read its header before touching any `paths:` filter.

**Why this matters more than it looks: there is no Android hardware behind this
repo, and no JDK or Android SDK on the maintainer's machine.** `./gradlew`
cannot be run locally at all. The emulator smoke test's screenshots are the only
way anyone sees this app running, and they are what made the Compose bump
checkable rather than hopeful. Treat that job as the Android equivalent of
"install it and look".

Known gap: a fresh install lands on onboarding, so the smoke test covers launch
and the first screen only. Home and Settings are one tap deeper and unexercised.
Reaching them needs `adb shell input tap` on fixed coordinates, which breaks
whenever the onboarding copy moves.

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

Two things that were *not* done and are worth knowing:

- Android quiz screens still have no equivalent of the iOS listen-loop pause.
  They block on an answer, so they stall rather than run away, which is why this
  was left — but a Compose-side pause is still missing.
- `AudioFocus` holds an `AudioFocusRequest` field (API 26) on a minSdk-24 class,
  guarded by `SDK_INT` checks in the repo's existing style. There is no local
  JDK or Android SDK, so **whether AGP lint's `NewApi` accepts that field
  declaration was verified only in CI**, not before the push.

## Still to do

Ordered by value. Each is self-contained. #7 and #8's tooling, and the two audio
items above, are done; what follows is the remainder.

**1. Stream the audio instead of pre-rendering it.** The buffers are sized once
rather than grown, but a long Farnsworth passage is still tens of millions of
samples materialized on the main thread before playback starts. The real fix is
to synthesize in the render callback from the segment list (iOS already keeps a
persistent `AVAudioSourceNode`) or feed a `MODE_STREAM` `AudioTrack` from a
background thread (Android). Until then, Story and Exam modes at slow effective
speeds remain the memory and main-thread hot spot. Two edits, one per tree.

**2. The punctuation divergence — a product decision, not a code fix.** Android
threads opted-in punctuation through a `studyOrder` ladder
(`TrainerEngine.kt:64,188`, `MorseCode.kt:54`, `Settings.kt:422`); iOS hardcodes
`kochOrder` (`TrainerEngine.swift:174`) and adds punctuation to the active set
immediately. Consequence: unlocking singles→pairs takes **40** characters on
Android and **37** on iOS (`ProgressiveCharacters.kt:128` vs `.swift:110`).
Decide which is intended, then port or remove. Do not "fix" this by making the
ports share code.

**3. Android state survival.** Zero `rememberSaveable`, zero ViewModels, zero
`SavedStateHandle` in the app. `MainActivity.kt:135` holds the whole nav state in
plain `remember`, so rotating mid-quiz drops you on Home and loses the session's
stats and streak day (`Stats.record` only runs from the explicit exits). The
Koch ladder survives because `EngineStore.save()` runs per answer — the right
instinct applied to one of the two things worth saving.

**4. Test parity.** Android's core engine — `TrainerEngine`,
`ProgressiveCharacters`, `CharacterStats`, `PhraseQuiz`, persistence — has **no
tests**, while the Swift twin has ~200 checks. And parity is currently kept by
hand-copying test code (`CwDecoderTest.kt` is a line-for-line transcription of
`main.swift:1591-1694`), which has already failed: `MorseTimingTest.kt:35-72`
sweeps all 56 speeds and pins the Farnsworth clamp, and **iOS pins neither**.
A `fixtures/*.json` set consumed by both trees shares *data*, not code, so it
does not violate the two-ports rule. Start with `fixtures/timing.json`.

**5. The app target's 42 concurrency warnings.** `SWIFT_STRICT_CONCURRENCY` is
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

**6. What Android lint found.** Its first run: 0 errors, 28 warnings, 69 hints.
Nothing latent. Worth acting on: `ListenService.kt:196` guards
`stopForeground(STOP_FOREGROUND_REMOVE)` behind `SDK_INT >= N` (API 24) while
`minSdk` *is* 24, so the branch is dead and its `@Suppress("DEPRECATION")`
suppresses nothing. The rest is 23 `UseKtx` and 69 `AutoboxingStateCreation`
(`mutableIntStateOf` over `mutableStateOf`) — decide whether to sweep them or
silence them in `lint.xml`, because leaving them keeps the report noisy.

Lint also flags `activity-compose` 1.9.3, `desugar_jdk_libs` 2.1.3 and the AGP
version. **The AGP one is a wall, not a nit:** compose-bom 2026.08.00 needs AGP
9.1 and compileSdk 37, so the Compose pin cannot move past 2026.06.01 until AGP
9 lands. That is its own piece of work.

**7. Smaller, verified, not urgent.** `Stats.kt:95-97` — `parseRecent` and
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
