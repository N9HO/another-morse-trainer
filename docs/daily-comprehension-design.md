# Daily Comprehension (#353)

A daily mode beside Daily Dit, Head Copy and Short Stories. A short sentence
(about five words, e.g. `ALFRED ATE TOAST AT BREAKFAST`) is played in Morse; the
learner then answers a question about it ("What was Alfred eating, and at which
meal?") instead of transcribing it. It trains understanding at speed, not
letter-perfect copy. Built on iOS, Android and desktop in the same change; the
three trees stay independent (`CLAUDE.md`).

## Decisions

- **Answer format: multiple choice, four options (A-D).** One answer works with
  touch, keyboard (A-D or 1-4 then Enter, desktop), voice-over and a hardware
  key (key A, B, C or D, then stop keying) with no free-text grading. A
  highlighted option is not final until locked in. One answer a day; the
  sentence can be replayed any number of times, before and after.
- **Same puzzle for everyone on a date.** Mirrors Daily Dit: the local civil
  date gives `puzzleNumber = max(1, daysFromCivil(date) - daysFromCivil(2026-01-01) + 1)`
  (the same epoch and the same integer arithmetic, so no tree depends on a
  platform calendar).
- **Item selection:** `itemIndex = ((puzzleNumber - 1) % itemCount) * 53 % itemCount`.
  The stride 53 is coprime with the bank size (132), so every item appears once
  per 132 days before any repeats. Pinned by the fixture, including a full cycle.
- **Option order is seeded by the date, not random.** `order = [0,1,2,3]` indexes
  `[answer, d1, d2, d3]`. `state = puzzleNumber`; for `i` in 3, 2, 1:
  `state = (state * 1664525 + 1013904223) mod 2^32`, `j = (state >> 16) % (i + 1)`,
  swap `order[i]` and `order[j]`. `optionOrder[k]` is the index shown at
  position `k`; the right answer is where `optionOrder[k] == 0`. Everyone sees
  the same order, so players can compare notes without spoiling anything.
- **Speed is the learner's own setting** (character speed, Farnsworth and tone,
  as every copy mode uses). There is no speed ladder: this mode is about
  meaning, not about the slowest speed you can survive.
- **Streak and leaderboard.** An answer, right or wrong, counts toward the Daily
  streak exactly like a Daily Dit guess (`markPracticedToday` /
  `Stats.recordPracticeDay`). It is **not ranked**: it never writes a session
  record, never touches the Koch ladder or Stats history, and is never submitted
  to the leaderboard, so it does not touch the anti-cheat design in
  `docs/high-scores-design.md`. A share card is out of scope.
- **Characters.** Every sentence is upper-case A-Z with single spaces; nothing
  that cannot be sent. The bank is authored in plain common words and each item
  records `kochLevel` (1 + the highest position in `MorseCode.kochOrder` of any
  letter used). **Rule:** the bank as a whole needs the full alphabet (it ranges
  from level 25 to 37), so it is not filtered per learner. A learner who has not
  unlocked every letter still plays the day's sentence, because the day's
  puzzle must be the same for everyone and filtering by unlock state would give
  different players different puzzles. The answer is a multiple-choice, so an
  unknown letter degrades to a guess rather than a blocker. `kochLevel` is kept
  as data so a later "only offer items within my set" option, or a
  fallback pool for beginners, needs no re-authoring. (Open question for the
  maintainer.)

## Data

- `fixtures/comprehension-bank.json`: the authored bank (132 items: `id`,
  `sentence`, `question`, `answer`, `distractors` (3), `kochLevel`). **Order is
  part of selection**: never reorder or insert before the end. Append new items
  only, and bump the fixtures deliberately.
- `fixtures/comprehension-selection.json`: expected dates -> puzzle -> item id ->
  option order, shuffle seeds, and one full stride cycle. Values were computed by
  a throwaway Python script written from the algorithm above, not by either
  implementation.
- Each tree holds its own copy of the bank, generated into that tree's own
  source by `ios/tools/gen_comprehension_bank.py`:
  `ios/Sources/MorseKit/ComprehensionBank.swift`,
  `android/.../morsekit/ComprehensionBank.kt`,
  `desktop/.../morsekit/ComprehensionBank.kt`. No tree reads another tree's
  directory or `fixtures/` at run time. A test in each tree asserts its copy
  equals the fixture (item by item), so editing one without the other fails CI.

## Per tree

| | iOS | Android | Desktop |
|---|---|---|---|
| Pure logic | `MorseKit/Comprehension.swift` | `morsekit/Comprehension.kt` | `morsekit/Comprehension.kt` |
| State | `AppModel+DailyComprehension.swift` (UserDefaults) | `DailyComprehensionStore.kt` (SharedPreferences) | `DailyComprehensionStore.kt` (Prefs) |
| Screen | `DailyComprehensionView.swift` | `DailyComprehensionScreen.kt` | `DailyComprehensionScreen.kt` |
| Home entry | card under Daily Dit (`IntroView`) | card under Daily Dit (`HomeScreen`) | same |
| Tests | `MorseKitCheck` | `DailyComprehensionTest`, `DailyComprehensionStoreCodecTest` | same |

## Guide

The user guide (source outside this repository) needs a Daily Comprehension
section next to Daily Dit: what it is, one answer a day, A-D options, hardware
key answering, "counts toward your streak, not ranked".
