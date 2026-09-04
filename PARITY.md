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
| Daily reminder at exactly the chosen minute | Android | The app deliberately does not request `SCHEDULE_EXACT_ALARM`, which Android 12+ gates behind a special permission; an inexact alarm may fire minutes late when the OS batches it. | `setInexactRepeating` at the chosen time; the reminder still arrives, at minute precision only on iOS. |
| A notification-permission prompt before the first reminder | iOS | None needed beyond the standard authorisation request; on Android 13+ `POST_NOTIFICATIONS` is a runtime permission the user has to grant separately. | Android asks for it when the reminder is switched on. iOS asks through `UNUserNotificationCenter` at the same moment. Same user-facing outcome. |
| Reminder survives a reboot without the app being opened | iOS | Not a gap: `UNCalendarNotificationTrigger` survives reboot on its own. Android's `AlarmManager` alarms do not, so the Android app re-arms on `BOOT_COMPLETED`. | Listed so nobody "ports" the Android receiver to iOS. |
| In-flight drill restored after the OS kills the process mid-session | iOS | iOS does not kill a foreground app mid-session the way Android reclaims a backgrounded Activity, so there is no state to restore. | Android mirrors the tally, phase and clock into saveable state and closes a reclaimed run out to Stats (`CLAUDE.md`, "Session state on Android"). |
| Listen & Learn keeps playing with the screen locked | — | Both do; the mechanism differs (Android foreground service `ListenService.kt`, iOS background audio session). | Listed as a same-feature example, not a gap. |

## Audit of 2026-09-04

The audit compared the two trees file by file: settings and defaults, every
mode and its options, the MorseKit logic layer and its data tables, progress
and sharing, and the hardware, audio and repeater layers. What follows is
everything found that is **not** a platform limitation — accidental
divergence, in the issue's words. Each line is a gap to close, in the
direction that makes the two apps agree; where one side is plainly better,
that side is the target. The tables are the backlog: an item leaves this file
when a pull request closes it on the side that lacks it.

A behaviour listed as "iOS only" is missing on Android, and the reverse.
File references are to the side that has the behaviour.

### Modes and screens

Every mode exists on both apps. Where the two home screens differ (iOS puts
Reference, Sending Drills, CW Decoder and Vail in the top bar, Android makes
them tiles) that is layout, not a gap. The gaps are in what a mode offers.

