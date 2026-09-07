# Another Morse Trainer

Learn to copy Morse code (CW) by ear with the Koch method, in a native iOS app
built with **SwiftUI**, with its training logic in a Foundation-only Swift
package (`Sources/MorseKit`) so it can be unit-tested and ported. The Android
port is its sibling in this repo, at [`android/`](../android) — a separate,
independently-versioned Kotlin implementation, not a shared module.

**The beta is open.** Join on
[TestFlight](https://testflight.apple.com/join/ZwXF88Gh).

The user guide, covering every mode, setting and hardware option, lives at
[anothermorsetrainer.app/guide](https://anothermorsetrainer.app/guide/), and
testers, bug reports and feature chat live on
[Discord](https://discord.gg/qgyk3TPUd9).

## Features

- **Journey**: gamified, level-based path (letters → numbers → punctuation →
  prosigns → Q-codes → abbreviations → words → call signs) with a progress bar
  that fills on a hit and drains on a miss (toggleable), an unlock map, and
  saved progress
- **Characters**: Koch-method ladder (A-Z, 0-9) with a user-pinnable
  "Track stage" (characters, pairs, triples, words & call signs)
- **Common Words**, **Abbreviations**, **Q-Codes**, **Prosigns**: phrase
  drills, with custom word lists and optional punctuation extras
- **Confusion Drill**: targeted review of the pairs you actually mix up
- **Head Copy**: copy in your head with auto-repeats and a timed reveal
- **Type It / QRQ Speed**: free-recall typing, plus high-speed copy at
  35 / 40 / 50 / 60 WPM on its own speed setting
- **Rapid Fire**: call signs / words / number groups / states (optionally
  with ARRL/RAC Field Day sections) / contest serials (cut numbers optional)
  / names / power, sent back to back at your pace; type, head-copy, key each
  one back, or just listen
- **Morse Invaders**: an arcade game — pixel-art invaders descend carrying
  characters; hear one and type it on a QWERTY keyboard, or see one and key
  it, before it lands. Each game starts 10 WPM under your character speed
  and steps up 1 WPM after six hits in a row (a landing steps it back 2);
  characters you miss come round more often until you master them again.
  Waves, lives, combos, three difficulties, and every hit and miss feeds
  your stats
- **CW Galaga**: the formation game — enemies swoop in along curved paths,
  settle into a formation and dive at you one by one; hear one and type it,
  or see one and key it, to shoot the most dangerous enemy carrying it before
  its dive gets through. Its own ramp (8 WPM under your
  character speed, up 1 WPM every six hits), a combo multiplier up to ×8,
  bigger formations every wave, three lives, three difficulties, and every
  hit and miss feeds your stats
- **Pileup Runner**: call CQ and work a simulated pileup, with your own side
  keyed on the air: adjustable callers, speeds, QSB/QRN, cut numbers, bust
  behavior, callsign shapes, and a live log
- **Contest**: timed runs of the weekly CW events (K1USN SST, ICWC MST,
  CWops CWT, NCCC Sprint, ARRL Field Day) with authentic exchanges and speeds,
  a live score and rate, and an end-of-run scorecard
- **Code Exam**: FCC/ARRL-style copy test at 5 / 13 / 20 WPM (solid copy or
  content questions)
- **Short Stories**: continuous copy of a public-domain fable (32 bundled), a
  longer classic sent in parts with a bookmark that keeps your place, or todays
  news: real RSS headlines sanitized to sendable Morse and hidden until you
  reveal them
- **Reference**: browsable, tap-to-hear chart of prosigns, Q-codes,
  abbreviations, ham lingo, cut numbers, and the full alphabet, with
  per-signal detail
- **CW Decoder**: point the microphone at received Morse (a rig's speaker, a
  WebSDR) and read it as text, with live WPM/pitch telemetry, a two-core
  pitch-lock rescue, and a noise blanker that keeps QRN static crashes from
  reaching the decoder as marks
- **Listen & Learn**: hands-free: hear the code, then the spoken answer, over
  characters, the curated on-air QSO elements (Top 20 or Top 100), words, or
  abbreviations and Q-codes; keeps playing with the screen locked, with the
  current item and the app logo on the lock screen and car displays
- **Voice answers**: speak your answer instead of tapping in any of the six
  choice quizzes, with a confirm/closest-match fallback that learns your
  corrections
- **Answer by keying**: key the answer on a touch or hardware Morse key
- **Sending Practice**: a dedicated hear-it, key-it-back mode on the adaptive
  ladder, with live decode, always-on replay, and a connected-MIDI-key
  readout; plus printable drill sheets built from what you've studied (even,
  personalized, or numbers & punctuation)
- **Vail repeater**: live CW over the [Vail](https://vail.woozle.org) network
  with a server picker and private-channel option, plus Vail Adapter support:
  MIDI key input *and* output (keyer modes, speed, sidetone, RX piezo buzz),
  unplug detection, chat, and a signal timeline
- **Bluetooth LE MIDI keys**: paired from inside the app via the system MIDI
  sheet, which is the only thing on iOS that makes a BLE key visible to apps
- **Bluetooth keep-alive and band noise**: a near-silent floor (on by
  default) that stops Bluetooth earbuds sleeping through the first character,
  and a separate band-noise level to copy through
- **Progress**: daily streak with milestone celebrations, a GitHub-style
  activity grid of daily practice time, session history with per-session
  recognition charts, per-character stats, most-confused pairs, performance
  by 5-WPM speed band, and a shareable Brag Sheet
- Character speed adjustable to 60 WPM, with Farnsworth spacing tracking it
- Timed practice sessions (1-30 min or open-ended) with mid-session timer
  controls and an end-of-session summary, and a mode switcher that jumps
  between drills without going home
- A first-run question about how much Morse you already know, which seeds
  the Characters ladder and unlocks the Journey that far
- Daily practice reminders (minute precision, streak-aware)

## Project layout

- `MorseTrainerApp/`: the SwiftUI app (audio, UI, persistence)
- `Sources/MorseKit/`: pure training logic: engines, quizzes, contest and
  pileup simulation, exam grading, stats. No UIKit/SwiftUI imports.
- `Sources/MorseKitCheck/`: a command-line harness exercising MorseKit
  (`swift run MorseKitCheck`)
- `tools/`: TestFlight upload + App Store Connect helpers, Discord triage bot

## Build

Open `MorseTrainer.xcodeproj` in Xcode and run the `MorseTrainer` scheme, or
build the logic package alone with:

```bash
swift build
swift run MorseKitCheck
```

CI (`.github/workflows/ios.yml`) builds both the package and the app on every
push, so changes made away from a Mac still get compile-checked.

## License

GPL-3.0-or-later, per the repository's root [LICENSE](../LICENSE) and the License
section of the [top-level README](../README.md). The vendored CW decoder under
`Sources/CWDecoderCore/` is MIT and keeps its own `LICENSE` and
`PROVENANCE.md`.
