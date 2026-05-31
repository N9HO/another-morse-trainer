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

print("\n────────────────────────────")
if failures == 0 {
    print("✅ All \(checks) checks passed.\n")
} else {
    print("❌ \(failures) of \(checks) checks FAILED.\n")
    exit(1)
}
