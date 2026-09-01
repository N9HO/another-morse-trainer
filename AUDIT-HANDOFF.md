# Audit handoff

Notes for whoever picks this up next — human or a fresh Claude session with no
memory of the audit. Written 2026-09-01 against `8dd8674`.

This file is scaffolding, not documentation. **Delete it once the follow-ups
below are done or triaged into issues.** Nothing in the codebase refers to it.

## What this was

A six-part review of the repo: iOS practices, Android practices, LLM/agent
setup, tests, formatting and linting, and code seams/duplication. The branch
`audit-fixes-1-5` carries the fixes that were worth doing immediately. Everything
else is listed under "Not done" below, with enough detail to act on without
re-running the audit.

Read `CLAUDE.md` first regardless — the two-ports rule and the vendored-decoder
rule both still apply to every item here.

## What changed on this branch

**Security.** `.github/workflows/claude.yml` gated only on the substring
`@claude` on a public repo, with `contents: write` and the API key in scope. Any
GitHub account could start it; and because `ios/tools/discord_triage` files
issues built from Discord text with the invite published in the README, so could
anyone in the Discord, with no GitHub account at all. Now gated on
`author_association` in `OWNER`/`MEMBER`/`COLLABORATOR`, plus `timeout-minutes`
and a `concurrency` group. `id-token: write` was dropped — nothing used it.

**Android — progress loss on upgrade (found while fixing something smaller).**
`EngineStore.decode` read `obj.getString("exposed")`, which *throws* when the key
is absent. `load()` wraps `decode` in `runCatching { }.getOrNull()`, and
`characters()` cannot tell a decode failure from "never saved" — it answers both
by reseeding from proficiency. So any save written before exposure tracking
existed was silently discarded, taking the learner's whole Koch ladder with it.
Now `obj.has("exposed")` decides, and absent stays absent.

**Android — snapshot aliasing.** `TrainerEngine.snapshot` did
`stats.values.toList()`. Swift's `CharacterStats` is a `struct`, so the Swift
twin's `Array(stats.values)` copies by value; the Kotlin port is a class holding
a `MutableList`, so the "snapshot" kept aliasing live state and every later
`record()` mutated it. Added `CharacterStats.copy()`; both `snapshot` and
`restore` now copy. This is the generic hazard when porting value-type code to a
reference-type language — worth checking for elsewhere.

**Android — exposed-vs-absent.** `Snapshot.exposedCharacters` is now
`Set<Char>?`, `null` meaning "predates the field". It was `emptySet()` with an
`isNotEmpty()` check, which conflated "old save" with "genuine beginner" and
skipped the growing-choices onboarding on a new learner's first relaunch.

**Both — whole-transmission render.** Both players built an entire transmission
into one array before playing a note. Android boxed every sample into
`ArrayList<Float>` (hundreds of MB at Farnsworth 33/8, then a `MODE_STATIC`
`AudioTrack` request for the same size, uncaught); iOS grew a `[Float]` with
per-segment `reserveCapacity`, peaking near twice its final size. Both now size
the buffer once and fill by index, with `max(0, …)` on both counts so the sizing
and fill passes cannot disagree. **This reduces the allocation; it does not
remove it.** See "Not done" #1.

**iOS — phantom WPM band.** `SessionHistory.wpmBandSummaries` filtered
`attempts > 0`; the Kotlin twin has filtered `attempts > 0 && wpm > 0` since it
gained the field, with a comment explaining why. Sessions persisted before the
speed field carry `characterWPM == 0` and formed a fake "0-4" band at the head
of the chart. Fixed, and pinned by two new checks in `MorseKitCheck` (verified
to go red without the fix).

**iOS — privacy manifest.** Added `ios/MorseTrainerApp/PrivacyInfo.xcprivacy`
declaring `NSPrivacyAccessedAPICategoryUserDefaults` (CA92.1) and
`…SystemBootTime` (35F9.1). Without it App Store Connect rejects uploads with
ITMS-91053. No `.pbxproj` edit was needed: `MorseTrainerApp` is a
`PBXFileSystemSynchronizedRootGroup`, so files dropped there are bundled
automatically. File timestamp / disk space / active keyboard APIs were checked
for and are not used.

**Android — R8.** Release builds had `isMinifyEnabled = false` while bundling
`material-icons-extended` (thousands of generated icons; the app uses ~30).
Now minify + `shrinkResources` with a near-empty `proguard-rules.pro` that keeps
the vendored `morsekit.cw` package un-renamed.

**Docs.** The version numbers in `CLAUDE.md`, `README.md`, and
`android-release.yml` all said iOS build 24 / Android 16 / 1.12.1; actual was 25
and 17 / 1.12.2. Rather than correcting three copies that will drift again, the
numbers are gone and each file points at the source of truth. Also fixed a
`README.md` table row that credited `ios-v*` to `discord-release.yml` — it fires
`ios-release.yml`; `discord-release.yml` is `workflow_run`-triggered, changed
deliberately in `ced64d1`.

## Verification status — read this before trusting the branch

