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
  that fills on a hit and drains on a miss (toggleable), an unlock map, and
  saved progress
- **Characters**: Koch-method ladder (A-Z, 0-9) with a user-pinnable
  "Track stage" (characters, pairs, triples, words & call signs)
- **Common Words**, **Abbreviations**, **Q-Codes**, **Prosigns**: phrase
  drills, with custom word lists and optional punctuation extras (a mark
  you turn on joins the Characters drill and the games' full set straight
  away)
- **Confusion Drill**: targeted review of the pairs you mix up
- **Head Copy**, **Type It**, **QRQ Speed**: copy in your head with
  auto-repeats and a timed reveal, free-recall typing, and high-speed copy at
  35 / 40 / 50 / 60 WPM on its own setting
- **Rapid Fire**: a stream of call signs / words / number groups / states
  (optionally with ARRL/RAC Field Day sections) / contest serials (cut
  numbers optional) / names / power sent back to back at your chosen pace;
  type as you hear it, head-copy then type, key each one back, or just listen
  and review the transmitted list
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
- **Code Exam**: FCC/ARRL-style copy test at 5 / 13 / 20 WPM (solid copy or
  content questions, random or a bundled passage)
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
  decoder core with live WPM/pitch telemetry, a two-core pitch-lock rescue,
  and a noise blanker that keeps QRN static crashes from reaching the decoder
  as marks
- **Listen & Learn**: hands-free: hear the code, then the spoken answer, over
  characters, the curated on-air QSO elements (Top 20 or Top 100), words, or
  abbreviations and Q-codes, spelled out with the full meaning or as the
  brief meaning alone; keeps playing with the screen locked (foreground
  service), with the current item and the app logo on the lock screen and car
  displays
- **Voice answers**: speak your answer instead of tapping (microphone), with
  NATO/letter-name/digit-word matching, a "did you say…?" confirm-and-correct
  flow, and a learned per-user voice profile
- **Sending Drills**: printable practice sheets of random character groups
  drawn from what you've studied (even, personalized, or numbers & punctuation),
  ready to share or print
- **Progress**: daily streak with milestone celebrations, a GitHub-style
  activity grid of daily practice time, session history with per-session
  recognition charts, per-character stats, most-confused pairs, performance
  by 5-WPM speed band, a shareable Brag Sheet, and personal bests including
  your best score in each arcade game, Contest, Pileup Runner and Rapid Fire
- **Leaderboard**: an opt-in shared board across both apps for Rapid Fire,
  Contest, Pileup Runner and the six arcade games, reached from Progress.
  Off by default; turn it on in Settings › Leaderboard with a display name.
  Each finished run's transcript (what was sent, what you answered) is graded
  by the server, which ranks the speed summed over the items you copied
  correctly, so a game's board number is not its on-screen score. Posting
  needs a genuine Play-installed build (Play Integrity); a "Delete my scores"
  button removes everything this install posted
- Timed practice sessions (1-30 min or open-ended) with mid-session timer
  controls and an end-of-session summary, and a mode switcher that jumps
  between drills without going home
- Daily practice reminders (streak-aware; inexact by a few minutes, see
  [PARITY.md](../PARITY.md))
- **Settings**: character speed to 60 WPM, Farnsworth, sidetone pitch, a
  Bluetooth keep-alive floor (on by default, so earbuds don't sleep through
  the first character) and a separate band-noise level to copy through,
  haptics, daily reminders, session length, custom word lists, punctuation
  opt-ins, and a slashed-zero display option
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

## License

GPL-3.0-or-later, per the repository's root [LICENSE](../LICENSE) and the License
section of the [top-level README](../README.md). The vendored CW decoder port
under `morsekit/cw/` is MIT and keeps its own `PROVENANCE.md`.
