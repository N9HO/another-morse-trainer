# First Four: design notes

Status: **built on both apps** in the pull request that closes #265, for the
maintainer to review; the defaults below are the ones the code ships with and
every one of them is a constant that can be changed in one place per port.
Written 2026-09-30 for issue #265 (Justin R., via Discord): a guided beginner
mode that teaches a brand-new operator the minimum CW needed to **hunt one
POTA activator** — and nothing more.

Settled on the issue before this note was written:

- It is **its own mode**, not a preset of the Pileup Runner (the QSO
  Simulator). The Pileup Runner puts you in the activator's chair; First Four
  puts you on the other side, as the hunter, and is a tutorial, not a drill.
- It is surfaced **early**: to a brand-new user, not behind the mode grid.
- It **ends with a nudge** to follow up with the first real CW contact — a
  QSL, an email, a thank-you — because for many operators that first contact
  is a big deal, and saying so to the activator is part of the hobby.

## What it is

The learner enters their callsign and their state. The mode then teaches,
in both directions (copy by ear, send on a key), exactly four things:

1. their own **callsign**,
2. their **state** abbreviation,
3. **?** (the question mark — "say again", and what an activator sends when
   they only caught part of a call),
4. **73** (best regards — how a contact ends).

It then puts those four to work in three short scripted scenes: a **busted
call** (the activator sends `N9?`; you send your full call and wait), an
**unanswered call** (you call and nothing comes back, or someone else is
answered; that is normal), and a **walkthrough** of a whole minimal hunter-side
POTA contact. Finishing the walkthrough ends on the follow-up nudge.

## What it is not

- Not general proficiency. No Koch ladder, no character stats, no confusion
  matrix feed. A learner who passes First Four can make one contact; the rest
  of the app is where they learn Morse.
- Not ranked. It is a tutorial with a fixed script: there is no score worth
  comparing, so it is not a leaderboard mode and submits nothing
  (`docs/high-scores-design.md`, "Which modes are eligible").
- Not a session. It writes no `SessionRecord`: a tutorial step would put a
  row in the history list and skew every lifetime average. Like Daily Dit it
  **does** count as practice for the day — a graded answer marks the streak
  (`markPracticedToday` on iOS, `Stats.recordPracticeDay` on Android), and so
  the buddy streak too.
- Not a gate. Every stage can be opened at any time from the stage list; the
  list recommends an order and "Next" follows it, but nothing is locked. A
  newcomer who already knows their call cold can skip straight to the
  walkthrough.

## The stages

Seven stages, in this order. The first four are **element stages**; each has a
copy phase and then a send phase. The last three are **scenes**: short scripts
of beats (below).

| # | Stage | What the learner does | Passes when (default) |
|---|---|---|---|
| 1 | Your call | Copy: hear it, type it. Send: see it, key it. | 3 correct copies in a row, then 3 correct sends in a row |
| 2 | Your state | Same, with the state | same |
| 3 | ? | Same, with `?` | same |
| 4 | 73 | Same, with `73` | same |
| 5 | Busted call | Activator sends a partial of your call (`N9?`, `N9H?` or a bare `?`); you send your full call and wait for the acknowledgement | 3 clean rounds |
| 6 | No reply | Two scenes: nobody answers (call again), and the activator answers someone else (wait, then call when they send QRZ) | each scene once, clean |
| 7 | Walkthrough | The whole contact, start to finish | 2 clean runs |

"In a row" means a miss resets that phase's count to zero. "Clean" means the
run had no mistakes; a mistake does not end a scene — the beat explains what
went wrong and waits for another try — but that run does not count toward the
pass. All four numbers are named constants (`FirstFour.copyStreakToPass`,
`sendStreakToPass`, `bustedRoundsToPass`, `walkthroughRunsToPass` in
MorseKit on both ports) and are pinned in `fixtures/first-four.json`.

Why these numbers: three in a row is the smallest run that is not luck for a
single item, and a scene is scripted, so two clean walkthroughs (with a
different activator each time) show the learner knows what comes next
rather than having clicked through once. They are deliberately conservative
in the sense of "short": the issue asks for "enough to make one successful
POTA contact", and a tutorial that takes an evening loses the beginner it is
for.

**Speed.** Everything plays at the learner's own speed settings (character
speed, Farnsworth, tone), the same timing every other copy mode uses. The
stage screen says so and points at Settings. The mode does not force a speed:
POTA activators commonly send in the high teens to low twenties, but a brand
new operator starts wherever they are, and the settings are one tap away.

**Copy grading.** Typed text is upper-cased and compared with every space
removed, so `n9ho`, `N9HO ` and `N 9 H O` are all a correct copy of `N9HO`.

