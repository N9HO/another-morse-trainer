# Brass Knuckles Knock Out: design notes

Status: **rules built on every app; screens not built.** Written 2026-10-27
from a feasibility pass (Justin R. / N9HO, Discord thread with Derick,
KO6PBZ). What exists: the game's rules ported to `BrassKnuckles.swift` /
`BrassKnuckles.kt` (iOS, Android, desktop) and pinned by
`fixtures/brass-knuckles.json`. What does not: any screen, sprite, sound,
Games-screen entry, leaderboard registration or Licenses entry, so the game is
not reachable by a user yet. The numbers marked *(from source)* are taken from
the game's own source; the rest is a proposal for the maintainer to change
before the screens are built.

Settled before this note was written:

- It is a **native port** on each app, in the idiom of that app, as a seventh
  arcade-style mode alongside Invaders, Galaga, Defender, Dungeon, Frogger and
  Asteroids. No WebView, no WebAssembly, no shared code
  module (`CLAUDE.md`, "separate ports, not one").
- It uses **AMT's existing keyer**, decoder and timing, not the game's own.
  The game's *numbers* (damage, tell lengths, speeds, ladder) are kept.
- It goes on the **AMT leaderboard**, which needs server work (§6). Local
  personal bests ship first.
- **Branding stays.** The mode is titled *Brass Knuckles Knock Out, Sponsored
  by The Brass Knuckle Gang*. The title splash gives prominent credit to
  Derick (KO6PBZ), and credits Kristen (K5MME) as well.
- The original author has said he is not making money from it and is happy
  for it to go in AMT (Discord, 2026-10-27). Both codebases are
  GPL-3.0-or-later.

## What it is

A Punch-Out-style boxing game that teaches Morse. Eight fighters, from
LID LARRY to BARON VON KILOWATT, add characters in Koch order as the player
climbs the ladder. Each opponent **telegraphs a punch by sending a character
or two** (the *tell*). The player keys it back before the punch lands:

- A correct, timely answer is a **dodge**; the opponent is then off balance
  and open.
- While open, every character the player sends is a **punch**: all dits jab,
  all dahs hook, a mix throws a combo. Cleaner timing hits harder.
- Clean counters earn **stars** (3 clean counters per star, up to 3). A star
  buys an **uppercut**: send a whole word or callsign without an error.
- A fight is 3 rounds of 90 seconds; three knockdowns end it; the player has
  100 HP *(all from source, `logic/boxing.rs`)*.
- Later fighters send faster, drop the on-screen hint, add static and fading,
  and lean on the characters the player copies worst.

## What the source gives us

The author sent the whole source with the game (`source/` in the zip; the
firmware it came from is `github.com/kvmet/k5mme_pocketcw`, v0.4.0).

| Part | Size | Port as |
|---|---|---|
| `logic/src/boxing.rs`: fighters, rounds, punches, damage, stars, uppercut, career record | 1,747 lines | The rules, one file per port (`Boxing.swift`, `Boxing.kt`), the same shape as `Asteroids`. |
| `firmware/src/ui/boxing.rs`, `ui/ring.rs`: title, fighter select, intro card, fight, results | ~1,700 lines | One screen per app (SwiftUI, Compose, Compose Desktop). |
| `firmware/src/sprites.rs`, `art.rs`: pixel art | generated | Regenerated from `tools/boxing_art.py` into each port's own data format. |
| `logic/src/{keyer,decoder,morse,timing,letters,synth,rng}.rs` | n/a | **Not ported.** AMT already has `PaddleKeyer`, `MorseDecoder`, `MorseCode`, `MorseTiming`, `CharacterStats` and `MorseSynth`. |

The rules are driven entirely by a millisecond tick, a seeded RNG and the
state machine's `update`/`deadline`; the screen only reads state to draw it.
That is the same discipline as `Asteroids.kt` ("pure game rules, no clock, no
audio, no UI; time enters through `advance`"), so the port follows the
existing game template rather than inventing one.

## Architecture per app

Same names and rules on every app, each in its own language, as for every
other game. A fix is three edits.

- **Rules** (`MorseKit` on iOS; `morsekit/` on Android and desktop):
  `BoxingGame` with `advance(ms)`, an injected random source, and events out
  (`TellSent`, `Dodged`, `Struck`, `Punched`, `Knockdown`, `RoundEnd`,
  `FightEnd`), mirroring `AsteroidsEvent`. The Swift and Kotlin files say
  "translated from ... the two must stay twins", as Asteroids does.
- **Screen**: `BoxingScreen` / `BoxingView`. Draws the ring and fighters,
  plays the tell through `MorseSynth`, reads the player's keying through the
  same path Asteroids' send mode uses (`PaddleKeyer` → `MorseDecoder`, fed by
  the on-screen key, a USB or Bluetooth MIDI key, or the keyboard).
