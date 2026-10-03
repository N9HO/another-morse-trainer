# CW Operating Procedure: design notes

Status: **built on all three apps, with the maintainer's decisions applied**
(see "Decisions" at the end). Written 2026-10-03
for issues #294 (zero beating, RIT/XIT/offset, with a pileup demo) and #295
(a CW Operating Procedure section for POTA hunting: lessons, scenarios, a hunt
simulator). Both were reported via Discord by Justin R.; #295's content is
based on **WB0RLJ's "Advice for CW POTA Hunters"**, and the issue asks that
WB0RLJ be credited in the app.

The pull request that carries this note builds sections 1–4 below (the lessons,
the zero-beat lesson from #294, and the "What should you do?" scenario mode) on
all three apps. The hunt simulator (section 5) is designed here and tracked as
its own follow-up issue; see "What ships when".

Every number below is a named constant in MorseKit on each port, pinned by
`fixtures/operating-procedure.json`, and can be changed in one place per port.

## Sources

Operating practice is taken from these, in this order of precedence. Where
they disagree the note says so and says which one the app follows.

1. **WB0RLJ (Jim Vaughan), "Advice for CW POTA Hunters"**, on his QRZ.com
   page, <https://www.qrz.com/db/WB0RLJ#Advice> (the page needs a QRZ login).
   He posts recordings of his daily activations on YouTube,
   <https://www.youtube.com/@WB0RLJ>. The issue
   (#295) lists its points: the prosigns and signals a hunter hears; call right
   after the activator's dit-dit, without waiting for a CQ; send your call once
   with no `DE`, no `K` and no repeats; answer a partial only if those
   characters are in your call, and always with your full call; recognise when
   the activator is working someone else; the exchange (RST, state, `BK`, `73`,
   dit-dit, then stop); a mistake is a string of dits and then a resend; and
   offsetting. This note uses those points as the issue gives them. The
   original was not readable without a login when this note was written, so
   anything more specific than the issue's summary comes from the sources
   below, not from WB0RLJ.
2. **Parks on the Air, "CW Guide"** (AB1WX, original author),
   <https://docs.pota.app/docs/cw_guide.html>. It says to send your call once,
   to repeat it once when the activator sends a partial such as `KE?`, that the
   hunter's exchange is a report and state (`5NN MI`), and, in a pileup, to set
   your transmit frequency "somewhere between 20 and 100 Hz off the activator's
   frequency", because otherwise "the activator hears one long tone".
3. **Parks on the Air, "Hunter Guide"** (K8ZRY, original author),
   <https://docs.pota.app/docs/hunter_guide-english.html>. It adopts the DX
   Code of Conduct: listen first; call, then listen; don't transmit when the
   activator calls another callsign or queries a call not like yours; always
   send your full call; tune up on a clear frequency at least 1 kHz away, at
   minimum power.
4. **ON4UN and ON4WW, "Ethics and Operating Procedures for the Radio
   Amateur"**, edition 3 (2010), as published by the ARRL,
   <https://www.arrl.org/files/file/DXCC/Eth-operating-EN-ARRL-CORR-JAN-2011.pdf>.
   §II.9.23, *Zero beat*: two stations on the same frequency are zero beat; the
   usual causes of not being zero beat are a forgotten RIT and a sidetone
   pitch that doesn't match the receive pitch ("If you listen at 600 Hz and the
   side tone pitch is set at 1.000 Hz, you will transmit 400 Hz away"). §III.1
   on pileups: send your full call once, never the DX station's call; keep
   quiet when the DX station comes back to a partial that does not resemble
   your call, or to another station.
5. **ITU-R M.1677-1**, *International Morse code* (2009), §3.2 (the error
   signal, written as eight dots). The app does not teach that one shape; see
   the mistakes lesson.
6. Manufacturers' manuals, for what the controls are called on real rigs
   (the offsetting lesson): Icom IC-7300 (RIT, ∂TX, CW PITCH, AUTOTUNE), Yaesu FTDX10 (CLAR
   RX / CLAR TX, CW PITCH, ZIN/SPOT), Kenwood TS-890S (RIT, XIT, CW pitch, CW
   T. auto tune), Elecraft KX3 (RIT, XIT, PITCH, SPOT and auto-spot),
   FlexRadio SmartSDR (RIT and XIT on the slice flag, the pitch line on the
   panadapter; no auto zero-beat button, per FlexRadio's community forum).

Two sources disagree about **zero beat**, and the disagreement is the lesson:
the ON4UN/ON4WW guide (written for one-on-one contacts and contest
search-and-pounce) says to be exactly zero beat; POTA's CW Guide says that in a
pileup, being exactly zero beat with everyone else is what makes calls
uncopyable, and to offset 20–100 Hz. The app teaches both, in that order: zero
beat is how you get *onto* the activator's frequency; a small offset is how
you stand out *on* it.

## What it is

A section of the app called **CW Operating Procedure**: on-air etiquette for
hunting POTA activators, one rule at a time. It has three layers, as #295
asks:

1. **Lessons** — eight short lessons, each a concept card, an audio demo of
   right and wrong, and three to five quick scenarios. The first is #294's
   zero-beat / RIT / XIT / offset lesson, with its pileup demo and a
   tune-to-zero-beat drill.
2. **"What should you do?"** — a scenario mode: a clip plays, you choose what
   to do, and the answer comes with a link to the lesson that teaches it.
3. **The hunt simulator** — a full hunter-side pileup with a simulated
   activator who decides from what *they* hear. Designed in section 5; not in
   the first pull request.

### What it is not

- **Not ranked.** It is teaching material with fixed answers, like First Four:
  no leaderboard mode, nothing submitted (`docs/high-scores-design.md`, "Which
  modes are eligible"). The hunt simulator, when it lands, is not ranked
  either: its outcome depends on a random activator, and its grading is about
  procedure, not speed (decided: maintainer, 2026-10-03, and on #313).
- **Not a session.** No `SessionRecord`, no Koch ladder, no character stats.
  As in First Four and Daily Dit, a graded answer marks the day as practised
  (`markPracticedToday` on iOS, `Stats.recordPracticeDay` on Android and
  desktop), so the streak and the buddy streak see it.
- **Not a gate.** Every lesson can be opened at any time. The list recommends
  an order; nothing is locked.
- **Not a keying drill.** The lessons are about *what* to send and *when*, so
  their scenarios are choices, not keyed answers. First Four already drills
  keying your call, state, `?` and `73`; the hunt simulator (section 5) is
  where keying comes back.

## How it relates to First Four

First Four (#265, `docs/first-four-design.md`) is the beginner's on-ramp: the
four things you must be able to hear and send — your call, your state, `?`,
`73` — and three scripted scenes. CW Operating Procedure is what comes next:
the *rules* of the pileup a First Four graduate is about to call into.

**First Four stays where it is and stays as it is.** It keeps its home card
and its onboarding button; it does not move inside the new section. Reasons:
it is the one thing a brand-new user should find without looking (that is
why it sits above the grid), it has its own stages and progress that a
returning user already has, and moving it would make the section's first
lesson a different *kind* of thing (keyed elements) from the other eight
(choices).

They link instead, in both directions:

- **Section → First Four.** While First Four is not complete, the top of the
  section's lesson list shows "New to CW on the air? Start with First Four"
  and opens it. Once it is complete, the row reads "First Four ✓" and still
  opens it, for review.
- **First Four → section.** First Four's finale card gains one line: "Ready
  for more? Operating Procedure, the card just below First Four on the home
  screen, is the next step: zero beat, pileups and partial calls." On Home the
  section's card sits directly under First Four's.

**No duplicated content.** Where the two overlap, the section *refers back*
rather than re-teaching:

| Topic | First Four | CW Operating Procedure |
|---|---|---|
| `?`, `73` | Hear and key them | Signals (lesson 2) lists them with the other signals and moves on |
| Busted call (`N9?`) | Keyed scene: resend your call | Partial calls (lesson 5) adds the rule First Four leaves out: stay silent when the partial is *not* yours (`N1?`), First Four's open question 4 |
| No reply / someone else answered | Keyed scenes | Is it me? (lesson 6) adds the close-but-wrong call (`N9BO`, and `N9BO?`) |
| The exchange | `5NN WI 73`, keyed | The exchange (lesson 7) keeps that reply as the primary form, accepts WB0RLJ's `5NN WI BK` … `73 E E` too, and adds the activator's `E E`, yours back, and stopping |

The exchange lesson keeps First Four's reply (`5NN <state> 73`) as the primary
right answer so the two never contradict each other, and accepts WB0RLJ's
order as a second form (decision 4).

One small tension is called out rather than hidden: First Four's *grading*
accepts your call sent twice (`N9HO N9HO`), because the decoder is lenient and
it is common on the air; the send-your-call-once lesson *teaches* once, as WB0RLJ, the POTA CW Guide
and ON4UN/ON4WW all do. First Four's grading is not changed.

## The lessons

Eight lessons: #295's order, except that offsetting (#294) comes first, straight
after First Four (decision 2). Each has:

- a **concept card**: a few short paragraphs, each platform's own words;
- an **audio demo**: one or more labelled clips, each a "right" or "wrong"
  example (or, for the signals lesson, just "this is what it sounds like"); Play plays a
  clip at the learner's speed and tone;
- **three to five scenarios**, run in order. A scenario plays a clip (what the
  activator just sent; some have no clip, only a situation), then offers two
  or three **choices**. A choice is one of:
  - **send** *text* — "Send `N9HO`";
  - **silent** — "Stay silent and listen";
  - **option** *key* — a named answer, for questions that are not "send or
    not" (what a signal means, which control to use).

  One choice is right (the primary answer); a scenario can accept more than
  one (`accepted`), as the exchange's do. The screen then shows the explanation for that
  scenario (each platform's own words, keyed by the scenario's id, which the
  fixture pins) and moves on with Next.

**Passing a lesson** is a **clean run**: every scenario answered right first
time, in one sitting. A wrong answer shows the explanation and the run carries
on to the end, but it is not clean; "Go again" restarts it. The offsetting lesson also has a
drill (below), and passes when both the drill and a clean run are done, in
either order.

Why a clean run and not "N of M": the scenarios are few, fixed and explained
as you go, so one clean pass is the smallest evidence that the rule has stuck
rather than been guessed; and a beginner who gets one wrong learns why on the
spot and only has to run three to five questions again.

The learner's **callsign** comes from Your Station (`QSOSettings.myCall` on
iOS, `PileupSettings.myCall` on Android and desktop), as First Four's does; the
**state** from `myState`. If either is missing (or the call is the `W1AW`
placeholder) the section asks for them first, with the same validation First
Four uses (pinned again in this section's fixture so the two ports of the
section do not depend on First Four's code), and writes them back.

The **activator** in every scenario is `K4RTZ` in `NC` and the **other
hunter** `W8KDP` — First Four's first table rows — unless the learner's own
call is one of those, when the next row of First Four's tables is used. The
scenarios are generated in MorseKit from (call, state), and the fixture pins
every scenario for two profiles.

With call `N9HO`, state `WI` (the fixture's first profile), the lessons are:

### 1. Offsetting: zero beat, RIT, XIT (#294)

The one lesson with more than a concept card and scenarios.

**Concept cards** (four short cards in one scroll):

1. *Pitch.* On CW the tone you hear is how far the signal is from where your
   receiver is tuned, plus your rig's CW pitch setting. Your sidetone is set
   to that same pitch. So a station that sounds **exactly like your sidetone**
   is on the frequency you'd transmit on.
2. *Zero beat.* Being on exactly the same frequency is "zero beat" — play two
   tones a few hertz apart and you hear a slow beat; at the same frequency the
   beat stops. Zero beat is how you get onto a station's frequency
   (ON4UN/ON4WW §II.9.23).
3. *Offset.* In a pileup, everyone zero beat sounds like one long tone and
   nobody gets copied. Calling a little off — 20 to 100 Hz (POTA CW Guide) —
   puts you on your own pitch in the activator's ears, and you are still well
   inside their filter.
4. *RIT, XIT and pitch.* **RIT** moves what you *hear* and leaves where you
   transmit alone; **XIT** moves where you *transmit* and leaves what you hear
   alone; **pitch** sets the tone (and sidetone) you hear CW at. Forgetting an
   RIT you left on is the classic way to call off frequency without meaning
   to (ON4UN/ON4WW §II.9.23).

Then a **"On your rig"** table, from the manuals listed under Sources:

| Maker | Hear-shift (RIT) | Transmit-shift (XIT) | Pitch | Zero-beat aid |
|---|---|---|---|---|
| Icom | RIT | ∂TX ("delta TX") | CW PITCH | AUTOTUNE (CW auto tuning) |
| Yaesu | CLAR (RX) | CLAR (TX) | CW PITCH | ZIN/SPOT |
| Kenwood | RIT | XIT | CW pitch | CW T. (CW auto tune) |
| Elecraft | RIT | XIT | PITCH | SPOT, auto-spot |
| FlexRadio | RIT | XIT | Pitch (and the pitch line on the panadapter) | None built in: click-tune to the line |

"Model and firmware vary — check your manual" under it.

**Demo 1, the pileup (#294).** Several stations answer the activator's CQ;
one of them is you. The same pileup plays three times, and the screen says
which pass is which:

| Pass | You | The others |
|---|---|---|
| `zeroBeat` | 0 Hz | all 0 Hz |
| `youOffset` | +80 Hz | all 0 Hz |
| `allOffset` | +80 Hz | −160, −70, +30, +150 Hz |

What is heard is the *activator's* receiver: each caller at the learner's own
tone setting plus their offset. The callers, in the order they are mixed:

| Caller | Speed (vs. the learner's WPM) | Level | Starts after |
|---|---|---|---|
| `W8KDP` | +2 | 0.80 | 0.15 s |
| `KE0RJ` | −2 | 0.90 | 0.30 s |
| you | 0 | 1.00 | 0.00 s |
| `AB7TF` | +4 | 0.70 | 0.10 s |
| `N4LQX` | −1 | 0.85 | 0.22 s |

(any caller whose call equals the learner's is replaced by `KC2VWM`; speeds
never go below 5 WPM; a pitch never below 200 Hz — the floor the Pileup
Runner's mixer already applies.) Each caller sends their call once, which is
the send-your-call-once lesson's point too.

80 Hz is inside the POTA CW Guide's 20–100 Hz and far enough apart to hear
plainly at any tone the app offers; the others' offsets are spread wider than a
real pileup on purpose, so pass 3 makes its point on a phone speaker.

**Demo 2, RIT.** One station, the activator, sending `CQ POTA` 200 Hz above
you. An **RIT** control (−300 to +300 Hz in 10 Hz steps) changes the pitch you
hear it at, live: heard pitch = tone − RIT + 200. A second readout shows *where
you'd transmit*, which RIT does not move: "You'd call 200 Hz from them". The
point the demo makes is ON4UN/ON4WW's: RIT can make them sound right while you
are still off their frequency.

**The drill: zero-beat it.** The activator is somewhere off your frequency; a
**tuning** control (−50, −10, +10, +50 Hz buttons, and a readout) moves your
VFO, which moves both what you hear and where you transmit. Three buttons:
**Station** plays them at the pitch your tuning gives; **Spot** plays your
sidetone; **Together** plays both at once, so you can hear the beat slow down
and stop. **Done** grades: right when your transmit offset from them is within
**±20 Hz** (`zeroBeatToleranceHz`). **Three in a row** pass the drill
(`zeroBeatStreakToPass`), as First Four counts.

The starting offsets cycle through a pinned table of eight, all multiples of
10 Hz (so exact zero is reachable with the 10 Hz button) and all at least 60 Hz
away: +180, −120, +250, −70, +90, −210, +140, −260.

The maths, pinned in the fixture (all in hertz; "offset" is a frequency
relative to the activator's; higher frequency sounds higher, as on the upper
sideband — CW-reverse would flip the signs and is not modelled):

    heardPitch(tone, station, vfo, rit) = tone + station − (vfo + rit)
    transmitOffset(station, vfo, xit)   = vfo + xit − station
    isZeroBeat(transmitOffset)          = |transmitOffset| ≤ 20
    audible(pitch)                      = max(200, pitch)

In the drill RIT and XIT are 0; in the RIT demo VFO and XIT are 0.

**Scenarios** (option choices):

| id | Situation | Choices (right first) |
|---|---|---|
| `offset.pileup` | A big pileup, everyone on the activator's frequency | call 50 Hz off · call exactly zero beat · call 2 kHz up |
| `offset.rit` | You want to hear them at your pitch without moving where you transmit | RIT · XIT · pitch |
| `offset.xit` | You want to transmit 60 Hz off without changing how they sound | XIT · RIT · pitch |
| `offset.tune` | You need to tune your antenna tuner | 1 kHz or more away, low power · on their frequency, quickly · on their frequency, low power |

The offsetting lesson passes when the drill has passed **and** a clean scenario run is done.

### 2. Signals

What a hunter hears from an activator, and what each means:

| Signal | Means |
|---|---|
| `?` | Say again — I didn't get it all |
| `AGN?` | Send that again |
| `<AS>` (di-dah-di-di-dit, run together) | Wait — stand by |
| `BK` | Back to you |
| `SRI` | Sorry |
| `QRZ?` | Who is calling me? |
| `E E` (dit-dit) | The friendly sign-off: this contact is done |

Demo: each signal, played in turn. Scenarios (option choices; the clip plays,
"What does this mean?"):

| id | Clip | Choices (right first) |
|---|---|---|
| `signals.as` | `<AS>` | wait · go ahead · goodbye |
| `signals.qrz` | `QRZ?` | who is calling · say again · sorry |
| `signals.ee` | `E E` | goodbye · error · who is calling |
| `signals.bk` | `BK` | back to you · wait · sorry |
| `signals.agn` | `AGN?` | say again · goodbye · back to you |

(On screen the choices are shuffled per run; the fixture pins them in this
right-first order, with `correct: 0`.)

### 3. When to call

The activator ends each contact with `73` or `TU` and a dit-dit (`E E`). That
is the moment to call — straight away. Don't wait for a CQ: in a pileup many
activators never send one between contacts. While a contact is going on, stay
quiet.

Demo: wrong — calling over `W8KDP 5NN NC NC BK`; right — calling after
`W8KDP TU 73 E E`.

| id | Clip | Choices (right first) |
|---|---|---|
| `when.dits` | `W8KDP TU 73 E E` | send `N9HO` · silent (wait for a CQ) |
| `when.inProgress` | `W8KDP 5NN NC NC BK` | silent · send `N9HO` |
| `when.as` | `<AS>` | silent · send `N9HO` |
| `when.sriQrz` | `SRI SRI QRZ?` | send `N9HO` · silent |

### 4. Send your call once

The activator knows their own call, and you are on their frequency, so they
know who you're calling. Send just your call, once: no `DE`, no `K`, no
activator's call, no repeats. Then listen.

Demo: wrong — `K4RTZ DE N9HO N9HO K`; right — `N9HO`.

| id | Clip | Choices (right first) |
|---|---|---|
| `once.cq` | `CQ POTA DE K4RTZ K` | send `N9HO` · send `K4RTZ DE N9HO K` · send `N9HO N9HO N9HO` |
| `once.qrz` | `QRZ?` | send `N9HO` · send `DE N9HO K` · send `N9HO N9HO` |
| `once.dits` | `W8KDP TU 73 E E` | send `N9HO` · send `K4RTZ N9HO` · silent |

### 5. Partial calls

When the activator sends part of a call and `?`, answer **only if those
characters are in your call**, and answer with your **full** call, once. A
bare `?` means they heard someone and caught nothing: anyone who called can
send again.

The match rule, pinned: strip the trailing `?`; if what is left is empty (a
bare `?`), it is everyone's; otherwise it is yours when it appears, as one
unbroken run of characters, anywhere in your call (prefix `N9`, middle `9H`,
suffix `HO`).

The "not yours" partial for a call is generated, so it is never accidentally
yours: take the call up to and including its first digit, and replace that
digit with (digit + 2) mod 10; if the result happens to be in the call, keep
adding 1 (mod 10) until it is not. For `N9HO` that is `N1?` — #295's own
example. A call with no digit (not valid here) is not given one.

| id | Clip | Choices (right first) |
|---|---|---|
| `partial.prefix` | `N9?` | send `N9HO` · silent |
| `partial.notMine` | `N1?` | silent · send `N9HO` |
| `partial.suffix` | `HO?` (last two characters) | send `N9HO` · silent |
| `partial.fullCall` | `N9H?` (all but the last character) | send `N9HO` · send `O` (just the missing part) · silent |

### 6. Is it me?

Listen to *who* the activator comes back to. Your call: it's you. Another
call: that contact is in progress, so stay quiet until it ends with dit-dit.

A call one character away from yours (`N9BO` for `N9HO`) depends on whether it
ends in a question mark (maintainer, 2026-10-03):

- **Without a question mark** (`N9BO 5NN NC NC BK`): they are working `N9BO`.
  Stay silent.
- **With a question mark** (`N9BO?`): they aren't sure what they heard. Send
  your call once, then listen for whether they come back with yours.

That answers #295's open design question ("decide and document the rule for
when the activator repeatedly sends a wrong call that is close to yours"). It
fits the DX Code of Conduct as the POTA Hunter Guide adopts it (don't transmit
when the activator works another callsign) and ON4UN/ON4WW §III.1 (make sure
you are logged correctly when the activator is asking).

The near-miss call is generated, so the fixture can pin it for any call: take
the first letter after the call's first digit and move it **back six letters**,
wrapping `A` round to `U` (`N9HO` → `N9BO`, #295's own example; `KB3MZL` →
`KB3GZL`). Back, so the result is never the input; one letter, so it is
genuinely close. A call with no letter after its first digit moves its last
letter instead.

Demo: wrong — answering `N9BO 5NN NC NC BK` with `N9HO`; right — `N9BO?`, you
send `N9HO`, and they come back `N9HO 5NN NC NC BK`.

| id | Clip | Choices (accepted first) |
|---|---|---|
| `me.other` | `W8KDP 5NN NC NC BK` | silent · send `N9HO` |
| `me.mine` | `N9HO 5NN NC NC BK` | send `5NN WI 73` ✓ · send `5NN WI BK` ✓ · send `N9HO` · silent |
| `me.close` | `N9BO 5NN NC NC BK` | silent · send `N9HO` |
| `me.closeAsked` | `N9BO?` | send `N9HO` · silent |

### 7. The exchange

When they come back with your call, report and state, reply. Two forms are
right (maintainer, 2026-10-03):

- **Primary: `5NN WI 73`**, the reply First Four teaches. They finish with
  `TU 73 E E`; send `E E` back.
- **WB0RLJ's order: RST, state, BK, 73, dit-dit.** The app reads that as two
  turns: `5NN WI BK`, then, after their `TU 73 E E`, `73 E E`.

Then **stop**: the next `QRZ?` is for someone else. Scenarios accept either
form wherever a reply or a close is asked for (`accepted` in the fixture).

Demo: wrong — a ragchew-length reply,
`K4RTZ DE N9HO TNX FER CALL UR 5NN 5NN NAME JOE QTH WI WI HW? K4RTZ DE N9HO KN`;
right — `5NN WI 73`, then `E E`; right — `5NN WI BK`, then `73 E E`.

| id | Clip | Choices (accepted first) |
|---|---|---|
| `exchange.reply` | `N9HO 5NN NC NC BK` | send `5NN WI 73` ✓ · send `5NN WI BK` ✓ · send the ragchew · send `N9HO 5NN WI` |
| `exchange.agn` | `AGN?` (after your reply) | send `5NN WI 73` ✓ · send `5NN WI BK` ✓ · send `N9HO` · silent |
| `exchange.dits` | `TU 73 E E` | send `E E` ✓ · send `73 E E` ✓ · send `N9HO` · send `TU 73 GL DE N9HO SK` |
| `exchange.stop` | `QRZ?` (after your `E E`) | silent · send `N9HO` |

### 8. Mistakes

If you send a wrong character, send an error, then send the word again,
correctly, from the start: your whole call, not just the bad letter. Don't go
quiet and hope.

**An error is not one fixed signal, and not a prosign** (maintainer,
2026-10-03). ITU-R M.1677-1 §3.2 writes it as eight dots, but on the air it can
be anything: a quick run of dits, five to eight of them, or someone slapping
the key, fast or slow, run together or ragged. So the lesson teaches
*recognising* an error and ignoring what came just before it, not copying one
shape.

In the app's text an error is the token `<ERR>`. It is never shown as dots:
the screen says `[error]`. When it plays, each error takes one of six shapes
(`errorVariants` in the fixture), picked at random unless the clip pins one
with `<ERR:n>`:

| Row | Dits | Sent | Speed (× your WPM) |
|---|---|---|---|
| 0 | 8 | run together | 1.0 |
| 1 | 5 | run together | 1.5 |
| 2 | 6 | slapped (character gaps) | 0.8 |
| 3 | 7 | run together | 1.25 |
| 4 | 5 | slapped | 1.0 |
| 5 | 8 | slapped | 1.5 |

"Run together" plays the dits as one keying (element gaps); "slapped" plays
separate dits (character gaps). A clip is split into pieces at each error
(`clipParts`) and the pieces play a word gap apart.

Demo: wrong — `N9HP` and silence; right — `N9HP <ERR> N9HO`; then three
"listen" examples of the activator doing it, each in a different shape
(`<ERR:1>`, `<ERR:2>`, `<ERR:5>`).

| id | Situation / clip | Choices (accepted first) |
|---|---|---|
| `mistake.call` | You keyed `N9HP` instead of your call | send `<ERR> N9HO` · send `SRI N9HO` · silent |
| `mistake.last` | You keyed `N9HI`; only the last letter is wrong | send `<ERR> N9HO` · send `<ERR> O` · silent |
| `mistake.state` | In your reply you keyed `5NN WJ` | send `<ERR> WI 73` · send `5NN WJ WI 73` · silent |
| `mistake.hear` | Clip: `N9HP <ERR> N9HO 5NN NC NC BK` | send `5NN WI 73` ✓ · send `5NN WI BK` ✓ · send `N9HO` · silent |

`mistake.hear` is the recognition one: the activator fumbles your call, sends
an error, and gets it right. Recognising the error means the right thing is
your exchange, not another call.

The mistaken text in each situation is generated, and the fixture pins it for
both profiles. "Moved on one" and "moved back six" step a letter through
`A`–`Z` and a digit through `0`–`9`, wrapping:

- `mistake.call` and `mistake.hear`: the call with its last character moved on
  one (`N9HO` → `N9HP`);
- `mistake.last`: the call with its last character moved back six (`N9HO` →
  `N9HI`);
- `mistake.state`: the state with its last letter moved on one (`WI` → `WJ`),
  so the situation reads "In your reply you keyed `5NN WJ`".

## "What should you do?"

A scenario mode over the lessons. The pool is every scenario whose choices
are *send* or *silent* — the "action" scenarios (lessons 3–8; the offsetting
and signals lessons are about controls and meanings, so they stay in their
lessons). It contains #295's five examples: `N1?` (stay silent), `N9?` (send
`N9HO`), `<AS>` (wait), `SRI SRI QRZ?` (call now) and `N9BO` (stay silent), and
its partner `N9BO?` (send your call once).

A run deals the pool shuffled, ten at a time (`scenarioRunLength`). Each
answer shows right or wrong, the explanation, and **"Lesson: Partial calls"**
— a link that opens that lesson. At the end: "8 of 10", and Go again. Nothing
is stored but the day's practice; there is no best score (it is not a game,
and not ranked).

## The hunt simulator (follow-up, #313)

Designed here so the first pull request's MorseKit pieces are shaped for it;
built in its own issue.

**What it is.** You are a hunter. A simulated activator runs a pileup on a
simulated frequency, and you call, answer and sign off by keying (on-screen
key, or a Vail / MIDI key, through the same `SendingKeyer` First Four uses).
It reuses, not forks:

- **The Pileup Runner's station generator** (`PileupEngine`'s callsign, speed,
  level, offset, QSB and caller-cap logic, `fixtures/pileup-*.json`) for the
  other hunters, through a new entry point that returns stations without an
  engine in the activator's chair.
- **The pileup mixer** (`MorsePlayer.playPileup` on every port, which already
  takes per-voice frequency, timing, gain, delay and QSB, and materialises its
  buffer on purpose — CLAUDE.md's standing trap).
- **The lessons' rules** (partial matching, near-miss calls, the exchange) as
  the grader's definitions of right.

**Two mixes.** Every hunter has a level *at the activator* and a level *at
you*. The activator decides from its mix (all hunters); you hear yours (a
subset: some hunters inaudible to you, some weaker). That is what makes
one-sided contacts and "quiet" frequencies that are actually busy — the
situations the when-to-call and is-it-me lessons are about.

**The activator**, a state machine: `cq` → `listen` → `pick` (the strongest
decodable call, or a partial) → `query` (`N9?`, or `?`) → `exchange` → `73 E E`
→ `listen`. After failed tries it sends `SRI QRZ?`; occasionally `<AS>`.

**The copy model.** For each character a hunter sends, the activator decodes it
with a probability from: that hunter's level over the noise; how much it
overlaps another caller in time; and how close their pitches are (a caller
within 20 Hz of another overlapping caller is "zero beat" with it and both are
much harder to copy). Busted calls and partials then happen by themselves, and
zero-beaters blur together. The probabilities are pinned in the fixture.

**Simulated hunter types:** good operator; repeater (sends their call
twice or three times); tail-ender (calls before the current contact ends);
zero-beater; `?`-sender; wrong-call responder (answers partials that aren't
theirs); tune-up carrier; QRP (very weak at the activator).

**Grading**, per contact attempt: timing (calling over a contact makes the
activator send `AGN?` to the other station, and counts against you);
sending your full call once; partial handling (the partial-calls rule); offset (your
XIT, from a control on screen, within 20–100 Hz in a pileup); the exchange
(either accepted form).

**Difficulty:** pileup size, WPM, QSB/QRN, the percentage of hunters you can't
hear, and the bust rate.

**Debrief:** a timeline of "what you heard" against "what the activator
heard", contact by contact, with each grading point linked to its lesson.

**Not ranked** (decided: maintainer, 2026-10-03; recorded on #313).

## Where it lives

**Home.** A card directly under First Four (decision 1), in First Four's card
style: **Operating Procedure**, "The next step after First Four · pileups,
partials, zero beat", then "3 of 8 lessons · the next step after First Four"
once started, and a tick when every lesson has passed. It follows First Four's
wide-layout placement exactly: on a big iPad window, an Android tablet or a
wide desktop window it stacks under Start here and First Four, beside Daily
Dit. On a phone it sits under First Four. It opens its own screen, never the
session setup sheet: it has no session length or answer style, and no place
in the mid-session mode switcher.

**Screens.** iOS: `OperatingProcedureView`, a sheet from Home, like First Four.
Android: `OperatingProcedureScreen`, a route like `Route.FirstFour`. Desktop:
the same, a route in `Main.kt`. Each has three levels: the section (station
line, the First Four row, the eight lessons with ticks, "What should you do?"),
a lesson (concept → demo → practice), and the scenario mode.

**Wide layouts.** On a big iPad window (`wideLayout`) and a wide Android or
desktop window (`isWideLayout()` in `Responsive.kt`): the lesson list is two
columns; a lesson puts the concept card and demo in one column and the
practice beside it, so the explanation stays in view while you answer.

**Progress** is stored under its own key (`MorseTrainer.operatingProcedure` in
`UserDefaults`; `amt_operating_procedure` preferences on Android; the desktop
`Prefs` node of the same name): which lessons have passed, which have a clean
scenario run, and whether the offsetting drill has. "Start over" on the section clears it.

**Credit** (decision 8). The section's foot says "Procedure after WB0RLJ's
*Advice for CW POTA Hunters*, with thanks, and the Parks on the Air CW Guide",
with two links under it: *Advice for CW POTA Hunters* (QRZ.com,
<https://www.qrz.com/db/WB0RLJ#Advice>) and *Jim Vaughan (WB0RLJ) on YouTube*
(<https://www.youtube.com/@WB0RLJ>, his daily activations). The guide credits
him the same way.

## Where the code goes

Pure logic in MorseKit on each port, three independent files:

- `ios/Sources/MorseKit/OperatingProcedure.swift`
- `android/app/src/main/java/app/anothermorsetrainer/morsekit/OperatingProcedure.kt`
- `desktop/app/src/main/kotlin/app/anothermorsetrainer/morsekit/OperatingProcedure.kt`

Each holds: the lesson list and constants; call/state normalisation and
validation (its own copy, so desktop does not depend on First Four's port
arriving); the partial-match rule and the generated partial, near-miss and
mistake texts; the scenario builders; the demo clips and the pileup demo's
voices; the offset maths; the drill's table; a scenario-run grader; and
`OperatingProcedureProgress`, the Codable / JSON-able progress record.

`fixtures/operating-procedure.json` pins all of it, derived from this note by
hand — not captured from a port. Read by `MorseKitCheck` (Swift),
`OperatingProcedureTest` (Android JUnit) and its desktop twin.

## Parity

All three apps in one pull request: the same lessons, scenarios, demos, drill,
scenario mode, constants, home card, wide layouts, progress and credit. Nothing
in sections 1–4 is platform-limited:

- the audio is the pileup mixer every port already has;
- choices need no keyer, so desktop's missing BLE MIDI (PARITY.md) does not
  matter here.

First Four reached desktop (#280, pull request #304) while this was being
written, so the First Four row and First Four's forward line are on all three
apps, and desktop's `PileupSettings.myState` (which the exchange lesson needs) came with
it.

All three READMEs gain the same feature line, and the user guide gains a CW
Operating Procedure section (site pull request linked from the app one).

## What ships when

- **This pull request:** this note; MorseKit + fixture + tests on all three
  ports; the section, all eight lessons (with #294's offsetting lesson
  first and complete: concept, pileup demo, RIT demo, drill, scenarios),
  "What should you do?", the home card under First Four, the First Four
  links; READMEs and the guide. Closes #294 and
  #295's layers 1 and 2.
- **Follow-up issue #313:** the hunt simulator (section 5), all three apps.

## Decisions (maintainer, 2026-10-03)

The eight open questions this note first ended with, as the maintainer
answered them. The note above already reflects each one.

1. **Placement: a card on Home, directly under First Four**, presented as its
   next step. It uses First Four's card style and the same wide-layout
   placement: stacked under Start here and First Four beside Daily Dit on a
   big iPad, Android tablet or desktop window, and one under another on a
   phone. It is not a grid tile.
2. **Lesson order: offsetting first.** The zero beat / RIT / XIT / offset
   lesson (#294) comes straight after First Four. The other seven keep #295's
   order and are renumbered 2–8. The fixture's `lessons` order and its
   progress script changed with it. The fixture is the spec, and the spec
   changed.
3. **Near-miss rule.** If the activator sends a near-miss of your call *with* a
   question mark (`N9BO?`), send your call once and listen for whether they
   correct to yours. *Without* one (`N9BO 5NN …`, working `N9BO`), stay silent.
   This is now lesson 6.
4. **The exchange.** `5NN WI 73` (First Four's) stays the primary form.
   WB0RLJ's order — RST, state, BK, 73, dit-dit — is taught as an accepted
   second form, and scenario grading accepts both. This is now lesson 7.
5. **Mistakes.** The error signal is not a prosign and not one fixed eight-`E`
   pattern. An error can be anything: a run of dits, or someone slapping the
   key 5–8 times. The examples vary in count (5–8), speed and spacing, and the
   lesson teaches recognising an error rather than copying a shape. This is
   now lesson 8.
6. **The hunt simulator (#313) is not ranked.** This is recorded on #313.
7. **Demo offsets are unchanged:** 80 Hz for you, and ±160 Hz for the widest
   caller.
8. **Credit.** "Procedure after WB0RLJ's *Advice for CW POTA Hunters*, with
   thanks", linked to <https://www.qrz.com/db/WB0RLJ#Advice>. Also a link to
   his YouTube channel, <https://www.youtube.com/@WB0RLJ> ("Jim Vaughan
   (WB0RLJ)"), where he posts recordings of his daily activations. Both links
   appear in all three apps, in this note and in the guide.
