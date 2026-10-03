# Windows and Linux desktop app: design notes

Status: **being built** in the pull request for #264 (Windows and Linux part).
Written 2026-09-30. The Mac is covered separately, as a Mac Catalyst build of
the iOS app, and nothing here touches `ios/`. Decisions below marked
*maintainer* were settled before the work started; the rest are this
design's, and say why.

The ask (#264, from Discord): a desktop Morse trainer, **full featured** (the
same trainer as the phone apps, not a listening-only cut), on Windows first
and Linux too, with **Vail Adapter keying working**. The maintainer will not
pay for a Windows code-signing certificate.

## Decisions

1. *Maintainer.* **A third independent tree, `desktop/`**: a Compose
   Multiplatform **Desktop (JVM)** app forked from the Android tree's
   Kotlin/Compose code. It is a copy that evolves on its own, exactly as
   `ios/` and `android/` are to each other. It is **not** Kotlin
   Multiplatform sharing and **not** a module shared with `android/`. The
   only thing it shares is `fixtures/`, as data (CLAUDE.md, "two ports, not
   one", now three).
2. *Maintainer.* Distribution through the **Microsoft Store** (MSIX, which
   the Store signs, so no certificate is bought) and **Flathub**. An unsigned
   MSI and an AppImage on GitHub Releases are secondary downloads.
3. *Maintainer.* **Desktop scores are unranked.** The leaderboard's anti-cheat
   rests on device attestation (`docs/high-scores-design.md` §3), and neither
   Windows nor Linux offers an attestation a GPL app can use. Ranked
   submission is off on desktop and the UI says so wherever a ranked mode
   would otherwise submit. Personal bests still work.
4. Package name, class names and file names stay the Android ones
   (`app.anothermorsetrainer`, `QuizScreen.kt`, …). A reader comparing the two
   trees finds the counterpart by name, the way `MorsePlayer.swift` and
   `MorsePlayer.kt` pair up today.
5. The app ID on Linux and the package identity base everywhere is
   **`app.anothermorsetrainer.AnotherMorseTrainer`** (reverse DNS of the
   project's domain, which is what Flathub verifies).

## 1. Tree layout and Gradle

```
desktop/
  settings.gradle.kts         its own Gradle root, like android/
  build.gradle.kts            plugin versions, pinned together
  gradle/wrapper/             Gradle 8.14.3 wrapper
  gradlew, gradlew.bat
  app/
    build.gradle.kts          the app: dependencies, generated sources, jpackage
    src/main/kotlin/app/anothermorsetrainer/
      morsekit/               copied verbatim from android/ (pure Kotlin)
      morsekit/cw/            the vendored decoder's Kotlin port, byte-identical,
                              with its PROVENANCE.md
      vail/                   the Vail repeater client
      *.kt                    the UI and platform layer
    src/main/resources/
      values/strings.xml      the copy, forked from android/
      icon.png
    src/test/kotlin/…         JUnit 4; fixtures/ is on the test classpath
  packaging/
    icons/                    icon.ico (MSI), icon-512.png
    msix/                     AppxManifest.xml template + Assets/
    linux/                    .desktop entry, AppStream metainfo
    flatpak/                  the Flatpak manifest
```

Versions, pinned together because each constrains the next: **Kotlin
2.2.21**, the Compose compiler plugin 2.2.21 (must equal Kotlin),
**Compose Multiplatform 1.9.3**, Material 3 for CMP **1.9.0**,
material-icons-extended 1.7.3 (the last release of that artifact), kotlinx
coroutines-swing 1.10.2, **Gradle 8.14.3**, **JDK 21** (Temurin, installed in
CI by `actions/setup-java`; the build uses a Gradle toolchain of 21). CMP 1.9
rather than the newest 1.12 because Material 3's last stable for CMP is
1.9.0, built against CMP 1.9; mixing it with a 1.12 UI runtime is
unsupported. Move all of them in one edit.

`org.json` is an ordinary dependency here (Android has it in the platform),
and OkHttp stays at the Android tree's 4.12.0.

The Android copy lives in `res/values/strings.xml` and the screens say
`stringResource(R.string.foo)`. The desktop keeps the same file and the same
call shape: a Gradle task generates an `R` object (one `Int` per entry, plus
the names) and `Strings.kt` resolves ids against the XML on the classpath,
applying Android's escaping rules. That is what let ~1,200 call sites port
without edits. The file is a copy; it drifts only by deliberate edit, like
everything else in the tree.

## 2. What ports over, and what is replaced

`morsekit/` (the training logic, 60 files) and its tests copy over
unchanged: they never touched Android APIs. The UI (Compose) ports with its
imports changed, because Compose Multiplatform uses the same `androidx.compose`
package names. What is Android-specific is replaced by a thin platform layer,
each piece keeping the Android class's API so the screens that call it did not
change:

| Android | Desktop | File |
|---|---|---|
| `AudioTrack` (streaming) | `javax.sound.sampled.SourceDataLine`, 16-bit mono, blocking writes from the same feeder threads | `PcmOut.kt` |
| `AudioRecord` (decoder, Sending Analyzer mic) | `TargetDataLine` | `PcmIn.kt` |
| `android.media.midi` | `javax.sound.midi`, polled for hot-plug | `MidiPorts.kt`, `MidiKeyInput.kt`, `MidiKeyOutput.kt` |
| `SharedPreferences` | one JSON file per store in the platform config directory, same store names and keys | `Prefs.kt` |
| `Handler(Looper.getMainLooper())` | `MainHandler` over the AWT event thread | `MainHandler.kt` |
| Android resources | generated `R` + `Strings.kt` | `Strings.kt` |
| system Back | Escape, through the same `BackHandler { }` call | `BackHandler.kt` |
| `AudioFocus` | a bookkeeping shell: desktops have no focus protocol | `AudioFocus.kt` |
| Listen & Learn foreground service | an in-process object; a minimised window keeps playing | `ListenService.kt` |
| share sheet | clipboard (image flavour + text) and Save as PNG | `DesktopShare.kt` |
| TextToSpeech (Listen read-back) | Windows: System.Speech via PowerShell; Linux: `spd-say` or `espeak-ng` when present | `SpeechPlayer.kt` |
| `PrintManager` (Sending Drills sheet) | `java.awt.print.PrinterJob` | `SheetPrinter.kt` |
| vibration | none (no hardware) | `Haptics.kt` |

### Audio

Every sound goes through `PcmOut`: the Morse player's feeder, the sidetone,
the background-noise floor and the repeater's received tones, each on its
own thread with blocking writes, the shape the Android port already had. Java
Sound's `SourceDataLine` sits on DirectSound/WASAPI on Windows and ALSA on
Linux; in a Flatpak the runtime's ALSA plugin routes it to PulseAudio or
PipeWire. The line is 16-bit PCM because every mixer opens it; the float
lines Android uses exist on some mixers only. Buffers: 1,024 frames (~23 ms)
for the sidetone, which has to land under the operator's finger; 2,048
(~46 ms) for the player and noise floor. Java Sound on Linux can underrun
below that; if an operator reports a late sidetone, this is the knob.

The repeater's received tones differ in one respect: Android opens a static
`AudioTrack` per burst, and a Java Sound mixer has a small, device-dependent
number of lines, so the desktop keeps one line open and sums bursts into it.

The pileup mixer still materialises its buffer (CLAUDE.md's standing trap
applies here too) and the player still cross-fades on a mid-tone swap.
**Nobody has heard any of this.** `fixtures/render.json` pins the samples; CI
proves the app starts; the audio path is untested on real hardware, as it is
on both phone ports.

### Key input: the Vail Adapter over `javax.sound.midi`

The adapter is a USB class-compliant MIDI device (and, until woken by a
Control Change, a HID keyboard sending Ctrl or `[` `]`). The desktop runs the
Android code's wake sequence and note parser unchanged; what changes is
discovery.

- **Windows.** The JDK's provider is WinMM (`midiIn*`/`midiOut*`). USB
  class-compliant devices appear there under their product name, so the
  adapter is found. **Known gaps:** a WinMM port is exclusive, so if a DAW,
  or a browser tab using Web MIDI for vailmorse.com, has the adapter open,
  opening it here fails and the key reads as not connected. The "Microsoft GS
  Wavetable Synth" output is always listed and is filtered out by name (it
  would *sound* an RX buzz). Windows 11's newer multi-client "Windows MIDI
  Services" is not reachable from Java Sound.
- **Linux.** The JDK's provider is ALSA **raw MIDI** (`/dev/snd/midiC*D*`),
  which is how the kernel's USB audio driver exposes a class-compliant
  device, so the adapter is found. **Known gaps:** raw MIDI is one opener at
  a time, like WinMM; ALSA's "Midi Through" port is filtered out; and in a
  Flatpak the sandbox sees `/dev/snd` only with `--device=all` (see §4).
- **Hot-plug.** Java Sound has no device-added callback, but both providers
  re-count their devices on each `MidiSystem.getMidiDeviceInfo()`. The
  desktop polls once a second (`MidiPorts.Watch`) and reports differences, so
  plugging a key in is noticed within a second; unplugging releases a held
  key exactly as on Android.
- **Timestamps.** Java Sound's input timestamps are -1 on ALSA and
  device-relative on WinMM, so edges are timed on arrival on the MIDI thread,
  before the hop to the UI thread. The Sending Analyzer's fist timing
  therefore carries the OS's MIDI scheduling jitter (low single-digit ms
  expected; unmeasured). Outgoing RX-buzz notes are held by a scheduler
  thread until their time, because Java Sound receivers ignore send
  timestamps.
