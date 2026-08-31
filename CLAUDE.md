# Working in this repository

A monorepo holding two independent apps. Read this before making changes that
span directories.

## Layout

    ios/        SwiftUI app + SwiftPM package (Xcode project, macOS to build)
    android/    Kotlin + Compose app (Gradle root; settings.gradle.kts lives here)

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

```bash
cd android                                # needs JDK 17 + Android SDK
./gradlew :app:testDebugUnitTest          # 12 test classes
./gradlew :app:assembleDebug
```

The triage bot has its own suite: `cd ios/tools/discord_triage && pytest`.

## Versions, tags, CI

- **The two apps are not coupled.** Independent version numbers, independent
  release cadences. iOS: build 24. Android: versionCode 16 / 1.12.1. Never bump
  one "to match" the other.
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
