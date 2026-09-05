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
- A user-visible change on one side obligates the same change on the other,
  in the same pull request or in a paired issue — see the parity rule below.
  Either way, say which side you touched.

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

## The other rule: two ports, one feature set

**Every feature, fix and behaviour change ships on both apps** (#171). Nothing
a user can do on iOS/iPadOS/macOS may be missing on Android, or the reverse,
unless the platform genuinely cannot do it — and then the exception is written
down in `PARITY.md`, not left as a silent gap. `PARITY.md` is the single
record: the policy, every intentional platform-limited exception, and the
divergences the last audit found and had not yet closed.

What this means when you make a change:

- **Parity is part of the definition of done.** An issue that changes what a
  user sees is complete when both apps have the behaviour, not when one does.
  When you are asked to implement a feature or fix a behaviour bug, do both
  trees in the same change — two edits, one per tree, per the rule above — or
  say plainly which side is missing and why.
- **A single-platform pull request has to say why.** The pull request template
  has a Parity section; `merge-gate.yml` reads it on any PR whose diff touches
  only one of `ios/` (excluding `ios/tools/`) and `android/` (excluding
  `android/store-assets/`), Markdown not counted, and fails unless
  exactly one of these is ticked: the other side is tracked in a paired issue
  (`#N` on that line), the gap is a platform limitation recorded in
  `PARITY.md` in the same PR, or the change is platform-internal with no
  user-visible effect (build, CI, lint, refactor, version bump, a crash fix in
  code only one platform has). Bot-authored PRs are exempt.
- **Paired issues.** A feature issue tracks both sides with its "Shipped on"
  checklist; a bug report says where it was seen and is checked on the other
  app before it closes. When only one side lands, open or link the issue for
  the other side rather than closing the original.
- **The platform's own idiom is not a divergence.** iOS pairs a BLE key through
  the system MIDI sheet and Android scans in-app; Android keeps Listen & Learn
  alive with a foreground service and iOS with a background audio session.
  Parity is about what the user can do, not how each OS does it.
- **Divergence in what is documented counts too.** The two READMEs list the
  same features; a feature added to one list and not the other is a gap in the
  same sense.

## The guide goes with the app

The user guide at anothermorsetrainer.app/guide describes every mode,
setting and hardware option, and its source lives outside this repository.
**A feature, or a change to how an existing feature behaves, is not done
until the guide section that describes it says the new thing** (#172). This
is documentation of behaviour, not a changelog: the guide must never describe
something the shipped apps no longer do.

- The pull request template has a Guide section; `merge-gate.yml` reads it on
  any PR that changes app code (either tree, Markdown not counted) without
  ticking "Platform-internal", and fails unless "Guide updated" (say which
  sections) or "No guide change needed" (say why) is ticked.
- When a change reaches one platform before the other, the guide says which.
- The two READMEs' feature lists are the in-repo summary of the same thing;
  keep them in step too (the parity rule above).

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

Swift concurrency checking is at **complete** in both trees, and pinned there:

- `Sources/` (the SwiftPM package) via
  `.enableUpcomingFeature("StrictConcurrency")` in `Package.swift`.
- `MorseTrainerApp/` (the Xcode target) via `SWIFT_STRICT_CONCURRENCY` in the
  project file.

Both were clean when pinned, so a new warning in either is a regression, not a
backlog item. The language mode is still Swift 5 (`SWIFT_VERSION`), so a
regression is a warning, not a failed build — read the log, not the exit code.

Know what this does *not* buy you: neither level diagnoses a race in a callback
from a C API, because there is no concurrency construct for it to check. The
CoreMIDI read block in `MIDIInput.swift` and the `AVAudioSourceNode` render
block in `KeyerEngine.swift` are both invisible to it at *complete*. Those need
reading, not compiling. Two races there were fixed by hand — `MIDIInput`'s
callback `var`s behind `stateLock`, and `ToneGenerator`'s
`OSAllocatedUnfairLock` — and nothing but the comments at those sites guards
against their return: reintroduce either and every build stays green.

```bash
cd android                                # needs JDK 17 + Android SDK
./gradlew :app:testDebugUnitTest          # JUnit 4; fixtures/ is on the classpath
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

## Verifying a change here

**There is no Android hardware behind this project, and no JDK or Android SDK
on the maintainer's machine.** `./gradlew` cannot run locally; every Android
change is written blind and verified only by CI. iOS builds locally, but nobody
can *hear* either app, and no test on either side plays audio. Everything in
this section exists because of that.

1. **Shared expectations go in `fixtures/`, as data.** Derive the values from
   the documented spec, not by running either port: a fixture captured from the
   code only records what the code already does, and both ports drifting the
   same way would still pass. Never regenerate a fixture to make a failing test
   pass; regenerate only when the behaviour is *meant* to change, and say so in
   the commit.
2. **Run a negative control on every new test.** Gradle prints test names only
   on failure, so a green build cannot distinguish "passed" from "never
   executed". Perturb the expectation in a throwaway commit, confirm the
   specific test names itself in the failed job's log, then revert that one
   commit with `git revert --no-edit <sha>` and read `git log` before pushing.
   Three ways a control lies: it can itself fail to *compile*, which from
   outside looks exactly like a successful control (check *which* job failed
   and *which* test named itself); `git revert A..B` excludes `A`; and
   `git revert` rejects `-q`, so a `&&`-chained commit lands on top of the
   unreverted control.
3. **Anything outside `ios/` and `android/` needs wiring into CI by hand.**
   Both platform workflows are path-filtered and `merge-gate.yml` derives its
   required checks from the diff, so a new file elsewhere triggers nothing and
   is required by nothing until it is added. Read `merge-gate.yml`'s header
   before touching a `paths:` filter.
4. **Measure iOS warnings against a clean worktree at `HEAD`**, each build with
   a fresh `-derivedDataPath`. A bare count means nothing, and an incremental
   build under-reports badly.

The Android emulator smoke test installs the release APK, launches it, rotates
it, and asserts `MainActivity` is not recreated. It is the only thing that ever
runs the app where anyone can see it, and a fresh install stops at onboarding.

Standing traps, all deliberate:

- **The pileup mixer materialises its buffer on both ports, on purpose.**
  Voices summed with per-voice pitch, speed, QSB and gain, then band noise and
  a peak normalisation over the finished mix — none decidable a sample ahead.
  Do not "finish" the streaming rewrite by changing it.
- **Nobody has heard the streaming audio player.** `fixtures/render.json`
  proves the samples are unchanged and CI proves it launches; the mid-tone
  cross-fade is behaviour no test covers. Story or Code Exam at a slow
  effective speed is the case to listen to.
- **Session state on Android survives process death as a score, not a round.**
  The quiz screens keep their tally, phase and clock under `rememberSaveable`;
  the engine-driven screens (Pileup, Contest, Rapid Fire) mirror their score
  into saveable state and close a reclaimed run out to `Stats` on restore. The
  in-flight drill, pileup or exam passage is not restored, and nothing but CI
  compiling it has ever exercised any of it.

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
