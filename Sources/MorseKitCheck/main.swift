import Foundation
import MorseKit

// A tiny zero-dependency test harness so we can verify MorseKit with only the
// command-line Swift tools (XCTest needs the full Xcode app). Once Xcode is
// installed these same checks become proper XCTest cases in the app project.

var failures = 0
var checks = 0

func check(_ name: String, _ condition: Bool) {
    checks += 1
    if condition {
        print("  ✓ \(name)")
    } else {
        failures += 1
        print("  ✗ \(name)   <-- FAILED")
    }
}

func approxEqual(_ a: Double, _ b: Double, _ tol: Double = 1e-5) -> Bool {
    abs(a - b) <= tol
}

/// A tiny seedable RNG so randomized logic is reproducible.
struct SeededRNG: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
    mutating func next() -> UInt64 {
        state ^= state >> 12
        state ^= state << 25
        state ^= state >> 27
        return state &* 0x2545F4914F6CDD1D
    }
}

print("\nMorseKit self-check\n")

// Alphabet
print("Alphabet:")
check("37 base characters (26 letters + 10 digits + ?)", MorseCode.table.count == 37)
check("X is -..-", MorseCode.pattern(for: "X") == "-..-")
check("lowercase x works too", MorseCode.pattern(for: "x") == "-..-")
check("A is dit-dah", MorseCode.elements(for: "A") == [.dit, .dah])
check("? is in the base set (..--..)", MorseCode.pattern(for: "?") == "..--..")
check("optional punctuation patterns resolve",
      MorseCode.pattern(for: ",") == "--..--" &&
      MorseCode.pattern(for: "/") == "-..-." &&
      MorseCode.pattern(for: ".") == ".-.-.-")
check("comma is NOT in the base alphabet", !MorseCode.alphabet.contains(","))
check("unknown char returns nil", MorseCode.pattern(for: "!") == nil)

// Timing at 33 WPM
print("\nTiming @ 33 WPM:")
let t = MorseTiming(wpm: 33)
check("dit ≈ 36.36 ms", approxEqual(t.dit, 0.0363636))
check("dah = 3 dits", approxEqual(t.dah, 3 * t.dit, 1e-9))
check("'E' lasts exactly one dit", approxEqual(t.duration(of: "E"), t.dit, 1e-9))
check("'A' = dit + gap + dah", approxEqual(t.duration(of: "A"), t.dit + t.elementGap + t.dah, 1e-9))

// Farnsworth timing
print("\nFarnsworth timing:")
let std = MorseTiming(wpm: 20)
let farns = MorseTiming(characterWpm: 20, effectiveWpm: 10)
check("character elements unchanged by Farnsworth", farns.dit == std.dit && farns.dah == std.dah)
check("Farnsworth stretches the character gap", farns.characterGap > std.characterGap)
check("standard spacing unit equals dit when no Farnsworth", abs(std.spacingUnit - std.unit) < 1e-9)
check("effective==character ⇒ no stretch",
      abs(MorseTiming(characterWpm: 20, effectiveWpm: 20).characterGap - std.characterGap) < 1e-9)

// Distance / distractors
print("\nClosest-sounding distractors:")
let X: Character = "X", B: Character = "B", P: Character = "P", Y: Character = "Y"
check("X↔B (1 element off) closer than X↔P (inverse)",
      MorseDistance.distance(X, B) < MorseDistance.distance(X, P))
check("X↔Y (1 element off) closer than X↔P",
      MorseDistance.distance(X, Y) < MorseDistance.distance(X, P))
let nX = MorseDistance.nearestNeighbors(to: "X", in: MorseCode.alphabet, count: 3)
check("3 neighbors returned, target excluded", nX.count == 3 && !nX.contains("X"))
check("neighbors of X include a 1-element-off letter",
      nX.contains("B") || nX.contains("Y"))
print("    → closest to X: \(nX.map(String.init).joined(separator: ", "))")

