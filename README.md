# Another Morse Trainer — Android

A native Android port of [Another Morse Trainer](https://anothermorsetrainer.app)
(the SwiftUI iOS app at [N9HO/another-morse-trainer](https://github.com/N9HO/another-morse-trainer)).
Learn to copy Morse code (CW) by ear with the Koch method.

Built with **Kotlin + Jetpack Compose**. The training logic (`morsekit`) is a
near 1:1 port of the iOS MorseKit package; the UI is rebuilt in Compose to match
the iOS app's navy/teal look.

## Features

- **Journey** — gamified, level-based path (letters → numbers → punctuation →
  prosigns → Q-codes → abbreviations → words → call signs) with a progress bar
  that fills on a hit and drains on a miss, an unlock map, and saved progress
- **Characters** — Koch-method ladder (A–Z, 0–9)
- **Common Words**, **Abbreviations**, **Q-Codes**, **Prosigns** — phrase drills
- **Confusion Drill** — targeted review of the pairs you mix up
- **Rapid Fire** — a stream of call signs / words / number groups / states sent
  back to back at your chosen pace; type as you hear it, head-copy then type, or
  just listen and review the transmitted list
- **Pileup Runner** — call CQ and work a simulated CW pileup
- **Code Exam** — FCC/ARRL-style copy test at 5 / 13 / 20 WPM
- **Sending Practice** — key it back (touch or MIDI key)
- **Repeater** — live CW over the Vail network, with Vail Adapter support: MIDI
  key input *and* output (keyer-mode config, sidetone, RX piezo buzz)
- **Reference** — browsable, tap-to-hear chart of prosigns, Q-codes,
  abbreviations, cut numbers, and the full alphabet, with per-signal detail
- **Listen & Learn** — hands-free: hear the code, then the spoken answer; keeps
  playing with the screen locked (foreground service)
- **Voice answers** — speak your answer instead of tapping (microphone)
- **Progress** — daily streak, accuracy, best copy, per-character recognition chart
- **Settings** — speed, Farnsworth, sidetone pitch, haptics, daily reminders
- Dark navy/teal theme, adaptive icon, phone + tablet responsive layout

## Build

Requires JDK 17 (Android Studio's bundled JBR works) and the Android SDK.

```bash
./gradlew assembleDebug     # debug APK → app/build/outputs/apk/debug/
./gradlew bundleRelease      # signed release AAB (needs keystore.properties — see RELEASE.md)
```

`compileSdk`/`targetSdk` 35, `minSdk` 24.

## Release

See [RELEASE.md](RELEASE.md) for signing and Google Play upload steps. The
signing keystore and `keystore.properties` are intentionally **not** committed.
