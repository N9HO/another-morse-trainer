# Feature parity between the apps

The Apple app (`ios/`, one build that runs on iOS, iPadOS and Apple-silicon
Macs) and the Android app (`android/`) are two independent ports of one
trainer, and they are meant to be **the same trainer**. Since #264 there is a
third: the desktop app (`desktop/`, Windows and Linux), forked from the Android
tree and kept as its own independent port. Everything below applies to all
three; where it still says "both", read "every". This file is the one
place that says so, lists every exception, and records what the last audit
found. It exists because of #171: parity was reached once by deliberate
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
| Pairing a Bluetooth LE MIDI key by scanning from inside the app | iOS | CoreMIDI only exposes a BLE MIDI peripheral once it has been connected through the system `CABTMIDICentralViewController` sheet; there is no app-level scan API. | Opens that system sheet from the screens that take a key (Sending Practice and keyed answers via `SendingKeyerView`, the Repeater, the Sending Analyzer; `BluetoothMIDISheet.swift`). Same outcome: a paired key. |
| Hardware-key section always visible in Settings | Android | Some Android devices ship without `FEATURE_MIDI`; showing MIDI controls there would offer a feature the device cannot use. | The section is hidden on devices without the feature (`SettingsScreen.kt`, `FEATURE_MIDI` check). On devices that have it, the section matches iOS. |
| Voice answers: listening starts by itself when the tone ends, and the time-to-recognize clock starts at speech onset | Android | Android's `SpeechRecognizer` is one-shot: each invocation plays the system start sound and takes audio focus, so auto-listening after every prompt would chime over every character; it also owns the microphone, so the app gets no audio to detect onset from. | A "Speak answer" button starts one recognition per prompt (`QuizScreen.kt`); the clock runs from the tap. |
| A desktop build (#264) | Android | Android has no desktop build of its own. The Apple app reaches the Mac through Mac Catalyst from the same target. | Windows and Linux get the separate `desktop/` app (#264), forked from the Android code and listed below; the Android app itself is phone and tablet only. |
| Daily reminder at exactly the chosen minute | Android | The app deliberately does not request `SCHEDULE_EXACT_ALARM`, which Android 12+ gates behind a special permission; an inexact alarm may fire minutes late when the OS batches it. | `setInexactRepeating` at the chosen time; the reminder still arrives, at minute precision only on iOS. |

#### Desktop (Windows, Linux) — #264

The desktop app is a Compose Desktop (JVM) port; these are the things it
cannot do, and why (details in `docs/desktop-design.md` §2).

| What | Missing on | Why | What the app does instead |
|---|---|---|---|
| Bluetooth LE MIDI keys | desktop | Java Sound's MIDI providers are WinMM on Windows and ALSA raw MIDI on Linux; BLE MIDI is exposed only through WinRT (Windows) and as an ALSA sequencer client with no raw-MIDI node (BlueZ), neither reachable from `javax.sound.midi`, and Java has no Bluetooth stack to scan with. | USB MIDI keys (the Vail Adapter) and the keyboard (Space, `[` `]`); the hardware-key settings say so (`DesktopCopy.KEY_CONNECT_HINT`). |
| Voice answers | desktop | No speech recognizer is reachable from the JVM: Windows' is WinRT-only, and Linux has no system recognizer. | Typed, choice and keyed answers; Settings says why instead of the switch. |
| Daily reminder | desktop | A reminder must fire while the app is closed. Windows needs a WinRT scheduled toast, Linux has no time-of-day scheduler for a sandboxed app; neither is possible from pure Java. | Settings says so instead of the switch. Follow-up with a native helper if asked for. |
| Haptics | desktop | Desktops have no vibration hardware. | The switch is hidden (`Haptics.isAvailable`). |
| Ranked leaderboard runs, and buddy streaks | desktop | Both rest on device attestation (App Attest, Play Integrity; `docs/high-scores-design.md` §3), which neither OS offers to an open-source app. | Runs count toward personal bests; post-run lines say "Not ranked"; the board is readable; Settings explains. |
| Listen & Learn controls in the lock screen, notification or car display | desktop | There is no media-session surface a JVM app can publish to. | The window's own controls; a minimised window keeps playing. |
| Spoken read-back inside the Flatpak | desktop (Linux, Flatpak) | The sandbox has no speech engine; Windows (System.Speech) and a Linux install with `spd-say` or `espeak-ng` do speak. | Shows the text. Bundling espeak-ng is a follow-up. |

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
  same figures; the iPad form is the sheet's own idiom.

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
- **Background noise floor.** Android runs it as a separate always-on stream
  (`BackgroundNoise.kt`); iOS folds it into the player's render callback
  (`MorsePlayer.swift`). Same six levels, same amplitudes.
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
- **Audio-stack reset recovery.** iOS rebuilds the engine on
  `mediaServicesWereReset` (`AudioSession.swift`); Android has no such
  event and catches `IllegalStateException` instead.

## Audit of 2026-09-04, and what closed it

The audit compared the two trees file by file in five slices: every mode and
its options, settings and defaults, progress and sharing, the hardware,
audio, voice, decoder and repeater layers, and the MorseKit logic layer with
its data tables. Every mode, every data table and every engine constant was
already on both apps; the divergences were in options a mode offered,
defaults and ranges, what the Stats screens showed, and the repeater's
secondary features. **All of them were closed in the same change that
recorded them**, on the side that lacked each behaviour, in that tree's
idiom. This section keeps the list so the next audit can start from it, and
so a regression of any row is recognisable.

Closed on Android (behaviour iOS had): the Journey "misses drain the bar"
toggle; Code Exam's built-in-passage option; Rapid Fire call-sign shape
chips; mid-session timer controls (add 5 min, add 1 min, remove the limit);
the mid-session mode switcher; Head Copy's repeat count, 0–10 s reveal and
live "Revealing in N…" countdown; the fourth Listen & Learn gap tier; show
right / wrong and show replay button; copy diagnostic info; Developer ·
Preview Stage; QSO wait-between-callers, per-caller Farnsworth and
which-digits-are-cut settings; mode setup remembered across launches (Rapid
Fire, QRQ speed, Contest, Exam); an explicit Farnsworth switch; the
most-confused-pairs section; the per-character table with pattern, attempts
and mastered seal; the Stats header; the session chart's goal line, gridlines
and axis; the stage name on the share card; the streak badge's milestone
emoji and best; the mastered count computed with a minimum-attempt gate;
speed bands over the full history; the repeater's unread-chat badge with a
per-channel read watermark and 60-second roster expiry; a repeater sidetone
that ducks other audio instead of taking it; and the "MIDI unavailable"
readout distinct from "no key connected".

Closed on iOS (behaviour Android had): first-run onboarding that asks the
proficiency once, seeds the ladder and unlocks the Journey that far
(`JourneyCurriculum.firstLevelBeyond`, now on both), with the per-mode
"Where are you starting?" card dropped from the setup sheet for the reason
in #151; keyed answers in every keyable drill, with an in-quiz toggle; the
QSO "Key my side in Morse" and "re-calls after TU" toggles; an explicit "Use
my word list" switch with the two-word minimum; session detail's duration,
fastest copy and speed; the share button hidden until a first session; a
history decoder that drops one corrupt row instead of the whole history;
lifetime totals as monotonic counters (seeded once from the existing history
so nobody's numbers drop); a reset that clears the streak, history and stats
its dialog promises; the three-tier session chart colours; Reset all
progress hidden mid-session; live adapter reconfiguration from Settings on
every screen; a stuck key released when the key is unplugged; the key held
while any paddle note is held; a lone connected MIDI device taken as the
adapter for RX buzz; and the note under the keyer picker saying which modes
the adapter clocks.

Values the two apps now agree on (the iOS value, the original app's, unless
Android's was plainly safer): character speed floor 15 WPM; Farnsworth off
by default with an effective-speed floor of 8; recognize-within 0.5–3.0 s;
reveal the answer on a miss; five-minute sessions; Head Copy two repeats and
a 5 s reveal; Listen gaps 1.3 / 1.0 / 0.5 / 0.2 s; exam grading by
questions; your call W1AW; caller speed floor 12; tone spread to 500 Hz;
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

## Audit of 2026-09-27: open rows

A second audit, in the same slices, after CW 77, the Sending Analyzer,
on-screen paddles, keyboard-entry answers and multiple buddies landed. Those
five features were found at parity in their logic: the CW 77 table, every
Sending Analyzer threshold and text, the paddle keyer (pinned by
`fixtures/paddle-keyer.json`), answer entry (`fixtures/answer-entry.json`) and
the buddy limits all match. What follows had not been closed when it was
recorded. Each row is closed per *Closing an item* below.

Behavior (a user can do something on one app and not the other, or gets a
different result):

| # | Divergence | iOS | Android |
|---|---|---|---|
| 1 | Session length in Journey, Sending Practice, Rapid Fire and Pileup Runner | Picker, five-minute default, countdown and timer menu (`AppModel.usesSessionLength`) | None; the run lasts until End. Rapid Fire and Pileup Runner are ranked, so runs are not comparable |
| 2 | Session length in Short Stories | No picker or timer menu, yet `startSession` starts the `practiceDuration` clock for every mode but Contest, so a story appears to end silently at five minutes; Code Exam and the games may be affected the same way (read from code, not yet run) | Picker and countdown (`DURATION_MODES` includes STORY) |
| 3 | Timer menu "Subtract 1 minute" | Present (`reduceSessionTime`) | Missing (`SessionMenus.kt`) |
| 4 | Pileup Runner / Contest options mid-session | In the in-session Settings sheet | Home Settings only (`SettingsScreen.kt` `PILEUP -> scope == null`) |
| 5 | Your callsign / name from a CW 77, Listen or Common Words session | Your Station section shown | Unreachable; the fields live in the home-only Pileup section |
| 6 | Display name and Delete my scores with Share scores off | Always shown | Hidden, so deleting scores or buddy pairings needs sharing switched back on |
| 7 | Post-run leaderboard line | Rank and metric, plus "New personal best · c/t graded correct"; "not ranked" (refused) told apart from "not posted" | Rank and metric or a reason only |
| 8 | Leaderboard metric display | Rounded (`metric.rounded()`) | Truncated (`toLong()`): 339.6 reads 340 vs 339 |
| 9 | Display-name validation | Server deny list and specific messages; no error on an empty field | No deny list, one generic message, shown when empty |
| 10 | Stats › Recent sessions | Whole history (up to 100), each opens its detail | Latest 6 |
| 11 | Best session accuracy and biggest session | Over the full history | Over `Stats.recent` (50) |
| 12 | Stats before the first session | Characters table shown | Only the empty message |
| 13 | Personal bests, lifetime totals and sharing from Home | Unreachable: the Brag Sheet opens only from a session toolbar | On the Progress screen from Home |
| 14 | Bluetooth LE key in Invaders, Galaga, Dungeon, Asteroids | A paired key feeds them | No `BluetoothKeyButton`, so only USB/Vail MIDI works |
| 15 | "Studied characters" (Analyzer Groups, Sending Drills) | The Koch ladder's active set | Seed plus every character with stats |
| 16 | Hardware-keyboard shortcuts in Head Copy (R, Return, X) and R to replay on the new-character intro | Present | Missing |
| 17 | On-screen key after the answer is revealed | Stays live | Disabled (Sending Practice, Rapid Fire) |
| 18 | Sidetone and tone-spread slider steps | 10 Hz; "Zero-beat" below 10 Hz | Continuous; "Zero beat" only at 0; stored sidetone not clamped on load |
| 19 | Pileup min/max speed and wait | Independent; min can pass max | Coupled |
| 20 | Your callsign input | Free text | Uppercase, letters/digits/`/`, 12 max, blank → W1AW |
| 21 | Preview tone | Replays the current drill item; silent with none | Always keys PARIS |
| 22 | Sending Analyzer, microphone input | Key closed | Hardware key stays open with its sidetone |
| 23 | Sending Analyzer, two keys at once | Merged into one logical key | Recorded separately |
| 24 | Sending Analyzer, microphone | Stops on interruption or route loss; own denied-permission message | No interruption handling; reuses the decoder's message ("…to decode audio") |
| 25 | Home Daily Dit card, solved with no WPM recorded | Drops the speed | Shows "copied at  WPM" (a bug) |

Copy and labels (same feature, different words; pick one per row):
reminder notification title and no-streak body; buddy footer, invite share
text and the "New invite code" relabel; delete-scores dialog; the CW 77
"no callsign set" hint; setting names (Side tone / Sidetone pitch, Speed /
Character speed, Recognize within / Recognition target, Reveal the letter /
Reveal answer, and the Pileup Runner rows); Android session rows showing
record keys ("Pileup", "Stories") where iOS shows mode titles; fastest copy
"0.84 s" vs "840ms"; the stale iOS QRQ blurb ("35 or 40 WPM", both offer
35/40/50/60); the Pileup Runner tagline.

Layout, recorded for a decision rather than as gaps: where the voice and
keyed-answer switches, word pool, "Use my word list", track stage and QRQ
speed live (setup sheet on iOS, Settings or the mode screen on Android);
Sending Drills, Sending Analyzer, Reference and the Repeater as toolbar
icons (iOS) vs tiles (Android); home tile order; the last-used tile
highlight (iOS only); the setup sheet's long blurb vs short tagline; iOS's
finer Settings sections. If these are accepted as idiom they move to *Same
feature, platform mechanism*.

Documentation: neither README mentions the Daily Dit; the Android README
lacks the first-run proficiency question and describes Sending Practice and
the Repeater more briefly than iOS's.

### The desktop app and this audit

The desktop tree was forked from `android/` at `7bcb50a` (2026-09-30), so it
inherits every Android-side row above as it stood then. It closes one half of
row 16: Head Copy takes R, Return and X on desktop, as on iOS (the R-to-replay
on the new-character intro is still missing on desktop and Android). Closing
a row now means closing it on every app that has the gap.

## Closing an item

When an audit, a bug report or a review finds a divergence, add it as a row
here first, then fix it on the side that lacks it, in that tree's idiom, and
delete the row in the same pull request. If, on inspection, the behaviour
turns out to be one the platform cannot provide, move the row to *Documented
exceptions* instead, with the reason. If the two apps disagree on a default
or a range, pick the value the user guide documents (or the better one, and
update the guide), and change the other side. A pull request that closes a
row is a single-platform PR by nature; tick "Paired issue" and name the row's
issue, or tick "All platforms" when every side changes.
