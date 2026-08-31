# Another Morse Trainer

Learn to copy Morse code (CW) by ear with the Koch method. This repository holds
both apps:

| | | |
|---|---|---|
| [**`ios/`**](ios/) | SwiftUI + SwiftPM + Xcode | iOS / iPadOS — [open beta on TestFlight](https://testflight.apple.com/join/ZwXF88Gh) |
| [**`android/`**](android/) | Kotlin + Jetpack Compose + Gradle | Android — closed testing |

The user guide lives at
[anothermorsetrainer.app/guide](https://anothermorsetrainer.app/guide/), and
testers, bug reports and feature chat live on
[Discord](https://discord.gg/qgyk3TPUd9).

Each platform's own README has the full feature list and setup notes:
[ios/README.md](ios/README.md) · [android/README.md](android/README.md).

## Two ports, deliberately not one

`ios/Sources/MorseKit/` (Swift) and
`android/app/src/main/java/app/anothermorsetrainer/morsekit/` (Kotlin) are
**parallel ports of the same training logic, kept as two independent trees.**
Sharing a repository is not sharing code: there is no Kotlin Multiplatform layer,
no common module, and the data tables are intentionally duplicated. Converging
them is a separate project with its own risk; please don't start it by accident
while fixing something else.

The same goes for the vendored CW decoder — `ios/Sources/CWDecoderCore/` (C99)
and `android/…/morsekit/cw/` (its Kotlin port). Both are kept byte-identical to
a firmware copy and carry their own `PROVENANCE.md`; don't reformat, relicense
or tidy them.

## Building

Each app builds from its own directory; nothing at the repository root builds
anything.

```bash
# iOS — needs macOS + Xcode
cd ios
swift build && swift run MorseKitCheck            # logic harness, no Xcode needed
xcodebuild -list -project MorseTrainer.xcodeproj
```

```bash
# Android — needs JDK 17 + the Android SDK
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Versions and releases

**The two apps have independent version numbers and release cadences and are not
coupled.** iOS is at build 24; Android at versionCode 16 / versionName 1.12.1.

Release tags are namespaced per platform, because a single repository now feeds
both release workflows:

| Tag | Fires | Effect |
|---|---|---|
| `ios-v*` | `.github/workflows/discord-release.yml` | Posts a changelog embed to Discord (scoped to `ios/`) |
| `android-v*` | `.github/workflows/android-release.yml` | Builds the signed AAB and uploads it to the Play closed-testing track |

An unprefixed `v*` tag fires **nothing**. Before the split it would have fired
**both** — an iOS Discord announcement for an Android release, and an attempted
Play upload for an iOS one.

`android/RELEASE.md` covers the Play Store side in full.

## CI

`ios.yml` and `android-ci.yml` are path-filtered to `ios/**` and `android/**`
(plus their own workflow files), so a single-platform commit only starts that
platform's build. This matters: the iOS jobs run on `macos-15`, billed at 10x a
Linux runner.

A consequence worth knowing: **a skipped path-filtered job reports no status at
all.** If either build is ever made a *required* status check on `main`,
single-platform PRs will be blocked from merging forever. Use an always-runs gate
job in that case, not a required build.

## History note

**The two repositories were merged on 2026-08-31.** Before that date the Android
app lived at
[`N9HO/another-morse-trainer-android`](https://github.com/N9HO/another-morse-trainer-android),
which is now archived and read-only. Its full commit history was grafted in with
`git subtree` — no history was rewritten, and every original commit SHA is still
reachable here.

**That leaves one ambiguity that cannot be fixed without rewriting history, so it
is documented instead.** At the cutover, iOS was at PR #94 and Android at PR #48.
Merging code does not merge issues, so **an `#N` reference in any commit message
from the Android lineage points at the archived
`another-morse-trainer-android` repository, not at this one** — GitHub will
nevertheless autolink it to this repo's issue #N, which is a different ticket
entirely. Android-lineage commits with `(#46)` and `(#47)` in their subjects are
real examples: those are Android tickets, not this repo's #46 and #47.

The boundary is the graft commit `ae5bde2` ("Add 'android/' from commit
aa23570…"). To tell which side a commit came from:

```bash
# Commits from the Android lineage (their #N refs mean the archived repo)
git log ae5bde2^2
```

Issue references created **after** the cutover mean this repository, on both
platforms.

### Following a file's history across the graft

`git log --follow` does not cross a subtree graft, so on a post-move path it
returns nothing. The history is all there — ask for it with the pre-move path,
or with both paths at once:

```bash
# Full history, using the path as it was before the move
git log --follow -- app/src/main/java/app/anothermorsetrainer/morsekit/TrainerEngine.kt

# Or both paths, which also shows the graft commit itself
git log -- android/app/src/main/java/app/anothermorsetrainer/morsekit/TrainerEngine.kt \
           app/src/main/java/app/anothermorsetrainer/morsekit/TrainerEngine.kt
```

The iOS tree moved with `git mv`, so rename detection handles it and
`git log --follow -- ios/<path>` works normally.