// Mastery
print("\nMastery gate:")
var mastered = CharacterStats(character: "K")
for _ in 0..<5 { mastered.record(correct: true, ttr: 0.6) }
check("fast + accurate ⇒ mastered", mastered.isMastered(ttrThreshold: 1.0))
var slow = CharacterStats(character: "M")
for _ in 0..<5 { slow.record(correct: true, ttr: 2.0) }
check("accurate but slow ⇒ not mastered", !slow.isMastered(ttrThreshold: 1.0))
var sloppy = CharacterStats(character: "R")
for i in 0..<5 { sloppy.record(correct: i % 2 == 0, ttr: 0.5) }
check("fast but inaccurate ⇒ not mastered", !sloppy.isMastered(ttrThreshold: 1.0))

// Engine: questions
print("\nQuestion generation:")
let engine = TrainerEngine(seedCount: 2, rng: SeededRNG(seed: 42))
let q = engine.nextQuestion()
check("4 options", q.options.count == 4)
check("options are distinct", Set(q.options).count == 4)
check("target is among the options", q.options.contains(q.target))

// Engine: progression
print("\nProgression (Koch):")
let prog = TrainerEngine(seedCount: 2, rng: SeededRNG(seed: 7))
let startCount = prog.activeCharacters.count
var added: Character? = nil
for _ in 0..<200 {
    let q = prog.nextQuestion()
    if let a = prog.record(answer: q.target, for: q, ttr: 0.5).addedCharacter {
        added = a; break
    }
}
check("a new character is introduced once all are mastered", added != nil)
check("active set grew by exactly one", prog.activeCharacters.count == startCount + 1)
if let a = added { print("    → added '\(a)'; active set is now \(prog.activeCharacters.map(String.init).joined())") }

// Engine: weighting toward weak characters
print("\nFocus on missed characters:")
let w = TrainerEngine(seedCount: 2, rng: SeededRNG(seed: 1))
let chars = w.activeCharacters
for _ in 0..<5 {
    w.record(answer: chars[0], for: .init(target: chars[0], options: chars), ttr: 0.4)
    w.record(answer: chars[0], for: .init(target: chars[1], options: chars), ttr: 0.4) // wrong
}
check("missed character gets more practice weight",
      w.weight(for: chars[1]) > w.weight(for: chars[0]))

// Persistence
print("\nSave / load:")
do {
    let e = TrainerEngine(seedCount: 3, rng: SeededRNG(seed: 3))
    for _ in 0..<10 { let q = e.nextQuestion(); e.record(answer: q.target, for: q, ttr: 0.7) }
    let data = try JSONEncoder().encode(e.snapshot)
    let restored = try JSONDecoder().decode(TrainerEngine.Snapshot.self, from: data)
    let fresh = TrainerEngine(seedCount: 1)
    fresh.restore(from: restored)
    check("active set survives a save/load round-trip", fresh.activeCharacters == e.activeCharacters)
    check("stats survive a save/load round-trip", fresh.stats.count == e.stats.count)
} catch {
    check("encode/decode without throwing", false)
}

// Phrase data & quiz
print("\nPhrase data & quiz:")
let abbrevItems = MorseData.abbreviationItems
let es = abbrevItems.first { $0.id == "ES" }
check("abbreviation ES means 'and'", es?.answer == "and")
check("ES sound key is E+S concatenated (....)", es?.soundKey == "....")
let prosignItems = MorseData.prosignItems
check("prosign <AR> plays a run-together pattern",
      prosignItems.first { $0.id == "<AR>" }?.playable == .pattern(".-.-."))
check("words list is non-trivial", MorseData.wordItems.count > 50)

let pq = PhraseQuiz(name: "Abbreviations", items: abbrevItems, rng: SeededRNG(seed: 9))
let d = pq.nextDrill()
check("phrase drill has 4 options", d.options.count == 4)
check("phrase options are distinct", Set(d.options).count == 4)
check("phrase options include the correct meaning", d.options.contains(d.correct))
check("recording the correct meaning scores correct",
      pq.record(choice: d.correct, ttr: 0.8).correct == true)

