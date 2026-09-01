# Working in this repository

A monorepo holding two independent apps. Read this before making changes that
span directories.

## Layout

    ios/        SwiftUI app + SwiftPM package (Xcode project, macOS to build)
    android/    Kotlin + Compose app (Gradle root; settings.gradle.kts lives here)
    fixtures/   Shared test *data*, read by both trees. Not code — see below.

Nothing at the repository root builds anything. `cd ios` or `cd android` first.

## The rule that matters most: two ports, not one

`ios/Sources/MorseKit/` (Swift) and
`android/app/src/main/java/app/anothermorsetrainer/morsekit/` (Kotlin) are
**parallel ports of the same training logic, kept as two independent trees.**

- Do **not** unify them — no Kotlin Multiplatform, no shared module, no
  deduplicating the data tables. That is a separate project with real risk.
- A fix that applies to both is **two** edits, one per tree, in the language and
  idiom of that tree.
- Changing one side does not obligate you to change the other in the same
  change, but say which side you touched.

The one thing the two trees *do* share is `fixtures/`: JSON files of expected
values, each tree reading them in its own idiom (`JSONDecoder` in the Swift
harness, `org.json` in the Kotlin tests). That is deliberate and is not a crack
in the rule — it shares **data**, never behaviour, so neither port can start
depending on the other's code.

It exists because the alternative failed. Parity used to be kept by hand-copying
test code between the trees, and the copies drifted: `MorseTimingTest.kt` swept
all 56 speeds and pinned the Farnsworth clamp while the Swift twin pinned
neither, so the two ports had different guarantees and nothing said so. A
fixture pins both to the same numbers. Expected values are derived from the
documented formulas independently of either implementation, so both ports
drifting the same way still fails.

`fixtures/**` is in the `paths:` filter of *both* workflows and in
`merge-gate.yml`'s detection for both platforms — a fixture change has to build
both apps, or it is only half checked.

## Do not touch the vendored decoder

`ios/Sources/CWDecoderCore/` (C99) and `android/…/morsekit/cw/` (its Kotlin
port) are kept byte-identical to a firmware copy. Each has a `PROVENANCE.md`
next to the code it documents, and `CWDecoderCore` also has its own `LICENSE`.
Don't reformat, relicense, tidy, or relocate them or those files.

## Building and testing

```bash
cd ios                                    # needs macOS + Xcode
swift build && swift run MorseKitCheck    # logic harness; no Xcode required
xcodebuild build -project MorseTrainer.xcodeproj -scheme MorseTrainer \
  -sdk iphonesimulator -destination "generic/platform=iOS Simulator" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
```

Swift concurrency checking is set at two different levels on purpose:

- `Sources/` (the SwiftPM package) is on **complete** checking, via
  `.enableUpcomingFeature("StrictConcurrency")` in `Package.swift`. It was
  already clean under it, so this pins that rather than describing an ambition —
  a new warning there is a regression, not a backlog item.
- `MorseTrainerApp/` (the Xcode target) is on **targeted**
  (`SWIFT_STRICT_CONCURRENCY` in the project file). Complete checking reports 43
  warnings there; going up a level is real work, not a setting change.

Know what this does *not* buy you: neither level diagnoses a race in a callback
from a C API, because there is no concurrency construct for it to check. The
CoreMIDI read block in `MIDIInput.swift` and the `AVAudioSourceNode` render
block in `KeyerEngine.swift` are both invisible to it at *complete*. Those need
reading, not compiling.

```bash
cd android                                # needs JDK 17 + Android SDK
./gradlew :app:testDebugUnitTest          # 12 test classes
./gradlew :app:lint                       # AGP lint; exclusions in app/lint.xml
./gradlew :app:assembleDebug
```

The triage bot has its own suite, run in CI by `triage-bot.yml`:

```bash
cd ios/tools/discord_triage
pip install -r requirements-dev.txt   # requirements.txt + pytest
pytest
```

`ios/tools/**` is excluded from `ios.yml` — nothing under it reaches the Xcode
build — so a change there runs the Python suite on Linux instead of two
`macos-15` runners.

## Versions, tags, CI

- **The two apps are not coupled.** Independent version numbers, independent
  release cadences. Never bump one "to match" the other. The current numbers
  are not repeated here on purpose — they went stale the first release after
  they were written. Read them from the source of truth:
  - iOS: `CURRENT_PROJECT_VERSION` / `MARKETING_VERSION` in
    `ios/MorseTrainer.xcodeproj/project.pbxproj`
  - Android: `versionCode` / `versionName` in `android/app/build.gradle.kts`
- Release tags are namespaced: **`ios-v*`** and **`android-v*`**. A bare `v*`
  tag fires nothing.
- `ios.yml` and `android-ci.yml` are path-filtered to their own subtree. If you
  add a workflow, give it a `paths:` filter too — the iOS jobs run on `macos-15`
  at 10x Linux billing.
- Changing a path filter? Remember a *skipped* job reports no status, so a
  path-filtered job must never be a required status check on `main`.

## Commit-message issue references

The Android app was merged in from a separate repository on 2026-08-31. **In
commits from the Android lineage, `#N` means the archived
`another-morse-trainer-android` repo, not this one** — see the History note in
`README.md`. Anything you write now refers to this repo.