- **Keyboard keying.** Without any adapter, Space is a straight key and `[`
  `]` are the dit and dah paddles on the keyed screens — which is also what
  an un-woken Vail Adapter types, so an adapter that never enters MIDI mode
  still keys.

To confirm on hardware (nobody has yet): the adapter's name as WinMM and
ALSA report it (the Vail check and the single-device fallback read it), and
that the wake sequence reaches it through each provider.

### No Bluetooth LE keys on desktop (platform limit)

Neither provider sees Bluetooth LE MIDI. On Windows, BLE MIDI is exposed only
through the WinRT `Windows.Devices.Midi` API (and Windows MIDI Services), not
WinMM. On Linux, BlueZ's BLE-MIDI support creates an ALSA *sequencer* client
with no raw-MIDI node. Neither is reachable from `javax.sound.midi` without
native code, and in-app scanning (Android's `BleMidi`) needs a Bluetooth
stack Java does not have. So the desktop offers USB keys (and the keyboard)
only, and says so under the hardware-key settings. This is recorded in
`PARITY.md` as a documented exception. A user with a BLE key can still bridge
it with OS tools (a WinRT-to-loopMIDI bridge on Windows; `a2jmidid`-style
sequencer-to-raw bridging is not generally available on Linux), which the app
does not document as supported.

### Settings persistence

`Prefs` keeps one JSON file per store in the platform config directory, with
the Android store names (`amt_settings`, `amt_stats`, `amt_vail`, …) and keys,
so the stores port line for line, including their migrations. Windows:
`%APPDATA%\AnotherMorseTrainer` (under the MSIX install, Windows redirects
this into the package's private AppData, which an uninstall removes).
Linux: `$XDG_CONFIG_HOME/another-morse-trainer`, which inside the Flatpak is
the sandbox's own `~/.var/app/<id>/config`. `java.util.prefs` was the
alternative; it was rejected because on Windows it writes the registry,
which the MSIX virtualises awkwardly, and on Linux it writes an XML tree
under `~/.java` that is harder to inspect and back up than a JSON file per
store. Writes are atomic (temp file and rename).

### Foreground service, audio focus, process death

None needed. A desktop process is not reclaimed while minimised, so Listen &
Learn runs in-process and the Android "survives process death as a score"
machinery is inert (kept, since it costs nothing and keeps the screens the
Android ones). Desktops have no audio-focus protocol; the trainer mixes with
whatever else is playing, the platform's idiom.

### Share and copy

Android's share sheet becomes two actions wherever a share button was: **Copy
image** (the rendered card on the clipboard as an AWT image flavour, which
pastes into chat apps, mail and image editors, with a text flavour alongside)
and **Save image…** (the system file dialog, PNG). Neither Windows (whose
Share contract is WinRT-only) nor Linux has a share sheet a JVM app can reach.
Same feature, platform mechanism.

### Notifications and the daily reminder

**Not on desktop in this PR.** A reminder has to fire while the app is
closed. On Windows that means a scheduled toast through WinRT
(`ScheduledToastNotification`), which needs the MSIX package identity and
native interop from the JVM; on Linux, a Flatpak can request autostart
through the Background portal, but there is no scheduler that wakes a closed
app at a time of day. Both are possible with native work and neither is
possible from pure Java. Recorded in `PARITY.md` as a desktop exception with
that reason; Settings shows why instead of the switch. Revisit as a follow-up
with a small native helper if users ask.

### Leaderboard and buddies in unranked mode

`LeaderboardClient` on desktop never starts a run (`beginRun` returns null),
so nothing is submitted; the board itself is public and still readable. Where
a ranked mode's screen would show the post-run rank line, it shows "Not
ranked: desktop scores stay on this computer", and where Settings offers
"Share scores", it explains why desktop runs are unranked. Personal bests,
which are local, work as on the phones. **Buddy streaks** ride on the same
attested identity (`docs/buddy-streak-design.md`), so pairing is unavailable
on desktop for the same reason; that is part of the same exception.

### Speech: read-back and voice answers

Listen & Learn's spoken read-back uses the OS's speech where the JVM can
reach it without native code: Windows' built-in System.Speech through
PowerShell (the text is passed through the environment, not the command
line), and `spd-say` or `espeak-ng` on Linux when installed. Inside the
Flatpak neither tool is present, so read-back there shows the text only;
bundling espeak-ng in the Flatpak is a follow-up.

Daily Dit's "your device is muted" prompt (#252) is not on desktop: Java
cannot read the system volume or mute state portably. Recorded in `PARITY.md`.

**Voice answers** (speaking your copy) are not available: neither Windows'
speech recognizer (WinRT) nor any Linux recognizer is reachable from the JVM
without a native or bundled model. Recorded in `PARITY.md` with that reason.

### Keyboard-first input

A desktop is keyboard and mouse. Typed modes focus their text field on entry
and submit on Enter; multiple-choice modes take number keys and the answer's
own letter (the Android port's `AnswerKeys`); Head Copy takes R, Return and X
(PARITY.md row 16 recorded these as iOS-only; the desktop has them); keyed
screens take Space and `[` `]`; Escape is Back.

## 3. Packaging

### Windows

- **MSI** (GitHub Releases): Compose Desktop's `packageMsi` runs jpackage
  with a jlinked runtime and WiX (the Compose plugin fetches WiX itself).
  Per-user install, Start-menu shortcut, fixed upgrade UUID. Unsigned:
  SmartScreen warns on first run, which is why it is the secondary download.
- **MSIX** (Microsoft Store): `createDistributable` produces the app image
  (launcher `.exe`, `app/`, `runtime/`); CI copies it into a package layout
  with `AppxManifest.xml` and the logo assets from `packaging/msix/`, then
  runs `makeappx.exe pack` from the Windows SDK on the `windows-latest`
  runner. The package is **not signed**: Partner Center signs Store
  submissions, so the maintainer never needs a certificate. An unsigned MSIX
  cannot be sideloaded; it is for upload only.
- The manifest's `Identity Name`, `Publisher` and `PublisherDisplayName`
  must be exactly what Partner Center assigns when the app name is reserved.
  They are read from the repository variables `MSIX_IDENTITY_NAME`,
  `MSIX_PUBLISHER` and `MSIX_PUBLISHER_DISPLAY_NAME`; until those exist, CI
  fills clearly fake placeholders (`CN=PLACEHOLDER-SET-MSIX_PUBLISHER`) and
  the package is only good for checking the build.
- The manifest declares `runFullTrust` (a jpackage app is a Win32 desktop
  app; the Store accepts it for desktop-bridge packages) and the
  `microphone` device capability (decoder, Sending Analyzer). Version is
  `MAJOR.MINOR.PATCH.0`: the Store reserves the fourth part.

### Linux

- **Flatpak** (Flathub). App ID `app.anothermorsetrainer.AnotherMorseTrainer`,
  runtime `org.freedesktop.Platform//25.08`. Permissions and why:
  `--socket=x11` and `--share=ipc` (AWT has no Wayland backend in JDK 21; it
  runs through XWayland), `--socket=pulseaudio` (audio out and the
  microphone), `--device=dri` (Skia can use the GPU; the app defaults to its
  software renderer on Linux and works without it), `--device=all` (raw MIDI
  under `/dev/snd`; Flathub accepts this for MIDI apps with the reason
  stated), `--share=network` (leaderboard board, Vail repeater, news feed for
  Short Stories), `--socket=cups` (printing the Sending Drills sheet), and
  `--filesystem=xdg-pictures` (Save image). An AppStream metainfo
  (`packaging/linux/…metainfo.xml`) and a `.desktop` entry go with it.
- **How Flathub builds it.** Flathub builds offline, from source. Two ways
  exist for a Gradle app: the Flathub manifest builds from the tagged source
  with `org.freedesktop.Sdk.Extension.openjdk21` and a generated list of
  every Maven artifact (the `io.github.jwharm.flatpak-gradle-generator`
  plugin, which `flatpak-builder-tools/gradle` points to, or its Python
  script); or it repackages a release artifact. Flathub's reviewers expect an
  open-source app to take the first path, so that is the plan for the
  submission. It has one wrinkle: the Compose plugin's jlinked runtime needs
  a JDK at build time, which the SDK extension provides.
  **This PR's CI builds the Flatpak the second way** — from the
  `createDistributable` app image, with its own jlinked runtime — to prove the
  sandbox, permissions, desktop entry and metainfo work. The from-source
  manifest is written when the submission is made (step 4 in §7), because it
  needs the generated sources file for the exact tagged dependency set.
- **AppImage** (GitHub Releases): the same app image, with an `AppRun`, the
  `.desktop` entry and icon, packed by `appimagetool` (pinned release). No
  runtime dependencies beyond glibc and X11.

## 4. CI: `.github/workflows/desktop.yml`

Path-filtered to `desktop/**`, `fixtures/**` and the workflow itself, like
the other two. Jobs:

| Job | Runner | What |
|---|---|---|
| Desktop unit tests | ubuntu | `./gradlew :app:test` — the ported morsekit tests and the fixtures |
| Desktop Linux packages | ubuntu | `createDistributable`; tarball, AppImage; launch under Xvfb and screenshot; open each of 32 screens (`AMT_START_ROUTE`) and fail on an exit or an exception |
| Desktop Flatpak | ubuntu (flatpak container) | `flatpak-builder` over the app image; `.flatpak` bundle |
| Desktop Windows packages | windows-latest | `packageMsi`, `createDistributable`, `makeappx pack` → MSI and MSIX |

Windows bills at 2x Linux, so only the packaging that needs Windows runs
there; tests run once, on Linux. No macOS runner is used. Artifacts are
uploaded; nothing is published. Releases will be tagged **`desktop-v*`**
(and `desktop-beta-v*` if a testing channel is added), with a version
independent of both phone apps (`desktopVersionName` in
`desktop/app/build.gradle.kts`). A release workflow that uploads to the
Store and Flathub is later work (§7).

The Xvfb screenshots are the only way anyone sees the app run, the job the
Android emulator smoke test does for that tree. The packaged app is launched
with a fresh profile (onboarding) and a seeded one (home screen), then once
per screen, straight onto its route, and a contact sheet of all of them is
uploaded. The Windows job also starts the app for 25 seconds and takes a
screenshot, as a non-blocking step.

## 5. Governance: two ports become three

Each change is small and delimited so it merges alongside the other open
work:

- **CLAUDE.md**: layout gains `desktop/`; "two ports, not one" names the
  third tree and its morsekit path; the parity rule covers three apps;
  building/testing gains the desktop commands; versions and tags gain
  `desktop-v*`.
- **merge-gate.yml**: detects `desktop/` as a third platform, requires the
  desktop checks when `desktop/`, `fixtures/` or `desktop.yml` change, and
  treats a PR that touches some but not all of the three trees as
  single-platform for the Parity declaration. The desktop jobs stay
  path-filtered and are **never** required checks on `main`; the gate
  requires them only when the diff needs them (its header explains why).
- **PR template**: "Both platforms" becomes "All platforms"; the paired-issue
  line names the other side(s).
- **PARITY.md**: the rule covers three apps; documented exceptions for BLE
  keys, voice answers, daily reminders, haptics, ranked submission and buddy
  streaks on desktop; "same feature, platform mechanism" entries for share,
  audio focus, Listen in the background, Escape as Back.
- **READMEs**: the root README names the three apps; `desktop/README.md`
  lists the same features as the other two, with the desktop differences.
  The phone READMEs are not touched in this PR: an edit under `ios/` starts
  two macOS runners (`ios.yml` does not skip Markdown), and the root README
  already points at all three.
- **The guide** (anothermorsetrainer.app/guide, outside this repo): a
  "Windows and Linux" platform note with the desktop differences, in a site
  PR that waits until the Store and Flathub listings are live.
- **Triage bot** (`ios/tools/discord_triage`): its platform list is iOS,
  iPadOS, macOS, Android. Adding `windows` and `linux` labels and the
  question text is a small change, deliberately left until the first desktop
  release so the bot does not ask users about an app they cannot install.

## 6. Maintainer checklist (only you can do these)

1. **Microsoft Partner Center**: register a developer account (individual
   accounts are free), reserve the name "Another Morse Trainer", and copy
   *Product identity* (Package/Identity/Name, Package/Identity/Publisher,
   Package/Properties/PublisherDisplayName) into repository variables
   `MSIX_IDENTITY_NAME`, `MSIX_PUBLISHER`, `MSIX_PUBLISHER_DISPLAY_NAME`.
2. **Store listing**: description, at least one screenshot (1366x768 or
   larger), the 300x300 store logo, age rating questionnaire, category
   (Education), and the **privacy policy URL** (the existing policy page
   covers the leaderboard read and Vail repeater; add a desktop line: no
   ranked submission, settings kept locally). The app declares microphone;
   the listing must say why.
3. **Flathub**: verify the app ID's domain by serving the token Flathub
   issues at `https://anothermorsetrainer.app/.well-known/org.flathub.VerifiedApps.txt`;
   then open a PR against `flathub/flathub` (branch `new-pr`) with the
   from-source manifest, the metainfo and `flathub.json` if needed. Expect
   review questions about `--device=all` (answer: raw MIDI for USB Morse
   keys).
4. **AppStream**: the metainfo needs real screenshot URLs (hosted on the
   site) and a release entry per version before Flathub accepts it.
5. **Hardware check**: plug a Vail Adapter into a Windows PC and a Linux PC
   with the CI-built packages, and confirm it is named, wakes, keys and
   buzzes. Nobody has done this.
6. **Listen**: Story or Code Exam at a slow effective speed, and the
   sidetone with a key, on each OS. Nobody has heard the desktop audio.

## 7. Risks, and the order of the remaining work

Risks:

- **Nobody has run it on a real desktop.** CI compiles it, runs the logic
  tests (377 of them, the fixtures included), packages it, launches it under
  Xvfb and opens every one of its 32 screens, and launches it once on the
  Windows runner. Nothing is clicked and nothing is heard: MIDI and audio
  have never met hardware.
- **Three trees drift faster than two.** Every user-visible change now owes
  three edits. The merge gate makes a partial change declare itself; it
  cannot make anyone do the third edit.
- **Java Sound latency on Linux** is the likeliest audio complaint (§2).
- **WinMM/raw-MIDI exclusivity** will confuse a user who has the Vail web
  client open in a browser at the same time.
- **Flathub's from-source build** of a Compose Desktop app is uncommon; the
  offline Gradle dependency list for Compose (Skiko's native jars per OS) is
  the part most likely to need iteration during review.
- **Compose Desktop and Linux rendering**: Skia's OpenGL path fails on some
  drivers; the app defaults to software rendering on Linux for that reason
  (`AMT_RENDER=opengl` opts back in).

Order:

1. This PR: tree, port, CI, governance, design.
2. Hardware and listening checks (§6, items 5 and 6) on both OSes; fix what
   they find.
3. The phone work that landed after this fork, ported into `desktop/` in
   this PR after rebasing onto it: Daily Dit Copy puts the card image on the
   clipboard with the text as a fallback flavour (#266, PR #269); the Code
   Exam pass bar and prosign passages, with `CodeExamFixtureTest` reading
   `fixtures/code-exam.json` (#262/#263, PR #274). Decided not to port, each
   recorded in `PARITY.md`: the buddy-line home switch (#253, PR #272; the
   desktop has no buddies, so there is no line to hide; the catalog keeps
   the shared search terms) and the built-in-speaker headroom (#259, PR #271;
   Java Sound cannot tell a laptop's speaker from its headphone jack). Still
   owed: First Four (#265, PR #276), tracked for desktop in #280.
4. Smaller desktop follow-ups found while porting: Settings search still
   indexes the settings the desktop hides (haptics, voice, reminder time,
   leaderboard sharing, buddies) and lands on the explanatory note; R to
   replay on the new-character intro (PARITY.md row 16, still missing on
   Android too); Listen read-back uses the system's default voice, not the
   best English one; the distributions are ~100 MB compressed, and a
   ProGuard pass over the release build (Compose Desktop supports one) would
   shrink them once someone can test the result.
5. Store and Flathub submission (§6), with the from-source Flatpak manifest
   and a `desktop-release.yml` that builds tagged `desktop-v*` releases and
   attaches the MSI, MSIX and AppImage.
6. Follow-ups that are possible with native work: the daily reminder
   (scheduled toast / Background portal), espeak-ng in the Flatpak, and BLE
   keys via a WinRT bridge.
