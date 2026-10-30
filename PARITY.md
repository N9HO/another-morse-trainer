# Feature parity between the apps

The Apple app (`ios/`, one build that runs on iOS, iPadOS and Apple-silicon
Macs) and the Android app (`android/`) are two independent ports of one
trainer, and they are meant to be **the same trainer**. Since #264 there is a
third: the desktop app (`desktop/`, Windows and Linux), forked from the Android
tree and kept as its own independent port. Everything below applies to all
three; where it still says "both", read "every". This file is the one
place that says so and lists every exception; `parity/manifest.json` and
`parity/check.py` hold the rest. It exists because of #171: parity was reached once by deliberate
effort, and nothing stopped it drifting again.

## The rule

1. **Every feature, fix and behaviour change ships on every app.** Nothing a
   user can do on one app may be missing on another.
2. **The only exception is a genuine platform limitation** — the operating
   system cannot do it, or forbids it — and then the exception is written in
   the *Documented exceptions* section below, in the same pull request that
   creates the gap. A gap that is not listed there is a bug.
3. **Parity is part of the definition of done.** An issue that changes what a
   user sees is closed when both apps have the behaviour. If only one side
   lands, the other side gets its own issue (or a tick left open on the
   original) and the first issue stays open until it is done, or is closed
   with a comment naming the issue that carries the rest.
4. **Same behaviour, each platform's own idiom.** iOS pairs a Bluetooth key
   through the system MIDI sheet, Android scans in-app; Android keeps Listen &
   Learn alive with a foreground service, iOS with a background audio
   session. Those are the same feature. Parity is judged by what the user can
   do and what they see, not by how the OS is asked to do it.
5. **Defaults, ranges and labels are behaviour.** A slider that stops at 15
   on one app and 5 on the other, or a mode that opens in a different state on
   each, is a divergence a user notices when they move between phones.
6. **The feature lists agree.** `ios/README.md` and `android/README.md`
   describe the same product. A feature added to one list and not the other
   is a gap in the same sense as one in code.

## How it is enforced

- **Pull request template.** `.github/pull_request_template.md` has a Parity
  section with four boxes: all platforms in this PR; a paired issue (`#N` on
  the line) tracks the other side; a platform limitation recorded in this
  file in the same PR; or platform-internal, nothing user-visible (build, CI,
  lint, refactor, version bump, a crash fix in code only one platform has).
- **Merge gate.** `.github/workflows/merge-gate.yml` reads that section on any
  pull request whose diff touches some but not all of `ios/` (excluding
  `ios/tools/`), `android/` (excluding `android/store-assets/`) and `desktop/`
  (excluding `desktop/packaging/`), Markdown files not counted, and fails
  unless one of
  the last three boxes is ticked — and, for a limitation, unless `PARITY.md`
  is in the diff. Bot-authored PRs are exempt. It cannot tell a feature from
  a refactor; the box is a statement the author is accountable for, and a
  reviewer can read.
- **Issue templates.** A feature request carries a "Shipped on" checklist
  with one box per platform. A bug report asks where it was seen and carries
  a two-box "fixed or confirmed absent" checklist for whoever closes it.
- **Parity check.** `parity/check.py` (CI job *Parity check*, see *Audits are
  replaced by a check* below) fails any pull request that leaves a mode,
  screen, setting, option list, README heading or platform status diverging
  between the apps without a manifest record that carries a reason.
- **Shared fixtures.** `fixtures/` pins the training logic to one set of
  expected values on both ports (see `CLAUDE.md`); a logic change that lands
  on one side fails the other side's build.
- **`CLAUDE.md`** carries the rule for anyone, human or tool, implementing an
  issue: do both trees, or say which side is missing and why.

## Documented exceptions

Each entry is a platform limitation, not a feature one side has not got to.
An entry says what the user cannot do, on which app, why the platform
prevents it, and what the app does instead. Remove an entry when the platform
changes and the gap is closed.