| Change | Verified how |
|---|---|
| iOS MorseKit + harness | `swift build && swift run MorseKitCheck` — **418 checks pass** |
| The new D5 checks | Confirmed **red** with the fix reverted, green with it |
| iOS app target (incl. `MorsePlayer.swift`) | `xcodebuild` simulator build — **BUILD SUCCEEDED** |
| Privacy manifest actually bundles | Found at `MorseTrainer.app/PrivacyInfo.xcprivacy` in the build output, and `plutil`-valid there |
| `claude.yml`, `android-release.yml` | YAML parses; job keys asserted |
| **All Kotlin changes** | **NOT COMPILED.** No JDK and no Android SDK on the machine this was done on |
| **R8 / `isMinifyEnabled = true`** | **NOT BUILT.** Highest-risk change here |

`swift build` compiles only the SwiftPM package. **It does not compile
`ios/MorseTrainerApp/`** — an agent that edits the app target, runs the fast
loop, sees green, and reports success has verified nothing. Use the `xcodebuild`
line in `CLAUDE.md` for anything under `MorseTrainerApp/`.

Before merging, someone with a JDK + Android SDK must run:

```bash
cd android && ./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleRelease
```

`assembleDebug` is not sufficient — debug builds are not minified, so it cannot
exercise R8. If R8 misbehaves, reverting `isMinifyEnabled` is independent of
every other change on the branch.

## Not done — the follow-up list

Ordered by value. Each is self-contained.

**1. Stream the audio instead of pre-rendering it.** The buffers are now sized
once rather than grown, but a long Farnsworth passage is still tens of millions
of samples materialized on the main thread before playback starts. The real fix
is to synthesize in the render callback from the segment list (iOS already keeps
a persistent `AVAudioSourceNode`) or feed a `MODE_STREAM` `AudioTrack` from a
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

**3. iOS audio-session hardening.** There is no `AVAudioSession` interruption,
route-change, or `mediaServicesWereReset` handling anywhere. Take a call during
Listen mode and the loop keeps advancing, silently, burning the session; unplug
headphones and it does not pause; after a media-services reset the engine is
dead with no recovery. Also: five call sites set conflicting categories with no
owner — `MorsePlayer.swift:106`, `VoiceRecognizer.swift:163,271`,
`CWDecoderEngine.swift:64,78`, `KeyerEngine.swift:95`. One coordinator that owns
category/mode/options is the fix.

**4. Android audio focus.** Nothing in the app ever requests or abandons audio
focus — `grep` for `requestAudioFocus` returns nothing — while running a
`FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` service. The app talks over music and
through calls. `BackgroundNoise` is already a process-wide singleton and is the
natural owner.

**5. Android state survival.** Zero `rememberSaveable`, zero ViewModels, zero
`SavedStateHandle` in the app. `MainActivity.kt:135` holds the whole nav state in
plain `remember`, so rotating mid-quiz drops you on Home and loses the session's
stats and streak day (`Stats.record` only runs from the explicit exits). The
Koch ladder survives because `EngineStore.save()` runs per answer — the right
instinct applied to one of the two things worth saving.

**6. Test parity.** Android's core engine — `TrainerEngine`,
`ProgressiveCharacters`, `CharacterStats`, `PhraseQuiz`, persistence — has **no
tests**, while the Swift twin has ~200 checks. And parity is currently kept by
hand-copying test code (`CwDecoderTest.kt` is a line-for-line transcription of
`main.swift:1591-1694`), which has already failed: `MorseTimingTest.kt:35-72`
sweeps all 56 speeds and pins the Farnsworth clamp, and **iOS pins neither**.
A `fixtures/*.json` set consumed by both trees shares *data*, not code, so it
does not violate the two-ports rule. Start with `fixtures/timing.json`.

**7. Cheap CI wins.** `ios/tools/discord_triage/` has 36 passing pytest tests
that **have never run in CI**, and it sits inside the `ios/**` path filter — so a
Python-only PR spins up two `macos-15` runners at 10x billing, builds Swift, and
runs zero Python tests. Add a path-filtered pytest job; exclude `ios/tools/**`
from `ios.yml`. Also add `pytest` to that directory's `requirements.txt`, which
`CLAUDE.md` already tells people to run. Remember: a path-filtered job reports no
status when skipped, so route anything new through `merge-gate.yml` rather than
making it a required check.

**8. Compiler strictness — the highest-value tooling gap.** The code is
genuinely clean (a conservative SwiftFormat pass finds three defects in 20,766
lines), so formatters are not the win here. What is off: Swift builds in language
mode 5 with no concurrency checking, and there are real races the compiler would
catch — an unsynchronized `MIDIInput.onEvent` closure written on the main actor
and read on the CoreMIDI thread, and an `NSLock` taken inside an
`AVAudioSourceNode` render callback (`KeyerEngine.swift:260`) where
`MorsePlayer` already knows to use `OSAllocatedUnfairLock`. Start with
`SWIFT_STRICT_CONCURRENCY = targeted`, read the warnings, then decide about
Swift 6. On the Android side, `./gradlew :app:lint` runs nowhere; AGP ships lint
free and it is the best bug-per-line-of-config available. Any Kotlin tool must
exclude `morsekit/cw/`.

**9. Smaller, verified, not urgent.** `Stats.kt:95-97` — `parseRecent` and
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
  wrappers are fair game; the vendored files are not.
- Do not restore the version numbers to the docs. They drifted within one
  release last time.
