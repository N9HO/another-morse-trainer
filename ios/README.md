# Another Morse Trainer

Learn to copy Morse code (CW) by ear with the Koch method, in a native iOS app
built with **SwiftUI**, with its training logic in a Foundation-only Swift
package (`Sources/MorseKit`) so it can be unit-tested and ported. The Android
port is its sibling in this repo, at [`android/`](../android) — a separate,
independently-versioned Kotlin implementation, not a shared module.

**The beta is open.** Join on
[TestFlight](https://testflight.apple.com/join/ZwXF88Gh).

**It runs on the Mac too.** The same app target builds for the Mac through Mac
Catalyst, under the same bundle ID so one App Store purchase covers both
(universal purchase). The Mac build is not published yet; see
[On the Mac](#on-the-mac) for what differs there.

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
  drills, with custom word lists, the CWOps CW 77 list as a Common Words
  pool (with your own callsign and name if you like, and a one-tap 40 WPM,
  no-Farnsworth preset), and optional punctuation extras (a mark you turn on
  joins the Characters drill and the games' full set straight away)
- **CW 77**: the CWOps CW 77 list as a mode of its own, in two styles:
  Listen (hands-free, like Listen & Learn, and it keeps playing with the
  screen locked) or Quiz (scored, like Common Words, answered by choices,
  typing or keying). The include-my-callsign-and-name switch and the
  one-tap 40 WPM, no-Farnsworth preset sit on its setup sheet, and it
  remembers the style you last chose
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
  you route the defense by tapping that asset or typing the callsign before
  it arrives. Callsigns are call-like groups from your active set or real
  US calls, sent from 8 WPM under your character speed (Farnsworth honored)
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
- **CW Asteroids**: the sending-side arcade game — labeled asteroids drift
  in toward your ship; key each one's label to destroy it, or hear a label
  sent and tap the asteroid carrying it. From wave 3 larger asteroids carry
  short words and callsigns that split into their characters when hit.
  Waves, lives, combos, three difficulties, a gentle speed ramp in hear-it
  mode, and every hit and miss feeds your stats
- **Daily Dit**: one five-letter word a day, the same for everyone, sent in
  Morse at a speed you pick (up to 75 WPM) and walked down 5 WPM for every
  three listens and every three wrong guesses. Each guess is scored
  letter by letter, and the result shares as a card with the slowest speed
  you heard it at. **Past puzzles** lists the last 30 days — copied, not
  solved or missed — and opens any past day as practice, to catch up on one
  you missed or replay one you copied; practice counts toward no streak and
  never changes that day's result
- **First Four**: just enough CW for a brand-new operator to hunt one POTA
  activator — enter your callsign and state, then hear and send your call,
  your state, ? and 73, and work through three short scenes: a busted call
  (the activator sends a partial like K9?; you send your full call and wait),
  a call nobody answers, and a whole hunter-side contact. Offered from the
  first-run screen and the top of Home, a tutorial rather than a scored mode,
  and it ends with a nudge to thank your first CW contact
- **Operating Procedure**: the next step after First Four, on a card just
  below it on Home: CW etiquette for hunting POTA activators, one rule at a
  time, with your own callsign and state in every example. Eight short
  lessons: offsetting first, then signals, when to call, sending your call
  once, partial calls, "is it me?" (a near-miss of your call with a ? gets
  your call once; without one, silence), the exchange (5NN, state, 73, or
  WB0RLJ's RST, state, BK, 73, dit-dit), and fixing mistakes (an error can
  sound like anything, so the examples vary). Each has a concept card,
  right-and-wrong audio examples and a few quick scenarios. The offsetting
  lesson explains zero beat, RIT, XIT and CW pitch (with what Icom, Yaesu,
  Kenwood, Elecraft and FlexRadio call them), plays the same pileup three
  ways (everyone zero beat, only you offset, everyone offset), lets you move
  an RIT and hear what it does, and drills tuning to zero beat by ear. "What
  should you do?" deals ten situations from the lessons. Not a scored mode.
  After WB0RLJ's "Advice for CW POTA Hunters" (linked, with his YouTube
  channel of daily activations) and the POTA CW Guide
- **Pileup Runner**: call CQ and work a simulated pileup, with your own side
  keyed on the air: adjustable callers, speeds, QSB/QRN, cut numbers, bust
  behavior, callsign shapes, and a live log
- **Contest**: timed runs of the weekly CW events (K1USN SST, ICWC MST,
  CWops CWT, NCCC Sprint, ARRL Field Day) with authentic exchanges and speeds,
  a live score and rate, and an end-of-run scorecard
- **Code Exam**: FCC/ARRL-style copy test at 5 / 13 / 20 WPM, every passage
  carrying the letters, numerals, `. , ? /` and AR / SK / BT the real exams did,
  graded the ARRL VEC way: copy it, then fill in 10 blanks from your copy; pass
  on one minute of solid copy (25 / 65 / 100 in a row, numerals, punctuation and
  prosigns counting two) or 7 of 10 answers (random or a bundled passage)
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
  characters, the curated on-air QSO elements (Top 20 or Top 100), the
  CWOps CW 77 list (optionally with your own callsign and name), words, or
  abbreviations and Q-codes, spelled out with the full meaning or as the
  brief meaning alone; keeps playing with the screen locked, with the
  current item and the app logo on the lock screen and car displays
- **Voice answers**: speak your answer instead of tapping in any of the six
  choice quizzes, with a confirm/closest-match fallback that learns your
  corrections
- **Answer by keying**: key the answer on a touch or hardware Morse key
- **Keyboard-entry answers**: type the character or word you heard instead
  of picking it, in the choice quizzes, either always or as a progression
  step: each level starts at 4 choices, moves to 6, then to typing, and a
  wrong typed character feeds the Confusion Matrix like a wrong tap
- **On-screen paddles**: the on-screen key can be a straight key or a pair of
  touch paddles with a built-in Iambic A / Iambic B / Ultimatic keyer, dit and
  dah memory, and a left-handed swap — wherever you key on screen
- **A hardware key wherever you type**: a Vail Adapter or other MIDI key
  also works in the Pileup Runner and Contest (what you key goes in the box
  and is sent when you stop keying; key CQ to call CQ), Type It, QRQ Speed,
  Daily Dit, Journey and Morse Defender's typed copy, decoded as you key
  with sidetone
- **Sending Practice**: a dedicated hear-it, key-it-back mode on the adaptive
  ladder, with live decode, always-on replay, and a connected-MIDI-key
  readout; plus printable drill sheets built from what you've studied (even,
  personalized, or numbers & punctuation)
- **Sending Analyzer**: send a pangram, call signs, words, your studied
  groups or your own text on a straight key, bug, cootie or paddles, through
  the on-screen key (straight or paddles, per Settings; the on-screen paddles
  are judged as paddles), a Vail adapter or other MIDI key, or the microphone
  (an oscillator, keyer, rig or signal generator it can hear); get the copy
  lined up against the text with every error marked, character- and
  word-spacing histograms against 3 and 7 units (Farnsworth-aware), dit and
  dah lengths and their ratio, achieved speed, plain-English feedback judged
  per key type, and a running record of problem characters, pairs and mix-ups
- **Vail repeater**: live CW over the [Vail](https://vail.woozle.org) network
  with a server picker and private-channel option, plus Vail Adapter support:
  MIDI key input *and* output (keyer modes, speed, sidetone, RX piezo buzz),
  unplug detection, chat, and a signal timeline
- **Bluetooth LE MIDI keys**: paired from inside the app via the system MIDI
  sheet, which is the only thing on iOS that makes a BLE key visible to apps
  (on the Mac, connected once in Audio MIDI Setup, which the same button
  explains)
- **Bluetooth keep-alive and band noise**: a near-silent floor (on by
  default) that stops Bluetooth earbuds sleeping through the first character,
  and a separate band-noise level to copy through (heard only while a mode,
  game or lesson plays, with a short sample when the level is picked)
- **Leaderboard**: an opt-in shared board across both apps for Rapid Fire,
  Contest, Pileup Runner and the six games. The server grades each run's
  transcript itself and ranks on the speed summed over correct items (so a
  game's board number is not its on-screen score); every post is attested
  with App Attest, so only the genuine app on a real device can rank. Pick a
  callsign-shaped display name in Settings › Leaderboard & Buddy; "Delete my scores"
  removes everything the server holds for the device. The board itself is
  browsable by anyone, per mode, from the Games menu and from Your Stats,
  without sharing; the first time you start a game with sharing off, one
  prompt offers to turn it on, with "Not now" and "Don't ask again".
- **Buddy streak**: pair with up to ten people (on either app), each by a
  six-character invite code (single use, 24 hours), and keep a separate
  streak with each of the days you *both* practiced. Settings ›
  Leaderboard & Buddy › Buddy streak lists every buddy with their streak and whether they have
  practiced today, and leaves one buddy at a time. The home screen says who
  has practiced today (a switch under Buddy streak hides that line), and the daily reminder adds one nudge naming whoever
  hasn't (as of the app's last look). Uses the leaderboard display name and
  attestation; pairing itself is the opt-in.
- **Account & sync** (optional; nothing changes until you sign in): sign in
  with a link emailed to you, no password, and your sessions, practice days,
  lifetime totals, personal bests, streak and course progress (Journey
  position, the Characters ladder, First Four, CW Operating Procedure, story
  bookmarks) sync between your devices on any of the apps; a new install
  signed in to the same account restores them. Settings › Account & Sync
  shows your callsign and display name, when it last synced, Sync now, and
  every signed-in device, each of which you can sign out. Sign out keeps
  everything on this device; Delete account removes the account and all
  synced data from the server. Mode, sound and key settings are not synced
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
- **Settings** grouped into eleven categories (Sound, Speed & Timing,
  Characters & Lessons, Practice & Feedback, Keys & Sending, QSO & Pileups,
  Reminders, Display, Leaderboard & Buddy, Account & Sync, Help & About) with a search field
  that finds any setting by name or synonym and jumps straight to it
- **iPhone and iPad**: one universal app. On iPad it turns to any
  orientation and runs in Split View, Slide Over and resizable Stage Manager
  windows. A big window puts the home grid four across with Daily Dit and
  Start here side by side, opens Settings as a sidebar beside the category
  you are in, runs the Stats character and confused-pair lists two to a row,
  and gives the on-screen key more room; a narrow one is the phone layout.
  Text and quiz columns keep a readable width throughout. A hardware
  keyboard answers in the typing and choice modes as on the iPhone

## Project layout

- `MorseTrainerApp/`: the SwiftUI app (audio, UI, persistence)
- `Sources/MorseKit/`: pure training logic: engines, quizzes, contest and
  pileup simulation, exam grading, stats. No UIKit/SwiftUI imports.
- `Sources/MorseKitCheck/`: a command-line harness exercising MorseKit
  (`swift run MorseKitCheck`)
- `tools/`: App Store release script + App Store Connect helpers (and the
  `whatsnew/` release notes App Review reads), Discord triage bot

## On the Mac

The Mac build is the iPhone app built for Mac Catalyst with the Mac interface
idiom (`SUPPORTS_MACCATALYST`, `TARGETED_DEVICE_FAMILY[sdk=macosx*] = 6`), so
there is one target and one code base; the Mac-only code is in
`MorseTrainerApp/MacCatalystSupport.swift`. What differs:

- **Bluetooth LE keys** are connected in macOS's Audio MIDI Setup (Window ›
  Show MIDI Studio, then the Bluetooth button), not in the app: the iOS
  pairing sheet does not exist on the Mac. The "Bluetooth key" buttons open a
  short how-to instead. USB MIDI keys and the Vail Adapter work as soon as
  they are plugged in, through CoreMIDI as on iOS.
- **The keyboard** works everywhere the iPhone app takes a hardware keyboard:
  typed answers, the number keys and Return for the choice quizzes, the games'
  letter keys, and Head Copy's R, Return and X.
- **The window** can be resized down to phone width; wider than that, the
  screens keep their readable column, and a wide window gets the iPad's
  big-screen layout (the home grid four across, Settings with a sidebar).
  File › New Window is removed (one session, one audio engine), and Help
  opens the user guide.
- **The leaderboard** needs App Attest to post. Where the Mac cannot attest,
  the boards stay readable and a run says why it was not posted, as in the
  simulator.
- **Listen & Learn** keeps playing in the background: a Mac app is not
  suspended when its window is not in front.
- **Sandbox**: the Mac build is sandboxed with network-client and
  microphone entitlements (`Config/MorseTrainer-macCatalyst.entitlements`).
- **Icon and category**: the Mac icon slots in `AppIcon.appiconset` are the
  iOS master drawn on the macOS icon grid (`tools/gen_mac_icon.py`; re-run it
  after changing `icon_1024.png`), and `LSApplicationCategoryType` files the
  app under Education. The minimum is macOS 13 (`MACOSX_DEPLOYMENT_TARGET`,
  the Catalyst counterpart of iOS 16).
- **Shipping it**: the release workflow takes `platform: maccatalyst`
  (TestFlight channel only for now), which archives for Mac Catalyst, uploads
  a signed `.pkg` and points `asc-api.py` at the Mac build
  (`ASC_PLATFORM=MAC_OS`). App Store Connect numbers builds per platform, so
  the Mac build shares `CURRENT_PROJECT_VERSION` with the iPhone one without
  colliding. Testers read `tools/whatsnew/whatsnew-mac-en-US` when it exists.
  The run is tagged `mac-beta-v<version>-b<build>`, which the Discord
  announcer ignores. Dry run first:
  `gh workflow run ios-release.yml --ref main -f dry_run=true -f platform=maccatalyst -f channel=testflight`.
- **The download**: `channel: developer-id` with `platform: maccatalyst`
  builds the Mac app for a GitHub release instead: Developer ID-signed,
  notarized, stapled and zipped as the `mac-developer-id` artifact
  (`AnotherMorseTrainer-Mac.zip`). Nothing goes to App Store Connect, no
  build number is spent and no tag is made. It signs with the Developer ID
  certificate in the `DEVID_APP_P12` secrets, because cloud signing cannot
  do Developer ID with an API key. A Developer ID profile cannot carry App
  Attest, so the download reads the leaderboard but cannot post to it:
  `gh workflow run ios-release.yml --ref main -f platform=maccatalyst -f channel=developer-id`.

## Build

Open `MorseTrainer.xcodeproj` in Xcode and run the `MorseTrainer` scheme, or
build the logic package alone with:

```bash
swift build
swift run MorseKitCheck
```

For the Mac, pick the "My Mac (Mac Catalyst)" destination in Xcode, or:

```bash
xcodebuild build -project MorseTrainer.xcodeproj -scheme MorseTrainer \
  -destination 'platform=macOS,variant=Mac Catalyst' CODE_SIGNING_ALLOWED=NO
```

CI (`.github/workflows/ios.yml`) builds the package, the iOS app and the Mac
Catalyst app on every push, so changes made away from a Mac still get
compile-checked.

## License

GPL-3.0-or-later, per the repository's root [LICENSE](../LICENSE) and the License
section of the [top-level README](../README.md). The vendored CW decoder under
`Sources/CWDecoderCore/` is MIT and keeps its own `LICENSE` and
`PROVENANCE.md`.
