# Feature parity between the two apps

The Apple app (`ios/`, one build that runs on iOS, iPadOS and Apple-silicon
Macs) and the Android app (`android/`) are two independent ports of one
trainer, and they are meant to be **the same trainer**. This file is the one
place that says so, lists every exception, and records what the last audit
found. It exists because of #171: parity was reached once by deliberate
effort, and nothing stopped it drifting again.

## The rule

1. **Every feature, fix and behaviour change ships on both apps.** Nothing a
   user can do on one app may be missing on the other.
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
  section with four boxes: both platforms in this PR; a paired issue (`#N` on
  the line) tracks the other side; a platform limitation recorded in this
  file in the same PR; or platform-internal, nothing user-visible (build, CI,
  lint, refactor, version bump, a crash fix in code only one platform has).
- **Merge gate.** `.github/workflows/merge-gate.yml` reads that section on any
  pull request whose diff touches only one of `ios/` (excluding `ios/tools/`)
  and `android/` (excluding `android/store-assets/`), Markdown files not
  counted on either side, and fails unless one of
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
| Pairing a Bluetooth LE MIDI key by scanning from inside the app | iOS | CoreMIDI only exposes a BLE MIDI peripheral once it has been connected through the system `CABTMIDICentralViewController` sheet; there is no app-level scan API. | Opens that system sheet from Settings (`BluetoothMIDISheet.swift`). Same outcome: a paired key. |
| Hardware-key section always visible in Settings | Android | Some Android devices ship without `FEATURE_MIDI`; showing MIDI controls there would offer a feature the device cannot use. | The section is hidden on devices without the feature (`SettingsScreen.kt`, `FEATURE_MIDI` check). On devices that have it, the section matches iOS. |
| Voice answers: listening starts by itself when the tone ends, and the time-to-recognize clock starts at speech onset | Android | Android's `SpeechRecognizer` is one-shot: each invocation plays the system start sound and takes audio focus, so auto-listening after every prompt would chime over every character; it also owns the microphone, so the app gets no audio to detect onset from. | A "Speak answer" button starts one recognition per prompt (`QuizScreen.kt`); the clock runs from the tap. |
| Daily reminder at exactly the chosen minute | Android | The app deliberately does not request `SCHEDULE_EXACT_ALARM`, which Android 12+ gates behind a special permission; an inexact alarm may fire minutes late when the OS batches it. | `setInexactRepeating` at the chosen time; the reminder still arrives, at minute precision only on iOS. |

### Same feature, platform mechanism (not gaps)

Listed so nobody "ports" one side's plumbing to the other, or reads it as a
missing feature.

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

## Closing an item

When an audit, a bug report or a review finds a divergence, add it as a row
here first, then fix it on the side that lacks it, in that tree's idiom, and
delete the row in the same pull request. If, on inspection, the behaviour
turns out to be one the platform cannot provide, move the row to *Documented
exceptions* instead, with the reason. If the two apps disagree on a default
or a range, pick the value the user guide documents (or the better one, and
update the guide), and change the other side. A pull request that closes a
row is a single-platform PR by nature; tick "Paired issue" and name the row's
issue, or tick "Both platforms" when both sides change.