- **Drawing**: the original draws a 320x240 RGB565 frame. I propose each app
  draws the ring into its own native canvas at that logical size and scales
  it up, using the sprite data. That keeps the look identical on all three
  without writing the screens three different ways; if a native layout is
  wanted instead, it is a larger job and should be decided separately.
- **Registration** (same as every game): a mode case in the app's mode enum
  (`TrainingMode` on Android and desktop, the mode enum in `AppModel.swift`
  on iOS), a `Route`, an entry on the Games screen, a `Stats.record` mode
  string, a row in the Stats screen's personal bests, and a line in each
  `README.md`.
- **Mode string**: `"Brass Knuckles"`, server id `brassKnuckles`.

## Using AMT's keyer

The game's tells are sent in the sidetone, and the player's answer arrives as
decoded characters, exactly as in Asteroids' send mode. What changes from the
original:

- **Timing quality** (what the original calls `Quality`, which scales damage)
  comes from AMT's `MorseTiming` and the decoder's timing result. The
  thresholds are the game's own numbers (to be read from the source's timing
  code when the rules are ported, not yet checked) applied to AMT's
  measurement. A fixture pins that mapping.
- **Dit/dah punch type** is read from the decoded character's pattern
  (`MorseCode`), the same table every mode uses.
- **Iambic mode and squeeze** are AMT's, set in Settings as for the other
  games. The original's own keyer settings are dropped.
- **Letter stats**: the game's "tells lean on your worst characters" uses
  AMT's `CharacterStats`, so it agrees with the rest of the app and feeds the
  same per-character data. Open point: whether fight results also *write* to
  those stats; see Open questions.

## Credits and the title splash

The title splash, on all three apps, shows:

> **BRASS KNUCKLES KNOCK OUT**
> *Sponsored by The Brass Knuckle Gang*
> Created by **Derick, KO6PBZ**
> Built on the HamCube PocketCW firmware by Kristen, K5MME

KO6PBZ is the most prominent line after the title. The splash keeps the
original's impact-star animation and brass knuckles logo where the art
allows.

Settings › Help & About › Licenses (every app, per `CLAUDE.md`) gets:

