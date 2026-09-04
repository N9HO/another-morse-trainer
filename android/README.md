# Another Morse Trainer: Android

A native Android port of [Another Morse Trainer](https://anothermorsetrainer.app).
The SwiftUI iOS app is its sibling in this repo, at [`ios/`](../ios) — a separate,
independently-versioned implementation, not a shared module.
Learn to copy Morse code (CW) by ear with the Koch method.

**Testing is currently closed.** Places are limited, so ask on
[Discord](https://discord.gg/qgyk3TPUd9) for a spot. (The iOS beta is open to
anyone via [TestFlight](https://testflight.apple.com/join/ZwXF88Gh).)

The user guide, covering every mode, setting and hardware option, lives at
[anothermorsetrainer.app/guide](https://anothermorsetrainer.app/guide/).

Built with **Kotlin + Jetpack Compose**. The training logic (`morsekit`) is a
near 1:1 port of the iOS MorseKit package; the UI is rebuilt in Compose to match
the iOS app's navy/teal look.

## Features

- **Journey**: gamified, level-based path (letters → numbers → punctuation →
  prosigns → Q-codes → abbreviations → words → call signs) with a progress bar
  that fills on a hit and drains on a miss, an unlock map, and saved progress
- **Characters**: Koch-method ladder (A-Z, 0-9) with a user-pinnable
  "Track stage" (characters, pairs, triples, words & call signs)
- **Common Words**, **Abbreviations**, **Q-Codes**, **Prosigns**: phrase drills
- **Confusion Drill**: targeted review of the pairs you mix up
- **Head Copy**, **Type It**, **QRQ Speed**: copy in your head, free-recall
  typing, and high-speed copy at 35 / 40 / 50 / 60 WPM on its own setting
- **Rapid Fire**: a stream of call signs / words / number groups / states sent
  back to back at your chosen pace; type as you hear it, head-copy then type,
  key each one back, or just listen and review the transmitted list
- **Pileup Runner**: a full QSO simulator: your callsign, eight exchange
  flavours (single caller → POTA → contests), realism controls (caller count,
  speed band, tone spread, QSB, QRN, cut numbers, bust behavior, callsign
  shapes), your own side keyed in Morse, auto re-call after TU, and a live
  log + rate readout. A near miss makes the station re-send its own call, an
  ambiguous copy brings both stations back, a partial matches anywhere in the
  call, and walk-offs are reported at the end of the run or as they happen
- **Contest**: timed runs of the weekly CW events (K1USN SST, ICWC MST, CWops
  CWT, NCCC Sprint, ARRL Field Day) with authentic exchanges, speeds, live
  score/rate, and an end-of-run scorecard
- **Code Exam**: FCC/ARRL-style copy test at 5 / 13 / 20 WPM
- **Sending Practice**: key it back (touch or MIDI key); quizzes can also be
  answered by keying
- **Repeater**: live CW over the Vail network with a server picker and
  private-channel option, plus Vail Adapter support: MIDI key input *and*
  output (keyer mode + speed, sidetone, opt-out RX piezo buzz), Bluetooth LE
  MIDI keys, and unplug detection
- **Reference**: browsable, tap-to-hear chart of prosigns, Q-codes,
  abbreviations, ham lingo, cut numbers, and the full alphabet, with
  per-signal detail
- **Short Stories**: continuous copy of a fable (32 bundled), a longer classic
  (Sherlock Holmes and friends) sent in parts with a bookmark that keeps your
  place, or todays news: real RSS headlines sanitized to sendable Morse and
  hidden until you reveal them, since decoding is the only way to read them
- **CW Decoder**: point the microphone at received Morse (a rig's speaker, a
  WebSDR) and read it as text; a faithful Kotlin port of the Carrier Wave
  decoder core with live WPM/pitch telemetry and a two-core pitch-lock rescue
- **Listen & Learn**: hands-free: hear the code, then the spoken answer; keeps
  playing with the screen locked (foreground service)
- **Voice answers**: speak your answer instead of tapping (microphone), with
  NATO/letter-name/digit-word matching, a "did you say…?" confirm-and-correct
  flow, and a learned per-user voice profile
- **Sending Drills**: printable practice sheets of random character groups
  drawn from what you've studied (even, personalized, or numbers & punctuation),
  ready to share or print
- **Progress**: daily streak, accuracy, best copy, per-character recognition
  chart, performance by 5-WPM speed band, and per-session detail screens with
  each session's own recognition chart
- **Settings**: character speed to 60 WPM, Farnsworth, sidetone pitch, a
  background noise floor (Whisper by default, so Bluetooth earbuds don't sleep
  through the first character), haptics, daily reminders, session length,
  custom word lists, punctuation opt-ins, and a slashed-zero display option
- Dark navy/teal theme, adaptive icon, phone + tablet responsive layout

## Build

Requires JDK 17 (Android Studio's bundled JBR works) and the Android SDK.

```bash
./gradlew assembleDebug     # debug APK → app/build/outputs/apk/debug/
./gradlew bundleRelease      # signed release AAB (needs keystore.properties, see RELEASE.md)
```

`compileSdk`/`targetSdk` 36, `minSdk` 24.

## Release

See [RELEASE.md](RELEASE.md) for signing and Google Play upload steps. The
signing keystore and `keystore.properties` are intentionally **not** committed.
