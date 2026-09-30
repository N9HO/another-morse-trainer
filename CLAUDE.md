# Working in this repository

A monorepo holding three independent apps. Read this before making changes
that span directories.

## Layout

    ios/        SwiftUI app + SwiftPM package (Xcode project, macOS to build)
    android/    Kotlin + Compose app (Gradle root; settings.gradle.kts lives here)
    desktop/    Windows + Linux app: Compose Multiplatform Desktop on the JVM,
                forked from android/ (its own Gradle root; see
                docs/desktop-design.md)
    fixtures/   Shared test *data*, read by every tree. Not code — see below.
    docs/       Design notes for work that is decided but not yet built.
                Read the relevant one before starting such a feature;
                `docs/high-scores-design.md` covers scorekeeping, personal
                bests and the shared anti-cheat leaderboard.

Nothing at the repository root builds anything. `cd ios`, `cd android` or
`cd desktop` first.

## The rule that matters most: separate ports, not one

`ios/Sources/MorseKit/` (Swift),
`android/app/src/main/java/app/anothermorsetrainer/morsekit/` (Kotlin) and
`desktop/app/src/main/kotlin/app/anothermorsetrainer/morsekit/` (Kotlin) are
**parallel ports of the same training logic, kept as three independent
trees.** The desktop tree began as a copy of the Android one (#264) and is
not linked to it: same language, same file names, no shared code.

- Do **not** unify them — no Kotlin Multiplatform, no shared module, no
  deduplicating the data tables. That is a separate project with real risk.
- A fix that applies to all of them is **three** edits, one per tree, in the
  language and idiom of that tree. Two trees in the same language are still
  two edits: never make `desktop/` read, include or depend on `android/`.
- A user-visible change on one side obligates the same change on the other,
  in the same pull request or in a paired issue — see the parity rule below.
  Either way, say which side you touched.

The one thing the trees *do* share is `fixtures/`: JSON files of expected
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

`fixtures/**` is in the `paths:` filter of *all three* platform workflows and
in `merge-gate.yml`'s detection for every platform — a fixture change has to
build every app, or it is only partly checked. The desktop tests read them
the Android way (`org.json` from the test classpath).

## The other rule: separate ports, one feature set

**Every feature, fix and behaviour change ships on every app** (#171): iOS
(iPhone, iPad, Mac), Android, and desktop (Windows, Linux). Nothing a user can
do on one may be missing on another, unless the platform genuinely cannot do
it — and then the exception is written down in `PARITY.md`, not left as a silent gap. `PARITY.md` is the single
record: the policy, every intentional platform-limited exception, and the
divergences the last audit found and had not yet closed.

What this means when you make a change:

- **Parity is part of the definition of done.** An issue that changes what a
  user sees is complete when every app has the behaviour, not when one does.
  When you are asked to implement a feature or fix a behaviour bug, do every
  tree in the same change — one edit per tree, per the rule above — or say
  plainly which side is missing and why.
- **A single-platform pull request has to say why.** The pull request template
  has a Parity section; `merge-gate.yml` reads it on any PR whose diff touches
  some but not all of `ios/` (excluding `ios/tools/`), `android/` (excluding
  `android/store-assets/`) and `desktop/` (excluding `desktop/packaging/`),
  Markdown not counted, and fails unless
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
- **Divergence in what is documented counts too.** The three app READMEs list
  the same features; a feature added to one list and not the others is a gap
  in the same sense.

## The guide goes with the app

The user guide at anothermorsetrainer.app/guide describes every mode,
setting and hardware option, and its source lives outside this repository.
**A feature, or a change to how an existing feature behaves, is not done
until the guide section that describes it says the new thing** (#172). This
is documentation of behaviour, not a changelog: the guide must never describe
something the shipped apps no longer do.

- The pull request template has a Guide section; `merge-gate.yml` reads it on
  any PR that changes app code (any tree, Markdown not counted) without
  ticking "Platform-internal", and fails unless "Guide updated" (say which
  sections) or "No guide change needed" (say why) is ticked.
- When a change reaches one platform before the other, the guide says which.
- The app READMEs' feature lists are the in-repo summary of the same thing;
  keep them in step too (the parity rule above).

## Do not touch the vendored decoder

`ios/Sources/CWDecoderCore/` (C99), `android/…/morsekit/cw/` (its Kotlin
port) and `desktop/…/morsekit/cw/` (a byte-identical copy of that port) are
kept byte-identical to a firmware copy. Each has a `PROVENANCE.md`
next to the code it documents, and `CWDecoderCore` also has its own `LICENSE`.
Don't reformat, relicense, tidy, or relocate them or those files.

## Licensing

The repository is GPL-3.0-or-later (root `LICENSE`, copyright Justin Rogers); the
vendored decoder above stays MIT (copyright Jay Vana), which the GPL permits.
Every app shows both notices on the Settings › Help & About › Licenses screen — the
GPL asks an interactive program to display its terms, and the MIT notice must
accompany every copy, including the shipped binaries. A new third-party
dependency that carries a notice goes on that screen too, on every port that
ships it.

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

```bash
cd desktop                                # needs JDK 21 (CI installs it)
./gradlew :app:test                       # JUnit 4; fixtures/ is on the classpath
./gradlew :app:run                        # the app, on the machine you are on
./gradlew :app:createDistributable        # app image with a jlinked runtime
./gradlew :app:packageMsi                 # Windows only
```

The MSIX, Flatpak and AppImage are assembled from the app image by
`desktop.yml`; see `docs/desktop-design.md` §3–4.

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
and desktop change is written blind and verified only by CI. Nobody has run
the desktop app on a real Windows or Linux machine either: `desktop.yml`'s
Xvfb launch screenshots are the only time anyone sees it. iOS builds locally, but nobody
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
  - Desktop: `desktopVersionName` / `desktopVersionCode` in
    `desktop/app/build.gradle.kts`
- Release tags are namespaced: **`ios-v*`** and **`android-v*`**. A bare `v*`
  tag fires nothing. Testing builds are tagged `ios-beta-v*` /
  `android-beta-v*` by the release workflows themselves; never push an
  `ios-v*` or `android-v*` tag to mark one, because that ships to production.
- Desktop releases will be tagged **`desktop-v*`** (no release workflow yet;
  `desktop.yml` builds artifacts only).
- `ios.yml`, `android-ci.yml` and `desktop.yml` are path-filtered to their own
  subtree. `desktop.yml` runs one job on `windows-latest` (2x Linux billing)
  and none on macOS. If you
  add a workflow, give it a `paths:` filter too — the iOS jobs run on `macos-15`
  at 10x Linux billing.
- Changing a path filter? Remember a *skipped* job reports no status, so a
  path-filtered job must never be a required status check on `main`.

## Commit-message issue references

The Android app was merged in from a separate repository on 2026-08-31. **In
commits from the Android lineage, `#N` means the archived
`another-morse-trainer-android` repo, not this one** — see the History note in
`README.md`. Anything you write now refers to this repo.