| What | Missing on | Why | What the app does instead |
|---|---|---|---|
| <a id="ex-ios-ble-scan"></a>Pairing a Bluetooth LE MIDI key by scanning from inside the app | iOS | CoreMIDI only exposes a BLE MIDI peripheral once it has been connected through the system `CABTMIDICentralViewController` sheet; there is no app-level scan API. | Opens that system sheet from the screens that take a key (Sending Practice and keyed answers via `SendingKeyerView`, the Repeater, the Sending Analyzer; `BluetoothMIDISheet.swift`). Same outcome: a paired key. |
| <a id="ex-android-midi-section"></a>Hardware-key section always visible in Settings | Android | Some Android devices ship without `FEATURE_MIDI`; showing MIDI controls there would offer a feature the device cannot use. | The section is hidden on devices without the feature (`SettingsScreen.kt`, `FEATURE_MIDI` check). On devices that have it, the section matches iOS. |
| <a id="ex-android-voice-auto"></a>Voice answers: listening starts by itself when the tone ends, and the time-to-recognize clock starts at speech onset | Android | Android's `SpeechRecognizer` is one-shot: each invocation plays the system start sound and takes audio focus, so auto-listening after every prompt would chime over every character; it also owns the microphone, so the app gets no audio to detect onset from. | A "Speak answer" button starts one recognition per prompt (`QuizScreen.kt`); the clock runs from the tap. |
| <a id="ex-android-desktop-build"></a>A desktop build (#264) | Android | Android has no desktop build of its own. The Apple app reaches the Mac through Mac Catalyst from the same target. | Windows and Linux get the separate `desktop/` app (#264), forked from the Android code and listed below; the Android app itself is phone and tablet only. |
| <a id="ex-android-reminder-exact"></a>Daily reminder at exactly the chosen minute | Android | The app deliberately does not request `SCHEDULE_EXACT_ALARM`, which Android 12+ gates behind a special permission; an inexact alarm may fire minutes late when the OS batches it. | `setInexactRepeating` at the chosen time; the reminder still arrives, at minute precision only on iOS. |

#### Desktop (Windows, Linux) — #264

The desktop app is a Compose Desktop (JVM) port; these are the things it
cannot do, and why (details in `docs/desktop-design.md` §2).

| What | Missing on | Why | What the app does instead |
|---|---|---|---|
| <a id="ex-desktop-ble"></a>Bluetooth LE MIDI keys | desktop | Java Sound's MIDI providers are WinMM on Windows and ALSA raw MIDI on Linux; BLE MIDI is exposed only through WinRT (Windows) and as an ALSA sequencer client with no raw-MIDI node (BlueZ), neither reachable from `javax.sound.midi`, and Java has no Bluetooth stack to scan with. | USB MIDI keys (the Vail Adapter) and the keyboard (Space, `[` `]`); the hardware-key settings say so (`DesktopCopy.KEY_CONNECT_HINT`). |
| <a id="ex-desktop-voice"></a>Voice answers | desktop | No speech recognizer is reachable from the JVM: Windows' is WinRT-only, and Linux has no system recognizer. | Typed, choice and keyed answers; Settings says why instead of the switch. |
| <a id="ex-desktop-reminder"></a>Daily reminder | desktop | A reminder must fire while the app is closed. Windows needs a WinRT scheduled toast, Linux has no time-of-day scheduler for a sandboxed app; neither is possible from pure Java. | Settings says so instead of the switch. Follow-up with a native helper if asked for. |
| <a id="ex-desktop-haptics"></a>Haptics | desktop | Desktops have no vibration hardware. | The switch is hidden (`Haptics.isAvailable`). |
| <a id="ex-desktop-ranked"></a>Ranked leaderboard runs, and buddy streaks (with them the home screen's buddy line and its "Show buddy line on home screen" switch, #253) | desktop | Both rest on device attestation (App Attest, Play Integrity; `docs/high-scores-design.md` §3), which neither OS offers to an open-source app. With no pairing there is never a buddy line to hide, so the switch is not shown. | Runs count toward personal bests; post-run lines say "Not ranked"; the board is readable; Settings explains. Settings search still finds the Buddy section by "home screen" (the shared catalog), and lands on that explanation. The section offers no Invite or Join button, so the phone apps' "a tap that cannot pair says why" fix (#335) has nothing to change here. |
| <a id="ex-desktop-listen-controls"></a>Listen & Learn controls in the lock screen, notification or car display | desktop | There is no media-session surface a JVM app can publish to. | The window's own controls; a minimised window keeps playing. |
| <a id="ex-desktop-headroom"></a>−6 dB headroom on the built-in speaker (#228 iOS, #259 Android) | desktop | Java Sound exposes mixers, not output routes: a laptop's speaker and its headphone jack are usually one mixer, and nothing portable says which is playing, so the app cannot tell when to apply it. Applying it everywhere would quieten headphones and interfaces too. | Every output plays at full level (samples as `fixtures/render.json` pins them); the OS volume is the control. Revisit if desktop users report pops at high speed on laptop speakers. |
| <a id="ex-desktop-dailydit-mute"></a>Daily Dit's "your device is muted" prompt before a listen (#252) | desktop | Java has no portable way to read the system output volume or mute state on Windows or Linux. | Every Play plays; the listen is spent as usual. |
| <a id="ex-desktop-flatpak-speech"></a>Spoken read-back inside the Flatpak | desktop (Linux, Flatpak) | The sandbox has no speech engine; Windows (System.Speech) and a Linux install with `spd-say` or `espeak-ng` do speak. | Shows the text. Bundling espeak-ng is a follow-up. |

#### The Daily DiDaaaaahDit (#353)

Shipped on iOS, Android and desktop in one change; no platform exceptions.
Each tree reads its own generated copy of `fixtures/comprehension-bank.json`
(`ios/tools/gen_comprehension_bank.py`), and each has a test that the copy
equals the fixture. Platform idiom only: desktop adds A-D / 1-4 / Enter on the
keyboard; the phones answer by tap or by keying A-D on a hardware key (all
three). The "your device is muted" prompt (#252) is not part of this mode on
any app, since listens are free and uncounted for scoring. The user guide needs
The Daily DiDaaaaahDit section (see `docs/daily-comprehension-design.md`).

### Same feature, platform mechanism (not gaps)

Listed so nobody "ports" one side's plumbing to the other, or reads it as a
missing feature.

- **Big-screen layout.** Both apps run on tablets in any orientation and in
  split-screen or resizable windows, and both re-flow the home grid four
  across on a big window with the same thresholds (`wideLayout` in
  `Theme.swift`, `isWideLayout()` in `Responsive.kt`). On iPad, Settings
  and Stats open as page-sized sheets, so Settings shows its categories as
  a sidebar and Stats runs its lists two to a row; Android's full-screen
  Settings and Stats keep the single readable column. Same settings and
  same figures; the iPad form is the sheet's own idiom. The desktop app's
  window is resizable and uses the Android rule and thresholds
  (`isWideLayout()` in its own `Responsive.kt`).

- **Notification permission.** Android 13+ makes `POST_NOTIFICATIONS` a
  runtime permission, so the Android app asks for it when the reminder is
  switched on; iOS asks through `UNUserNotificationCenter` at the same
  moment. Same outcome.
- **Streak count in the reminder text.** Android reads the streak when the
  alarm fires (`ReminderReceiver.kt`); iOS cannot run code at delivery, so
  it bakes the count in and re-schedules whenever the streak can change:
  the day's first practice, a lapse noticed on foreground, a reset
  (`AppModel.refreshReminderIfStreakChanged`).
- **Reminder after a reboot.** `UNCalendarNotificationTrigger` survives a
  reboot on its own; `AlarmManager` alarms do not, so the Android app re-arms
  on `BOOT_COMPLETED` (`ReminderReceiver.kt`).
- **Session state across process death.** Android reclaims a backgrounded
  Activity, so the Android app mirrors the tally, phase and clock into
  saveable state and closes a reclaimed run out to Stats (`CLAUDE.md`,
  "Session state on Android"). iOS keeps the model alive; nothing to
  restore.
- **Listen & Learn with the screen locked.** Android: a foreground service
  (`ListenService.kt`). iOS: the background audio session and `.playback`
  claim (`MorsePlayer.swift`).
- **Background noise floor.** Android and desktop run it as a separate
  stream (`BackgroundNoise.kt`); iOS folds it into the player's render
  callback (`MorsePlayer.swift`). Same six levels, same amplitudes. Band
  noise is scoped to practice on every app (#331): Android and desktop gate
  it on the open screen (`Route.playsBandNoise`), iOS on the player holding
  the audio session, which a run hands back as it ends — so the iOS
  end-of-run summary is quiet, where the Android and desktop recap, still
  on the mode's screen, keeps the noise until you leave it. Each plays a
  2.5-second sample when the level is picked in Settings.
- **Decoder microphone path.** Android asks for the `UNPROCESSED` source
  with a sample-rate fallback chain (`CwDecoderEngine.kt`); iOS sets the
  session to `.measurement` mode (`AudioSession.swift`). Both bypass the
  OS's gain control and noise suppression.
- **Voice recognition biasing.** iOS uses a custom language model and
  on-device recognition (`VoiceRecognizer.swift`, iOS 17+); Android has only
  `EXTRA_BIASING_STRINGS` (`VoiceRecognizer.kt`, API 33+), the nearest
  equivalent.
- **Bluetooth LE key on the Mac.** The iOS system pairing sheet
  (`CABTMIDICentralViewController`) does not exist on the Mac; macOS connects
  BLE-MIDI keys system-wide in Audio MIDI Setup, and the same button shows
  how (`MacBluetoothMIDIHelp`, `MacCatalystSupport.swift`). Same outcome: a
  key CoreMIDI can see.
- **Desktop (#264).** Share is *Copy image* (clipboard, image and text
  flavours) and *Save image…* (PNG) instead of a share sheet; Escape is Back;
  audio mixes with other apps (desktops have no audio-focus protocol); Listen
  & Learn runs in-process, since a minimised desktop app keeps running;
  settings are JSON files in the platform config directory; the Sending
  Drills sheet prints through the system print dialog; USB keys are found by
  polling, since Java Sound has no hot-plug callback; keyed screens also take
  Space and `[` `]`.
- **Account sign-in tokens and device name (Account & Sync).** iOS keeps
  the access and refresh tokens in the Keychain, this device only
  (`AccountServices.swift`); Android in `EncryptedSharedPreferences`, backed
  by the Android Keystore (`AccountClient.kt`); desktop, which has no
  keystore common to Windows and Linux on the JVM, in a file in its config
  directory readable only by the user: `rw-------` on POSIX file systems, an
  ACL holding only the owner on Windows (desktop `AccountClient.kt`). The
  name shown in the devices list is the device's own: `UIDevice` name on
  iOS, the device-name setting (else the model) on Android, the hostname on
  desktop. Same feature, same sync rules (`fixtures/sync-wire.json`).
- **Audio-stack reset recovery.** iOS rebuilds the engine on
  `mediaServicesWereReset` (`AudioSession.swift`); Android has no such
  event and catches `IllegalStateException` instead.

## Audits are replaced by a check

Until #171 closed, parity was kept by audits: someone read the trees side by
side, wrote down the differences, and fixed them. Two audits (2026-09-04 and
2026-09-27) found dozens of divergences each, and a third would have found
more. **That work is now a program.** `python3 parity/check.py` (CI job
*Parity check*, `.github/workflows/parity.yml`) compares the three trees
against `parity/manifest.json` on every pull request and fails on any
divergence the manifest does not record.

What the check reads, from source text only (it runs no app code and needs no
network or toolchain):

- every training mode (`TrainingMode`), Android and desktop route
  (`sealed interface Route`) and screen file each tree registers;
- every enumerated option list a user picks from (keyer modes, paddle modes,
  durations, reveal modes ...), in order and by label, across the trees, and
  against `fixtures/keyer-modes.json` and `fixtures/option-lists.json`, which
  the Swift harness and both Kotlin test suites also read;
- the Settings catalog of each tree (which settings exist, their titles and
  their order);
- the feature headings of the three READMEs;
- that every feature the manifest says is `limited` or `absent` on a platform
  has a reason and an anchor in this file.

`python3 parity/check.py --self-test` is the negative control: it feeds the
checker deliberately drifted data and fails unless every drift is caught.

What it cannot see: behaviour. Whether a slider has the same range, a screen
the same layout, a drill the same scoring, still takes a reader (or a shared
fixture, which is why `fixtures/` exists). The check guarantees that no mode,
screen, setting or option list appears on one app and not another without
somebody writing down why.

### Adding a feature

1. Build it on every tree (iOS, Android, desktop), one edit per tree.
2. Add its entry to `parity/manifest.json`: its modes/routes/screens/files per
   tree, its README headings, and a status per platform. A platform that does
   not get it needs a `notes` entry with the reason and an anchor here.
3. Run `python3 parity/check.py`. It names anything it still sees diverge.

A new enumerated setting also gets its list added to `fixtures/option-lists.json`
and its enum locations to `parity/sources.json`.

## Agreed values (kept from the audits)

The audits' lists of closed items are in git history (this file before the
check landed). What stays is the record of values and rules the apps agreed
on, so a regression is recognisable.

Values the two apps now agree on (the iOS value, the original app's, unless
Android's was plainly safer): character speed floor 15 WPM; Farnsworth off
by default with an effective-speed floor of 8; recognize-within 0.5–3.0 s;
reveal the answer on a miss; five-minute sessions; Head Copy two repeats and
a 5 s reveal; Listen gaps 1.3 / 1.0 / 0.5 / 0.2 s; exam graded both ways
in every sitting (no grading choice since the ARRL grading change); your
call W1AW; caller speed floor 12; tone spread to 500 Hz;
QRN Off / Normal / Moderate / Heavy at 0 / 0.04 / 0.10 / 0.20; keep partial
call off; RX piezo buzz on; RX delay 0–4000 ms in 250 ms steps; TX tone
48–96; 5000 signal events; the pileup QRN, keep-partial and Listen-gap
values stored by earlier Android builds are mapped to the nearest new value
on load.

MorseKit: one custom-word parser on both ports (split on comma, semicolon
and whitespace; trim; uppercase; strip characters with no Morse pattern; cap
at 24; drop empties; de-duplicate), pinned by `fixtures/custom-words.json`
in the Swift harness and `CustomWordsTest`; the contest multiplier drops
empty pieces on both; `MorseCode.characterForPattern` is public on Android;
the Rapid Fire response blurbs match.

Closed after the release that shipped the above: the two mode names that
differed. iOS called them "Words" and "QSO Simulator"; both apps now say
"Common Words" and "Pileup Runner", on the tiles and in every sentence
that names the mode. Internal identifiers did not change.

Three audited rows turned out not to be gaps and are recorded here so they
are not re-audited: the repeater's room list, deterministic private-QSO
channel name and decoder-room flag are model state on iOS that no view
reads, and Android now carries the same state; the Vail in-app log ring
buffer is a debugging aid with no user-visible surface on either app.

**Test coverage is still not at parity, and that is a parity risk.** The
iOS harness runs about 520 checks over 50 sections; Android has about 150
JUnit tests in 25 classes. Sections with no Android twin: voice matching
and profile, exam speeds, passage generation and solid-copy grading, contest
practice, practice streak, session history and the recognition chart, Rapid
Fire, the story and serial libraries, Q-codes, word tiers, confusion pairs,
MorseKit's own `MorseDecoder`, and save/load. Neither side tests
`SendingDrill`. The way to close these is the one `CLAUDE.md` prescribes: a
fixture derived from the spec, read by both.

## Open gaps

The divergences the last audit (2026-09-27) found and nobody has closed yet
live in the `gaps` list of `parity/manifest.json`, one record each (rows 1-25,
the copy differences, the layout decisions and the documentation gaps), with
the platforms it affects, its state and, where the source can show it, a probe
that makes the check fail when the gap closes so the record cannot go stale.
`python3 parity/check.py --gaps` prints them. Audit rows had no issue numbers
when they were written; the `ref` field is the row (`audit-0927-NN`) and
`issue` is `null` until one is opened.

The desktop app was forked from `android/` at `7bcb50a` (2026-09-30), so it
inherits every Android-side gap as it stood then. It closes one half of row
16: Head Copy takes R, Return and X on desktop, as on iOS.

Layout, recorded for a decision rather than as gaps (gap `layout-decisions`):
where the voice and keyed-answer switches, word pool, "Use my word list",
track stage and QRQ speed live (setup sheet on iOS, Settings or the mode
screen on Android); Sending Drills, Sending Analyzer, Reference and the
Repeater as toolbar icons (iOS) vs tiles (Android); home tile order; the
last-used tile highlight; the setup sheet's long blurb vs short tagline; iOS's
finer Settings sections. If these are accepted as idiom they move to *Same
feature, platform mechanism*.

## Closing an item

When a divergence is found by a bug report or a review, add a record to
`gaps` in `parity/manifest.json` first, then fix it on the side that lacks
it, in that tree's idiom, and delete the record in the same pull request
(the check fails on a recorded gap whose source no longer shows it, so a
closed gap cannot linger). If, on inspection, the behaviour turns out to be
one the platform cannot provide, record it under *Documented exceptions*
above with an `ex-` anchor, and reference that anchor from the feature's
`notes` in the manifest. If the apps disagree on a default or a range, pick
the value the user guide documents (or the better one, and update the guide),
and change the other side. A pull request that closes a gap is a
single-platform PR by nature; tick "Paired issue" and name the gap's issue, or
tick "All platforms" when every side changes.
