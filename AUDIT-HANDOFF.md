# Audit handoff

Notes for whoever picks this up next — human, or a fresh Claude session with no
memory of any of it. Rewritten 2026-09-01 against `fb1c13d` on `main`.

This file is scaffolding, not documentation. **Delete it once the list below is
done or triaged into issues.** Nothing in the codebase refers to it.

Read `CLAUDE.md` first regardless: the two-ports rule and the vendored-decoder
rule apply to everything here.

## The fact that shapes every decision in this repo

**There is no Android hardware behind this project, and no JDK or Android SDK on
the maintainer's machine.** `./gradlew` cannot be run locally at all. Every
Android change is written blind and verified only by CI.

iOS is better off: `xcodebuild` and `swift run MorseKitCheck` both work locally.
But nobody can *hear* either app, and no test on either side plays audio.

Everything in the next section exists because of that.

## How to verify things here

This is the part worth reading even if you skip the rest.

**1. Put shared expectations in `fixtures/`, as data.** Four exist:

    fixtures/timing.json    WPM → dit/dah/gap durations, PARIS, Farnsworth
    fixtures/render.json    segment lengths, probe samples, whole-signal sums
    fixtures/ladder.json    Koch order, punctuation teaching order, study order
    fixtures/mastery.json   attempt sequences → accuracy, median, isMastered

Both trees read them in their own idiom — `JSONDecoder` in
`MorseKitCheck/main.swift`, `org.json` in the Kotlin tests. That shares *data*,
never behaviour, so the two-ports rule holds. `CLAUDE.md` says why.

**Derive fixture values from the documented spec, not by running either port.**
A fixture captured from the code only records what the code already does, and
both ports drifting the same way would still pass.

**2. Run a negative control.** Perturb the fixture, confirm the thing goes red,
then revert. Every fixture here has been through this, and it is not ceremony:

- **Gradle prints test names only on failure**, so a green build does not
  distinguish "passed" from "never executed". A control is the only proof a new
  test class runs at all.
- A control can itself be broken. One attempt here failed to *compile*, so every
  job went red having run no tests — which from the outside looks exactly like a
  successful control. Check *which* job failed and *which* test named itself.
- `git revert A..B` excludes `A`. Reverting two control commits with
  `HEAD~1..HEAD` silently restores only one.

**3. Anything outside `ios/` and `android/` needs wiring into CI by hand.** Both
workflows are path-filtered and `merge-gate.yml` derives its required checks from
the diff. Twice a new file was added that triggered *no* workflow and was
required by *no* check — `.github/scripts/android-smoke.sh`, then `fixtures/`.
Both are wired now; the next one will not be.

**4. Measure iOS concurrency warnings against a clean worktree at `HEAD`.** A
bare count means nothing, and an incremental build under-reports badly. Build
the baseline in `git worktree` and diff the two warning lists.

## What has merged

