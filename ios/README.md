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
  drills, with custom word lists and optional punctuation extras (an
  opted-in mark joins the Characters ladder after the core set, and the
  games' full set straight away)
- **Confusion Drill**: targeted review of the pairs you actually mix up
- **Head Copy**: copy in your head with auto-repeats and a timed reveal
- **Type It / QRQ Speed**: free-recall typing, plus high-speed copy at
  35 / 40 / 50 / 60 WPM on its own speed setting
- **Rapid Fire**: call signs / words / number groups / states (optionally
  with ARRL/RAC Field Day sections) / contest serials (cut numbers optional)
  / names / power, sent back to back at your pace; type, head-copy, key each
  one back, or just listen
- **Games**: the six arcade games below sit behind one Games tile on the
  home screen (and under a Games heading in the mid-session mode switcher),
  so the main menu stays short as more games are added
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
  and steps up 2 WPM every five hits (a landing steps it back). Waves,
  lives, combos, three difficulties, and every hit and miss feeds your stats
- **Morse Defender**: an arcade callsign-copy game — cities and ships with
  callsigns line the bottom; each attacker sends its target's callsign and
  you route the defence by tapping that asset or typing the callsign before
  it arrives. Callsigns are call-like groups from your active set or real
  US calls, sent from 8 WPM under your character speed (Farnsworth honoured)
  and stepping up 2 WPM every four hits. Assets are the lives (4 growing to
  8), up to three attackers at once, waves, combos, three difficulties, and
  every character copied feeds your stats and confusion matrix
- **CW Dungeon**: a small roguelike — monsters cast spell words in Morse;
  copy the spell, then key its counter word (the spell book is on screen) on
  the on-screen or a hardware key before the attack lands. Counters hurt the
  monster, some heal you, a wrong or late one costs a life. Rooms, bosses,
  combos, three difficulties, a gentle speed ramp with your Farnsworth
  spacing, spells tiered to your active Koch set, and every keyed character
  feeds your stats
- **CW Frogger**: an arcade crossing — hop a frog over three lanes of traffic
  and three of river. Every vehicle and log carries a character and each lane
  is cued in Morse: only the cued vehicle is harmless, only the cued log
  floats. Labels hide as the waves go on until the traffic announces itself in
  Morse. Three lives, waves, combos, three difficulties, its own gentle speed
  ramp, and every lane decision feeds your stats and confusion matrix
- **CW Asteroids**: the sending-side arcade game — labelled asteroids drift
  in toward your ship; key each one's label to destroy it, or hear a label
  sent and tap the asteroid carrying it. From wave 3 larger asteroids carry
  short words and callsigns that split into their characters when hit.
  Waves, lives, combos, three difficulties, a gentle speed ramp in hear-it
  mode, and every hit and miss feeds your stats
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
  abbreviations and Q-codes, spelled out with the full meaning or as the
  brief meaning alone; keeps playing with the screen locked, with the
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
- **Leaderboard**: an opt-in shared board across both apps for Rapid Fire,
  Contest, Pileup Runner and the six games. The server grades each run's
  transcript itself and ranks on the speed summed over correct items (so a
  game's board number is not its on-screen score); every post is attested
  with App Attest, so only the genuine app on a real device can rank. Pick a
  callsign-shaped display name in Settings › Leaderboard; "Delete my scores"
  removes everything the server holds for the device.
- **Progress**: daily streak with milestone celebrations, a GitHub-style
  activity grid of daily practice time, session history with per-session
  recognition charts, per-character stats, most-confused pairs, performance
  by 5-WPM speed band, a shareable Brag Sheet, and personal bests including
  your best score in each arcade game, Contest, Pileup Runner and Rapid Fire
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