**Send grading.** The keyed text comes from the same decoder every keying
mode uses (`SendingKeyer`): on-screen key (straight or paddles, per
Settings), or a hardware Vail / BLE-MIDI key. It is compared the same way,
spaces removed, because a beginner's word spacing is exactly what the
decoder is least sure of. Sending your call **twice** (`N9HO N9HO`) is also
accepted wherever your call is expected; it is common practice on the air.
The send auto-submits once the decoded text is at least as long as the
expected text and the key has gone idle, as Sending Practice does; Submit
and Clear are there too.

## The scenes

Each scene is a list of beats, generated in MorseKit from the learner's call
and state and an activator picked for the run. A beat is one of:

- **hear** — the activator transmits; the text plays, and appears on screen
  after it has played. Continue moves on.
- **silence** — nothing comes back. Shown as "…" with an explanation.
- **copy** — the activator transmits and the learner is asked for one piece
  of it (the activator's state), typed.
- **send** — the learner keys the text shown on the cue card.
- **wait** — the right thing to do is nothing. The learner taps "Wait";
  keying instead is the mistake the beat is there to teach.

Every beat also carries a **cue** — a stable name for the explanation the
screen shows beside it (`cq`, `callThem`, `partial`, `resend`, `ack`,
`noReply`, `callAgain`, `otherStation`, `stayQuiet`, `qrz`, `theirExchange`,
`yourExchange`, `signOff`). The words are each platform's own strings; the
cue is what the fixture pins, so the two apps explain the same beat at the
same moment.

With call `N9HO`, state `WI`, activator `K4RTZ` in `NC`, and another hunter
`W8KDP`:

**Busted call** (partial `N9?`):

    hear   CQ POTA DE K4RTZ K          cq
    send   N9HO                        callThem
    hear   N9?                         partial
    send   N9HO                        resend
    hear   N9HO 5NN NC NC BK           ack

The partials for a call are a bare `?` and then every proper prefix of two
or more characters followed by `?`: `?`, `N9?`, `N9H?` for `N9HO`. Round *n*
uses partial *n* modulo the list, so the three rounds for `N9HO` see all
three shapes. The bare `?` is where the third of the four earns its place:
it is what an activator sends when they heard someone and caught nothing.

**No reply**, scene A (nobody answers):

    hear    CQ POTA DE K4RTZ K         cq
    send    N9HO                       callThem
    silence                            noReply
    send    N9HO                       callAgain
    hear    N9HO 5NN NC NC BK          ack

**No reply**, scene B (the activator answers someone else):

    hear    CQ POTA DE K4RTZ K         cq
    send    N9HO                       callThem
    hear    W8KDP 5NN NC NC BK         otherStation
    wait                               stayQuiet
    hear    TU 73 QRZ                  qrz
    send    N9HO                       callAgain
    hear    N9HO 5NN NC NC BK          ack

**Walkthrough**:

    hear    CQ POTA DE K4RTZ K4RTZ K   cq
    send    N9HO                       callThem
    copy    N9HO 5NN NC NC BK → NC     theirExchange
    send    5NN WI 73                  yourExchange
    hear    TU 73 E E                  signOff

The hunter's reply is `5NN <state> 73` — the report every POTA contact
carries (always 5NN, "599"; the screen says so), the state, and 73. 5NN is the
only thing the walkthrough asks for that is not one of the four; it is shown
on the cue card and explained, not drilled. The activator's `E E` is the
friendly "dit dit" sign-off, sent as two separate E's.

Activators are picked from a fixed table of eight (callsign, state) pairs in
MorseKit, never equal to the learner's own call; the other hunter from a
fixed table of two. The tables are in the fixture so both ports carry the
same ones. Which row a run gets is each port's own random choice — only the
table and the script for a given row are pinned.

## The finale

Passing the walkthrough (and whenever the walkthrough is revisited after
that) shows a finale card instead of just a tick:

> You're ready for your first POTA contact. Find an activator on a spotting
> page, listen until you have their call, and call them.
>
> A first CW contact is a big deal — for you, and often for the activator
> too. Afterwards, consider following up: send a QSL card, drop them an
> email, or just a thank-you note. Many activators love hearing that they
> were someone's first.

## Where it lives

**Home.** A card directly under "New to Morse? Start here", above the mode
grid: "First Four — Your first POTA contact · call, state, ?, 73", with a
progress line once started ("3 of 7 stages") and a tick once finished. That
is the "early option" the issue asks for — it sits with the other newcomer
entry, where a beginner looks first, and it is always there, like Start
here. It is not a grid tile: it is a guided path, not a practice mode you
return to daily.

**Onboarding.** The first-run screen gains a second, quieter button under
"Start practicing": "Or learn just enough for your first POTA contact",
which completes onboarding with the chosen proficiency exactly as the main
button does and then opens First Four. A newcomer who wants to be on the air
this weekend is exactly who the issue is about, and the first run is the
only screen they are guaranteed to see.

**Screens.** iOS: `FirstFourView`, presented as a sheet from Home, like Daily
Dit. Android and desktop: `FirstFourScreen`, a route like `Route.DailyDit`
(desktop since #280, keyboard-first: Enter submits a typed answer and takes
Continue / Wait / Next; Space and `[` `]` key). None goes
through the session setup sheet or `TrainingMode`: there is no session
length, answer style or run to set up, and it has no place in the
mid-session mode switcher.

## Callsign and state

The callsign is the one the app already has: **Your Station › Your
callsign** (`QSOSettings.myCall` on iOS, `PileupSettings.myCall` on
Android), which the Pileup Runner, Contest, CW 77 and the Vail repeater
already use. First Four reads it and writes it back; there is no second
copy.

The state is new: **Your Station › Your state** (`QSOSettings.myState` /
`PileupSettings.myState`), next to the callsign and name, and in the Settings
search catalog on both ports (`fixtures/settings-catalog.json` gains
`myState`). Stored there so it can be reused — the Pileup Runner and Contest
could send it as your own exchange, and CW 77's "include my callsign and
name" could drill it — but in this change **only First Four reads it**.
Wiring it into those modes is a behaviour change to each of them and is left
as an open question.

First Four's first screen asks for both, pre-filled from those settings. The
default callsign `W1AW` counts as "not entered" there (it is the settings
placeholder, not the learner's call), so the field starts empty for a new
user. Validation, pinned in the fixture:

- **Callsign**: upper-cased, spaces removed; 3–10 characters of `A–Z`,
  `0–9` and `/`; at least one letter and one digit.
- **State**: upper-cased, spaces removed; 2–3 letters. Not checked against a
  list, so a Canadian province (`ON`) or a DX hunter's own shorthand works.

Progress (which stages have passed, and the scene counters) is stored under
its own key (`MorseTrainer.firstFour` in `UserDefaults`; the `amt_first_four`
preferences on Android). Changing the callsign or state does not reset it.
"Start over" on the stage list does.

## Where the code goes

Pure logic in MorseKit on each port, as two independent files, per the
two-ports rule:

- `ios/Sources/MorseKit/FirstFour.swift`
- `android/app/src/main/java/app/anothermorsetrainer/morsekit/FirstFour.kt`
- `desktop/app/src/main/kotlin/app/anothermorsetrainer/morsekit/FirstFour.kt`
  (#280; a third independent tree, per CLAUDE.md)

Each holds: the stage list and pass constants; call and state
normalisation and validation; copy and send matching; the partials; the
activator tables; the three scene builders; a `FirstFourScene` runner that
takes a response (sent text, typed text, wait, continue) and says whether it
was right and why not; and `FirstFourProgress`, the Codable / JSON-able
progress record with the pass rules.

`fixtures/first-four.json` pins all of it — the stage order and constants,
validation cases, matching cases, partials, the tables, the exact beats of
every scene for two profiles, and a scripted progress run — derived from the
rules in this note, not by running either port. Read by `MorseKitCheck`
(Swift) and `FirstFourTest` (Kotlin JUnit).

UI: `ios/MorseTrainerApp/FirstFourView.swift` and
`android/.../FirstFourScreen.kt`, each in its own idiom, with a small key
panel on the `SendingKeyer` both ports already have.

## Parity

Both apps in one pull request: the same stages, constants, scripts,
entry points (home card and onboarding button), settings field, grading and
finale. Nothing is platform-limited. The desktop app followed in #280 with
the same behaviour; it has no buddy streak, so a graded answer marks only
the local streak there. Every README gains the same feature
line, and the user guide gains a First Four section in the same release
(site PR linked from the app PR).

## Open questions for the maintainer

1. **Pass numbers.** 3 in a row per phase, 3 clean busted rounds, 2 clean
   walkthroughs. Too many for a first evening, or too few to mean anything?
2. **Hunter's reply.** `5NN WI 73` is minimal. Many hunters send the state
   twice (`5NN WI WI 73`) or lead with `TU`. Worth teaching one of those
   instead?
3. **Speed.** The learner's own settings. Should First Four instead default
   to a POTA-typical 18–20 WPM character speed with Farnsworth spacing, with
   its own speed control?
4. **"Not you" partials.** A busted round could also send a partial that is
   *not* the learner's call (`K4?`), where the right answer is to stay quiet.
   Left out to keep the stage to what the issue asked; easy to add as a
   `wait` beat.
5. **Reusing the state.** Should the Pileup Runner / Contest send
   `myState` as your exchange, and CW 77 drill it with your call and name?
6. **Copy of your call among look-alikes.** Copying your own call always
   plays your own call. A harder copy phase would mix in near-miss calls
   (`N9HQ`) and ask "was that you?". Left for a follow-up.
7. **Onboarding button wording.** "Or learn just enough for your first POTA
   contact" — fine, or should first run ask "What brings you here?" and route
   on the answer?