- the game's copyright holders and GPL-3.0-or-later notice, naming KO6PBZ and
  K5MME, with a link to [Kristen's PocketCW firmware](https://github.com/kvmet/k5mme_pocketcw);
- the **B612 font** OFL notice, if the font is bundled (it ships with the
  source as `B612-OFL.txt`);
- no MIT notices for the Rust crates (`byteorder`, `embedded-graphics`) unless
  code from them is actually copied; the port draws natively.

Before the pull request, get the author's "OK to ship in AMT's apps" in
writing (an email or message is enough), and confirm each copyright holder.
The Discord thread is good evidence but not explicit about app stores.

## Fixtures (built)

`fixtures/brass-knuckles.json`, read by every port in its own idiom
(`BrassKnucklesTest.kt` on Android and desktop, a section of
`MorseKitCheck/main.swift` on iOS). Its expected values come from the game's
own reference implementation, pocketcw-logic 0.4.0, run on a seed and a
script, so they are independent of all three of our ports (the rule in
`CLAUDE.md`). `fixtures/brass-knuckles-oracle.rs` is the generator; it is not
built by any workflow and says how to re-run it.

It pins: the eight fighters (every field), tell speeds, the punch type,
damage at each timing quality and dot length of all 41 characters, the
xorshift32 generator and the US calls built from it, 12 tells and 10 uppercut
words per fighter, the career rules, the worst-characters order, and **eleven
scripted fights** (dodge and counter, a wrong answer, too slow, a star and an
uppercut, a wasted star, three knockdowns to a TKO, getting up, staying down,
the bell and a decision, pause and resume, a pause mid-tell, the last fighter)
as a snapshot after every step. A port that plays any of them differently
fails.

The ports use the game's own generator and tick arithmetic, not AMT's, so a
seed plays out the same everywhere.

## Personal bests and the leaderboard

**Local first.** The mode records a score through `Stats.record` like the
other games, so `mode-bests.json`'s fold rule gives it a personal best with no
new code. The on-screen number is the original's fight score.

**Leaderboard needs the server.** The server is in its own repository
(`high-scores-design.md` §7), and a mode it doesn't know is never submitted
(`Leaderboard.modeIds`). So:

1. Add `"Brass Knuckles" to "brassKnuckles"` to `modeIds`, and to
   `rampingModes`, on every app, once the server accepts it.
2. The server grades a transcript (`{sent, answered, reactionMs}` per item),
   not the on-screen score. For this game the natural transcript is each
   tell and the player's answer with its reaction time, plus punches sent.
   The ranked number is then the server-graded figure, which will not equal
   the on-screen score (the same trade-off the doc already accepts for the
   arcade games).
3. That needs a change in the separate server repo: a new mode id, its
   ramping flag, and its grading rule. **I haven't seen that repo.** I'd need
   access to it, or the maintainer makes that change, before step 1 ships.

So the app work can land with local bests and no board, and the board follows
when the server is ready. The Leaderboard screen lists nine ranked modes
today; this makes ten.

## Parity

Every row is "shipped on iOS, Android and desktop". Known platform notes:

- **Bluetooth key**: not on desktop (an existing documented exception for the
  arcade games, `PARITY.md` row 14). The game inherits it; add the game to
  that row's list on the apps affected. iOS and Android pair as for the other
  games.
- **Haptics** and any sound-session handling follow what the other games do
  on each platform; no new exceptions are expected.
- The desktop app has never been run on real hardware with audio and MIDI, so
  desktop verification of this game is CI and screenshots only, as for the
  others.

The PR touches all three trees, so the merge gate passes with the "all
platforms in this PR" box. `PARITY.md` gains the mode in its mode lists, and
`ios/README.md`, `android/README.md` and `desktop/README.md` each gain a
bullet beside CW Asteroids.

## The guide and the version

- **User guide.** The guide at anothermorsetrainer.app/guide lives outside
  this repo, and `CLAUDE.md` says a feature is not done until its guide
  section says the new thing. The PR's Guide box needs a section for this
  mode: how to play, the controls, the credits. Someone with access to the
  guide's source has to write it.
- **Version.** Not changed by this work. Moving the shared version sends the
  Apple builds back through Beta App Review and is the maintainer's call.

## How it gets verified

Per `CLAUDE.md`, nobody can run Android or desktop locally and nobody can
hear any of the apps, so:

- The rules and the scripted fight are pinned by fixtures, with values
  derived from the documented rules rather than captured from a port.
- Each new test gets a negative control (perturb the expectation in a
  throwaway commit, confirm the named test fails in CI, revert).
- The fight's audio (tells, sidetone, static) and the feel of the keying are
  **unheard by anyone** until the maintainer plays it on a device. That
  belongs in the PR description.
- The screen is checked on desktop only by the Xvfb launch screenshot, so the
  fight needs a deterministic seed and tick for a stable screenshot.

## Build order

1. **Done:** rules port on Android, with `fixtures/brass-knuckles.json` and unit tests.
2. **Done:** Swift twin and desktop twin of the rules; all three pass the same fixture.
   (Android and desktop tests have been run, the Swift harness has not: see the PR.)
3. Android screen, then iOS, then desktop, each reusing its app's keying path.
4. Title splash, credits, Licenses entries, README and `PARITY.md` rows.
5. Leaderboard registration once the server repo is ready.

Each step is a separate commit; the whole thing is one feature in one pull
request, or three if the reviewer prefers them split per app with paired
issues, as `CLAUDE.md` allows.

## Risks

- **Sprite and animation volume.** The pixel art is the bulk of the
  presentation. Generating it from the author's Python grids keeps it exact,
  but each port needs its own loader and renderer; this is the piece most
  likely to run over.
- **Timing feel.** The original was tuned for the device's keyer. Re-using
  AMT's keyer keeps the app consistent but may change difficulty; the
  fixtures pin the arithmetic, not the feel. Plan for a tuning pass.
- **Leaderboard grading** is unscoped until the server side is seen.
- **Frame rate on desktop under Xvfb** is untested; screenshot tests will
  need a deterministic seed and tick.

## Open questions

1. Should a fight write to AMT's per-character stats, or only read them?
   Reading only is the conservative default; I have not checked what the
   other arcade games do.
2. Server repo access for the leaderboard grading rule (§6).
3. Permission is now explicit: Derick wrote "if you wanna use it please do!"
   and asked Justin to host it (Discord, 2026-10-27). Still to confirm: that
   Kristen (K5MME) is fine with being named, and the exact copyright holders
   to list.
4. Whether any of the original's own settings (volume, static level) should
   be exposed, or all of it folded into AMT's Settings.