| Behaviour | Has it | Lacks it | Evidence |
|---|---|---|---|
| Journey: "misses drain the bar" toggle | iOS | Android | `IntroView.swift:436`, `AppSettings.swift:513`. Android has `JourneyScoring.FillOnly` in `Journey.kt:140` but hardwires `Default` at `JourneyScreen.kt:98`. |
| Code Exam: "use a built-in passage" option | iOS | Android | `IntroView.swift:552`, `AppModel.swift:959`. Android bundles the passages (`MorseDataExam.kt:62`) but `CodeExamScreen.kt:97` only ever calls `forRandom`. |
| Rapid Fire: call-sign shape chips | iOS | Android | `IntroView.swift:757`. Android pins `CallsignFormat.commonDefaults` at `RapidFireScreen.kt:154`. |
| Mid-session timer controls (add 5 min, add 1 min, remove limit) | iOS | Android | `ContentView.swift:1364`, `AppModel.swift:1788`. Android shows a read-only countdown, `QuizScreen.kt:487`. |
| Mid-session mode switcher in the toolbar | iOS | Android | `ContentView.swift:1146`. Android routes every mode change through Home (`MainActivity.kt:300`). Plausibly deliberate given one-screen-per-mode routing; decide, then either port or list as an exception. |
| Head Copy: live "Revealing in N…" countdown | iOS | Android | `ContentView.swift:345`. |
| Listen & Learn: four answer-gap tiers (1.3 / 1.0 / 0.5 / 0.2 s) | iOS | Android | `AppSettings.swift:81`; Android has three (1.3 / 0.7 / 0.3 s) at `Listen.kt:23`. The iOS comment already names iOS as canonical. |
| First-run onboarding that asks proficiency once and seeds the ladder | Android | iOS | `OnboardingScreen.kt:39`, `MainActivity.kt:242`. iOS asks per mode on the setup sheet instead (`IntroView.swift:985`); Android removed that from its sheet on purpose (`SessionSetupSheet.kt:43`, #109). The two apps ask the same question in different places; pick one model. |
| Keying answers in every keyable drill, with an in-quiz toggle | Android | iOS | `QuizScreen.kt:544`. iOS allows it for Characters and Words only, from the setup sheet (`IntroView.swift:687`, `AppModel.swift:466`). |

Checked and at parity, README wording notwithstanding: the eight pileup
exchange flavours, Reference's ham-lingo section, story kinds and the four
news sources, exam speeds and grading, Rapid Fire content and response
modes, the Track-stage pin, Start Here, Daily Dit, contest types, and voice
answers in the same six quizzes.

### Settings, defaults and ranges

Present on one app only:

| Setting | Has it | Lacks it | Evidence |
|---|---|---|---|
| Show right / wrong | iOS | Android | `AppSettings.swift:585`, `SettingsView.swift:275` |
| Show replay button | iOS | Android | `AppSettings.swift:587`, `SettingsView.swift:281` |
| QSO: min / max wait between callers | iOS | Android | `AppSettings.swift:408`, `SettingsView.swift:369` |
| QSO: per-caller Farnsworth | iOS | Android | `AppSettings.swift:403`, `SettingsView.swift:353` |
| QSO: which digits get cut | iOS | Android | `AppSettings.swift:413`, `SettingsView.swift:395`. Android has on/off only (`PileupScreen.kt:422`) and passes no `cutDigits` (`PileupSettings.kt:160`). |
| Copy diagnostic info to the clipboard | iOS | Android | `SettingsView.swift:449`, `:595` |
| Developer: preview stage | iOS | Android | `SettingsView.swift:422`. Developer affordance; port or drop. |
| QSO: "key my side in Morse" on/off | Android | iOS | `PileupSettings.kt:77`. iOS always keys your side. |
| QSO: "recall after TU" on/off | Android | iOS | `PileupSettings.kt:80`. iOS always re-calls. |
| Mode setup remembered across launches (Rapid Fire, QRQ speed, Contest, Exam) | iOS | Android | iOS persists in `AppSettings.swift:202`, `:536`, `:470`, `:566`; Android keeps them in `rememberSaveable` / `remember` (`RapidFireScreen.kt:103`, `TypedQuizScreen.kt:379`, `ContestScreen.kt:86`, `CodeExamScreen.kt:69`) and resets on relaunch. |

Present on both, but a user moving between phones would notice:

| Setting | iOS | Android | Evidence |
|---|---|---|---|
| Character speed floor | 15 WPM | 5 WPM | `SettingsView.swift:110`; `Settings.kt:102` |
| Farnsworth | explicit on/off switch, effective speed floor 8 | always-visible slider, "Off" at character speed, floor 5 | `AppSettings.swift:481`, `SettingsView.swift:141`; `SettingsScreen.kt:244` |
| Recognize-within range | 0.5–3.0 s | 0.5–2.5 s | `AppSettings.swift:502`; `Settings.kt:154` |
| Reveal-the-answer default | on a miss | always | `AppSettings.swift:586`; `Settings.kt:160` |
| Session length default | 5 minutes | until I stop | `AppSettings.swift:519`; `Settings.kt:163` |
| Head Copy defaults | 2 auto-repeats, reveal at 5 s (0–10 s slider) | auto-repeat off, manual reveal (0/2/4/6 s) | `AppSettings.swift:598`; `Settings.kt:189` |
| Custom word list | used whenever non-empty; hides the tier picker | separate "use my list" switch; needs at least 2 words | `IntroView.swift:622`; `Settings.kt:485` |
| Voice answers and keyed answers | mutually exclusive | both can be on | `IntroView.swift:400`; `QuizScreen.kt:544` |
| Code Exam grading default | questions | solid copy | `AppSettings.swift:568`; `CodeExamScreen.kt:70` |
| QSO: your call default | W1AW | blank, sent as N0CALL | `AppSettings.swift:396`; `PileupSettings.kt:157` |
| QSO: caller speed floor | 12 WPM | 10 WPM | `PileupSettings.kt:39` |
| QSO: tone spread maximum | 500 Hz | 400 Hz | `PileupSettings.kt:136` |
| QSO: QRN levels and labels | Off / Normal / Moderate / Heavy = 0 / 0.04 / 0.10 / 0.20 | Off / Light / Medium / Heavy = 0 / 0.04 / 0.09 / 0.16 | `AppSettings.swift:323`; `PileupSettings.kt:18` |
| QSO: keep partial call default | off | on | `AppSettings.swift:421`; `PileupSettings.kt:74` |
| Reset all progress | always in Settings | Home only, hidden mid-session | `SettingsView.swift:467`; `SettingsScreen.kt:559` |

### Progress, statistics and sharing

| Behaviour | Has it | Lacks it | Evidence |
|---|---|---|---|
| Most-confused pairs section on Stats | iOS | Android | `StatsView.swift:67`, `AppModel.swift:2310`. Android records and persists the same matrix (`ConfusionMatrix.kt`, `EngineStore.kt:111`) and shows it nowhere but the Confusion drill. |
| Ideal time-to-recognize line and gridlines on the session chart | iOS | Android | `SessionDetailView.swift:144`; Android bars at `StatsScreen.kt:443` have no goal line. |
| Per-character table: pattern, attempts, mastered seal, weakest first | iOS | Android | `StatsView.swift:145`, `AppModel.swift:2292`. Android shows an A–Z bar chart only (`StatsScreen.kt:135`). |
| Stats header: current stage, active characters, recognize-within goal | iOS | Android | `StatsView.swift:12` |
| Stage name on the share card | iOS | Android | `BragSheetView.swift:289`; `ShareCard.kt:54` omits it. |
| Home streak badge shows milestone emoji and best streak | iOS | Android | `IntroView.swift:187`; Android shows the count only (`HomeScreen.kt:319`). |
| Session detail shows duration, fastest copy and speed | Android | iOS | `StatsScreen.kt:395`. iOS stores them (`SessionHistory.swift:18`) and never shows them (`SessionDetailView.swift:45`). |
| Share button hidden until the first session | Android | iOS | `StatsScreen.kt:90`; iOS can share a zero-session card (`BragSheetView.swift:50`). |
| A corrupt history row is dropped, not the whole history | Android | iOS | `Stats.kt:260`; iOS `loadHistory` drops everything on one bad byte (`AppModel.swift:2824`). |

Same feature, different numbers:

| What | iOS | Android | Evidence |
|---|---|---|---|
| Lifetime totals | summed from a history capped at 100 sessions, so they shrink after the 100th | monotonic counters | `AppModel.swift:2350`, `SessionHistory.swift:109`; `Stats.kt:142` |
| "Mastered" on the share card | 5-attempt window, ≥90 %, minimum attempts | lifetime ≥90 % and median under target, no minimum | `Stats.swift:71`, `AppModel.swift:2356`; `ShareCard.kt:101` |
| Per-character stats window | active characters, last 20 attempts | every character ever drilled, last 12 correct | `AppModel.swift:2281`; `Stats.kt:113` |
| Speed-band input | full history | the 50-row summary list | `SessionHistory.swift:173`; `StatsScreen.kt:155` |
| Reset all progress | clears the ladder and Journey only, while the dialog says "learned letters and stats" | clears streak, history, per-character data, ladder and Journey | `AppModel.swift:2833`, `SettingsView.swift:492`; `Stats.kt:189` |
| Session chart colour | green / red at 0.9 | three tiers at 0.9 / 0.7 | `SessionDetailView.swift:182`; `StatsScreen.kt:459` |
| Streak count in the reminder | baked in when scheduled, re-armed on the day's first practice | read when the alarm fires | `Notifications.swift:30`; `ReminderReceiver.kt:34`. Android's cannot go stale. |

At parity: streak model and milestones (3 / 7 / 14 / 30 / 60 / 100 / 365),
celebrations, week strip, totals grid, personal bests, session list and
detail, per-session chart, speed bands, history cap, share caption, Daily Dit
share, printable drill sheet, reminders, and every persistence store.

### Hardware keys, audio, voice, decoder and repeater

| Behaviour | Has it | Lacks it | Evidence |
|---|---|---|---|
| Repeater: unread-chat badge with a per-channel read watermark | iOS | Android | `RepeaterModel.swift:59`, `:440`; Android only dedupes (`VailRepeater.kt:289`). |
| Repeater: roster entries expire after 60 s | iOS | Android | `RepeaterModel.swift:161`, `:692`; Android sets the list and stops (`VailRepeater.kt:286`). |
| Repeater: room list surfaced | iOS | Android | `RepeaterModel.swift:54`; Android parses rooms (`VailMessage.kt:111`) and drops them at `VailRepeater.kt:286`. |
| Repeater: deterministic private QSO channel name from both callsigns | iOS | Android | `RepeaterModel.swift:389` |
| Repeater: the server's "decoder" room flag honoured | iOS | Android | `RepeaterModel.swift:98`; Android no-ops at `VailRepeater.kt:298`. |
| Repeater audio mixes with other apps (a radio app can run alongside) | iOS | Android | `AudioSession.swift:76`; Android's repeater sidetone takes full focus (`SidetoneGenerator.kt:36`). |
| Sending Practice tells "MIDI unavailable" from "nothing plugged in" | iOS | Android | `SendingKeyer.swift:25`, `SendingKeyerView.swift:130`; Android reports a name or null (`MidiKeyInput.kt:64`). |
| In-app Vail log ring buffer | iOS | Android | `VailLog.swift:24`. Debug affordance; port or drop. |
| Adapter re-configured live mid-session from Settings, on every screen | Android | iOS | `MidiKeyOutput.kt:105`, `HardwareKey.kt:97`; iOS pushes live only from the repeater (`RepeaterModel.swift:117`) and configures Sending Practice once at start (`SendingKeyer.swift:99`). |
| Unplugging the key releases a stuck key-down | Android | iOS | `MidiKeyInput.kt:110`; iOS updates the source list only (`MIDIInput.swift:145`). |
| Key is "down" while any paddle note is held | Android | iOS | `MidiKeyInput.kt:119`; iOS ends transmit on the first release (`SendingKeyer.swift:146`, `RepeaterModel.swift:514`), cutting overlapping paddle presses short. |
| A lone connected MIDI device is assumed to be the adapter for RX buzz | Android | iOS | `MidiKeyOutput.kt:80`; iOS name-matches only (`MIDIOutput.swift:244`). |
| Settings note saying which keyer modes the adapter clocks | Android | iOS | `AdapterKeyer.kt:77`, `SettingsScreen.kt:796`; iOS says it in prose (`RepeaterView.swift:337`). Minor. |

Same feature, different numbers:

| What | iOS | Android | Evidence |
|---|---|---|---|
| RX piezo buzz default | off | on | `RepeaterModel.swift:136`; `VailRepeater.kt:50` |
| RX delay range | 0–4000 ms in 250 ms steps | 0–5000 ms slider | `RepeaterView.swift:323`; `RepeaterScreen.kt:306` |
| TX tone clamp | 0–127 | 48–96 | `RepeaterModel.swift:406`; `VailRepeater.kt:177` |
| Signal-event cap | 5000 | 2000 | `RepeaterModel.swift:67`; `VailRepeater.kt:80` |

Voice answers: iOS starts listening when the tone ends and meters speech
onset for the time-to-recognize clock (`AppModel.swift:2113`,
`VoiceRecognizer.swift:239`); Android waits for a tap on "Speak answer"
(`QuizScreen.kt:659`) and leaves `onBeginningOfSpeech` empty
(`VoiceRecognizer.kt:51`). Android's one-shot recogniser owns the microphone
and plays its own start tone, so the auto-start is platform-shaped, but
`onRmsChanged` could approximate the onset clock. Decide, then port or add to
the exceptions.

At parity: MIDI input and the wire parser, hot-plug, MIDI output and the
adapter wake, keyer modes 0–9 and speed, sidetone, RX buzz scheduling,
touch and physical-keyboard answers, the sidetone ramp, playback, the pileup
mix, audio focus, background playback, noise-floor levels and amplitudes,
haptics, speech voices, the voice tables, matcher, profile and confirm flow,
the decoder engine, its constants, telemetry, two-core rescue and screen,
the Vail client, sender attribution, message envelope, break-in, server
picker with custom URL, private channel, signal timeline and roster.