// Character engine via the shared QuizSource protocol
print("\nUnified quiz protocol:")
let src: QuizSource = TrainerEngine(seedCount: 2, rng: SeededRNG(seed: 5))
let cd = src.nextDrill()
check("character drill exposes 4 string options", cd.options.count == 4)
check("character drill correct is among options", cd.options.contains(cd.correct))
check("recording correct via protocol scores correct",
      src.record(choice: cd.correct, ttr: 0.5).correct == true)

// Progressive character ladder
print("\nProgressive ladder (singles → pairs → triples → words):")
do {
    let eng = TrainerEngine(seedCount: 2, rng: SeededRNG(seed: 11))
    // Master the whole single-character set so the ladder can advance.
    eng.setActiveCharacters(MorseCode.kochOrder)
    let ladder = ProgressiveCharacters(engine: eng, rng: SeededRNG(seed: 11))
    check("ladder starts at singles", ladder.stage == .singles)

    var unlockedPairs = false
    for _ in 0..<3000 {
        let d = ladder.nextDrill()
        // Always answer correctly & fast to drive progression.
        if ladder.record(choice: d.correct, ttr: 0.4).unlocked == ProgressiveCharacters.Stage.pairs.displayName {
            unlockedPairs = true; break
        }
    }
    if !unlockedPairs {
        print("    (debug) allMastered=\(eng.allActiveMastered) active=\(eng.activeCharacters.count)")
    }
    check("singles complete unlocks Pairs", unlockedPairs)
    check("stage advanced to pairs", ladder.stage == .pairs)

    // A pairs/triples drill should present 4 distinct multi-character options.
    var sawGroup = false
    for _ in 0..<30 {
        let d = ladder.nextDrill()
        if case .text(let s) = d.playable, s.count >= 2 {
            sawGroup = (d.options.count == 4 && Set(d.options).count == 4 && d.options.contains(d.correct))
            if sawGroup { break }
        }
    }
    check("group drill has 4 distinct options incl. correct", sawGroup)

    // Drive pairs → triples → phrases.
    var reachedPhrases = false
    for _ in 0..<2000 {
        let d = ladder.nextDrill()
        _ = ladder.record(choice: d.correct, ttr: 0.4)
        if ladder.stage == .phrases { reachedPhrases = true; break }
    }
    check("ladder reaches Words & Call Signs", reachedPhrases)
}

// Confusion-pair tracking & drill
print("\nConfusion pairs:")
do {
    let eng = TrainerEngine(seedCount: 2, rng: SeededRNG(seed: 21))
    eng.setActiveCharacters(["X", "Y", "B", "P", "E", "T"])
    let qX = TrainerEngine.Question(target: "X", options: ["X", "Y", "B", "P"])
    // Heard X but answered Y three times, answered B once.
    eng.record(answer: "Y", for: qX, ttr: 0.5)
    eng.record(answer: "Y", for: qX, ttr: 0.5)
    eng.record(answer: "Y", for: qX, ttr: 0.5)
    eng.record(answer: "B", for: qX, ttr: 0.5)
    check("a wrong answer records who you confused it with",
          eng.confusions.count(target: "X", chosen: "Y") == 3)
    eng.record(answer: "X", for: qX, ttr: 0.4)   // a correct answer …
    check("a correct answer records no confusion",
          eng.confusions.count(target: "X", chosen: "X") == 0)
    check("strongest confusion is heard-X-picked-Y",
          eng.confusions.entries().first.map { $0.target == "X" && $0.chosen == "Y" } == true)
    check("strongest unordered pair is X/Y with count 3",
          eng.confusions.pairs().first.map { $0.count == 3 && Set([$0.a, $0.b]) == Set<Character>(["X", "Y"]) } == true)

    eng.easeConfusion(target: "X", chosen: "Y")
    check("a correct recognition eases the pair",
          eng.confusions.count(target: "X", chosen: "Y") == 2)

    // The confusion drill should pit the confused pair head-to-head.
    let cq = ConfusionQuiz(engine: eng, rng: SeededRNG(seed: 3))
    let cdrill = cq.nextDrill()
    check("confusion drill has 4 distinct options",
          cdrill.options.count == 4 && Set(cdrill.options).count == 4)
    check("confusion drill includes the correct answer", cdrill.options.contains(cdrill.correct))
    check("confusion drill targets a confused character (X)", cdrill.correct == "X")
    check("confusion drill offers a real confuser on the buttons",
          cdrill.options.contains("Y") || cdrill.options.contains("B"))

    // Recording a review answer must not graduate a new Koch character.
    let beforeCount = eng.activeCharacters.count
    _ = cq.record(choice: cdrill.correct, ttr: 0.4)
    check("confusion drill records without introducing a new character",
          eng.activeCharacters.count == beforeCount)

    // Always usable, even before any errors exist.
    let fresh = TrainerEngine(seedCount: 4, rng: SeededRNG(seed: 9))
    let cq2 = ConfusionQuiz(engine: fresh, rng: SeededRNG(seed: 9))
    let fdrill = cq2.nextDrill()
    check("confusion drill works before any confusion data exists",
          fdrill.options.count == 4 && fdrill.options.contains(fdrill.correct))

    // Confusion data survives a save/load round-trip.
    do {
        let data = try JSONEncoder().encode(eng.snapshot)
        let restored = try JSONDecoder().decode(TrainerEngine.Snapshot.self, from: data)
        let e2 = TrainerEngine(seedCount: 1)
        e2.restore(from: restored)
        check("confusions survive a save/load round-trip",
              e2.confusions.count(target: "X", chosen: "Y") == eng.confusions.count(target: "X", chosen: "Y"))
    } catch {
        check("confusion encode/decode without throwing", false)
    }
}