| PR | |
|---|---|
| [#113](https://github.com/N9HO/another-morse-trainer/pull/113)–[#116](https://github.com/N9HO/another-morse-trainer/pull/116) | The original audit list: security fix, Android state bugs, allocation fix, privacy manifest, R8, triage-bot fixes, concurrency checking, AGP lint, compose-bom bump |
| [#118](https://github.com/N9HO/another-morse-trainer/pull/118) | `AudioSession` owns the iOS session as a claim stack; `AudioFocus` is Android's first focus holder; the two races no compiler will ever flag |
| [#119](https://github.com/N9HO/another-morse-trainer/pull/119) | `android:configChanges` — rotation no longer restarts practice, asserted in the emulator |
| [#120](https://github.com/N9HO/another-morse-trainer/pull/120) | `fixtures/timing.json` — timing pinned on both ports, replacing hand-copied test code |
| [#121](https://github.com/N9HO/another-morse-trainer/pull/121) | `MorseSynth` — streaming synthesis, no more ~22M samples on the main thread |
| [#122](https://github.com/N9HO/another-morse-trainer/pull/122) | Punctuation taught through the ladder on both ports |
| [#123](https://github.com/N9HO/another-morse-trainer/pull/123) | Corrupt-prefs launch crash fixed; `CharacterStats` and `TrainerEngine` get their first tests |

CI runs seven jobs. `merge-gate.yml` is the single required check and derives
what a PR needs from its own diff — read its header before touching any `paths:`
filter, and remember a skipped job reports no status.

The Android emulator smoke test installs the R8 release APK, launches it,
rotates it, and asserts `MainActivity` is not recreated. It is the only thing
that ever runs this app where anyone can see it. A fresh install lands on
onboarding, so it covers launch and that one screen; going deeper needs
`adb shell input tap` on fixed coordinates, which breaks when the copy moves.

## Standing traps in this code

- **The pileup mixer still materialises its buffer, on both ports, on purpose.**
  Voices summed with per-voice pitch, speed, QSB and gain, then band noise and a
  peak normalisation over the finished mix — none decidable a sample ahead. Do
  not "finish" the streaming rewrite by changing it.
- **Two hand-fixed races are protected by nothing.** `MIDIInput`'s callback
  `var`s behind `stateLock`, and `ToneGenerator`'s `OSAllocatedUnfairLock`. Both
  are callbacks from C APIs imported without `@Sendable`, so *no* level of
  concurrency checking sees them. Reintroduce either and every build stays green.
  Both sites carry a comment saying so.
- **Nobody has heard the streaming audio player.** The fixture proves the samples
  are unchanged and CI proves it launches. The mid-tone cross-fade is new
  behaviour no test covers. Story or Code Exam at a slow effective speed is the
  case to listen to.

## Still to do

Ordered by value. Each is self-contained.

**1. Finish test parity.** 17 Kotlin test classes now exist, against 433 harness
checks on the Swift side. Still uncovered on the Kotlin side:
`ProgressiveCharacters` beyond the ladder unlock, `PhraseQuiz`, and persistence
(`EngineStore` / snapshot round-trips — the two ports do **not** share a
serialisation format, so that is per-tree work, not a fixture).

`CwDecoderTest.kt` is still a line-for-line transcription of
`main.swift:1591-1694`. That is the obvious next fixture, and the last remaining
instance of the copied-test-code habit that `fixtures/` was built to replace.

**2. Android session state through process death.** Rotation is handled; a
backgrounded app that Android reclaims still loses the running session, because
every screen holds its tally in plain `remember` and `Stats.record` only runs
from the explicit exits. Ten screens carry that shape: `QuizScreen`,
`TypedQuizScreen`, `HeadCopyScreen`, `StoryScreen`, `ContestScreen`,
`PileupScreen`, `RapidFireScreen`, `SendingPracticeScreen`, `CodeExamScreen`,
`JourneyScreen`. The fix is `rememberSaveable` or a ViewModel per screen, plus a
`Saver` for `Tally` (`QuizScreen.kt:78`), whose `ttrsMs` list and `charOutcomes`
map are both private.

**Weigh it against the cost first.** It is ~12 files touched heavily, and
nothing here can exercise it: no local toolchain, no instrumented tests, and the
smoke test never gets past onboarding. CI would compile it and tell you nothing
about whether it works. That is why rotation was fixed with a manifest attribute
instead — read #119 before deciding this is worth it.

**3. The app target's 42 concurrency warnings.** `SWIFT_STRICT_CONCURRENCY` is
`targeted` on `MorseTrainerApp/`; the package is already at `complete` and pinned
there. Complete checking on the app reports 42 warnings across 10 files —
non-`Sendable` captures in `@Sendable` closures, sending-risks-data-races, and
main-actor isolation violations — concentrated in `MorsePlayer`, `Haptics`,
`SendingDrillView` and `NewsFetcher`. Clearing them is the path to Swift 6
language mode. Measure with:

    xcodebuild build -project MorseTrainer.xcodeproj -scheme MorseTrainer \
      -sdk iphonesimulator -destination "generic/platform=iOS Simulator" \
      -derivedDataPath /tmp/dd SWIFT_STRICT_CONCURRENCY=complete \
      CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO

Fresh `-derivedDataPath`, and diff against a clean worktree at `HEAD`.

**4. What Android lint found.** Currently 0 errors, 29 warnings, 69 hints — read
the count off a run, not off this file; the number quoted here was stale once
already. Worth acting on: `ListenService.kt:239` guards
`stopForeground(STOP_FOREGROUND_REMOVE)` behind `SDK_INT >= N` (API 24) while
`minSdk` *is* 24, so the branch is dead and its `@Suppress("DEPRECATION")`
suppresses nothing. The rest is `UseKtx` and `AutoboxingStateCreation`
(`mutableIntStateOf` over `mutableStateOf`) — sweep them or silence them in
`lint.xml`, because leaving them keeps the report noisy.

Lint also flags `activity-compose`, `desugar_jdk_libs` and the AGP version.
**The AGP one is a wall, not a nit:** compose-bom 2026.08.00 needs AGP 9.1 and
compileSdk 37, so the Compose pin cannot move past 2026.06.01 until AGP 9 lands.
Its own piece of work.

**5. Smaller, verified, not urgent.** `SidetoneGenerator.kt:90` — an overshoot
guard of the form `a > b && b > a`, provably always false; `BackgroundNoise.kt`
already does it correctly. `MorseData.kt:186` — a verbatim dead second copy of
all ten story texts that nothing reads (`MorseDataStories.kt` is the live one).
Android has no `strings.xml` at all. Audio threads on both platforms run at
default priority.

## Open questions for the maintainer

Neither is a bug to be fixed unasked — both are product calls.

- **Opting out of an already-earned punctuation mark does not remove it from the
  drill.** `removeActiveCharacter` exists on both ports and is called by neither.
  This was ported deliberately in #122 to keep the two sides identical; changing
  it would open a fresh divergence rather than close one.
- **Android pauses other audio (`AUDIOFOCUS_GAIN`) where iOS ducks it
  (`.duckOthers`).** A deliberate difference from #118: the Android idiom for a
  media-playback foreground service, and ducking leaves copying-under-music
  half-fixed. Worth confirming it is what you want on both.

## Things not to do

- Do not unify the two ports. No shared module, no KMP, no deduplicating the data
  tables. `CLAUDE.md` is explicit and it is the right call. `fixtures/` is data,
  which is why it is allowed.
- Do not touch `ios/Sources/CWDecoderCore/` or `android/…/morsekit/cw/`. Their
  wrappers are fair game; the vendored files are not. `app/lint.xml` exempts the
  Kotlin one for the same reason.
- Do not restore version numbers to the docs. They drifted within one release
  last time. Read them from `project.pbxproj` and `build.gradle.kts`.
- Do not trust an incremental build when measuring warnings, or a green Gradle
  build as proof a test ran.
- Do not regenerate a fixture to make a failing test pass. Regenerate only when
  you *intend* the behaviour to change, and say so in the commit.