// QSO simulator (Phase 1: typed POTA, single station)
print("\nQSO simulator:")
do {
    let qso = QSOSimulator(rng: SeededRNG(seed: 42))
    let call = qso.station.call
    let state = qso.station.state

    let d0 = qso.nextDrill()
    check("the first step copies the station's callsign", d0.correct == call)
    check("each QSO step carries a question", !d0.question.isEmpty)
    check("typed answer is graded case/space-insensitively",
          qso.record(choice: " \(call.lowercased()) ", ttr: 0.5).correct == true)

    let d1 = qso.nextDrill()
    check("the second step copies the station's state", d1.correct == state)
    check("the two steps ask different questions", d0.question != d1.question)

    let out = qso.record(choice: state, ttr: 0.5)
    check("finishing the exchange logs one QSO", qso.completedQSOs == 1)
    check("completing a contact reports it as logged (unlocked)", out.unlocked == call)

    check("a wrong copy scores incorrect",
          QSOSimulator(rng: SeededRNG(seed: 7)).record(choice: "NOPE", ttr: 0.5).correct == false)

    let d2 = qso.nextDrill()
    check("a fresh QSO begins after the last step", !d2.correct.isEmpty)
}

// Word tiers (ham-weighted Top N words)
print("\nWord tiers:")
check("ranked word list has at least 500 entries", MorseData.rankedWords.count >= 500)
check("ranked words are unique", Set(MorseData.rankedWords).count == MorseData.rankedWords.count)
check("Top 100 returns 100 items", MorseData.topWordItems(100).count == 100)
check("Top 300 returns 300 items", MorseData.topWordItems(300).count == 300)
check("Top 500 returns 500 items", MorseData.topWordItems(500).count == 500)
check("word item ids are unique", Set(MorseData.topWordItems(500).map { $0.id }).count == 500)
check("a smaller tier is a prefix of a larger tier",
      Array(MorseData.topWordItems(500).prefix(100)) == MorseData.topWordItems(100))
check("ham vocabulary ranks first (CQ in Top 100)",
      MorseData.topWordItems(100).contains { $0.answer == "CQ" })

print("\n────────────────────────────")
if failures == 0 {
    print("✅ All \(checks) checks passed.\n")
} else {
    print("❌ \(failures) of \(checks) checks FAILED.\n")
    exit(1)
}
