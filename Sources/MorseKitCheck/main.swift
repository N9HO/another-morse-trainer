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
print("\nQuestion generation (choices grow with what the learner has met):")
let engine = TrainerEngine(seedCount: 2, rng: SeededRNG(seed: 42))
let q0 = engine.nextQuestion()
check("a brand-new learner sees a single option", q0.options.count == 1)
check("that lone option is the target", q0.options == [q0.target])
// Once a second character has been met, two options appear.
engine.setExposedCharacters(engine.activeCharacters)   // pretend both seeds were met
let q = engine.nextQuestion()
check("with two characters met, two options appear", q.options.count == 2)
check("options are distinct", Set(q.options).count == q.options.count)
check("target is among the options", q.options.contains(q.target))
// The count tops out at the configured cap (default 4).
let capped = TrainerEngine(seedCount: 6, rng: SeededRNG(seed: 42))
capped.setExposedCharacters(capped.activeCharacters)
check("choices are capped at the configured maximum (4)", capped.nextQuestion().options.count == 4)

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
    check("the met-characters set survives a save/load round-trip",
          fresh.exposedCharacters == e.exposedCharacters)
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
check("the first phrase drill shows a single option", d.options.count == 1)
check("that lone option is the correct answer", d.options == [d.correct])
check("recording the correct meaning scores correct",
      pq.record(choice: d.correct, ttr: 0.8).correct == true)
// As more items are heard, the choice count grows toward the cap (4).
var maxPhraseOpts = d.options.count
var phraseOptionsStayDistinct = true
for _ in 0..<60 {
    let dd = pq.nextDrill()
    maxPhraseOpts = max(maxPhraseOpts, dd.options.count)
    if Set(dd.options).count != dd.options.count { phraseOptionsStayDistinct = false }
    if !dd.options.contains(dd.correct) { phraseOptionsStayDistinct = false }
    _ = pq.record(choice: dd.correct, ttr: 0.8)
}
check("phrase choices grow to the cap (4) as items are heard", maxPhraseOpts == 4)
check("phrase options stay distinct and include the answer", phraseOptionsStayDistinct)

// Character engine via the shared QuizSource protocol
print("\nUnified quiz protocol:")
let src: QuizSource = TrainerEngine(seedCount: 2, rng: SeededRNG(seed: 5))
let cd = src.nextDrill()
check("a fresh character drill exposes a single string option", cd.options.count == 1)
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
    eng.setExposedCharacters(["X", "Y", "B", "P", "E", "T"])   // all have been met
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
    fresh.setExposedCharacters(fresh.activeCharacters)   // all four have been met
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

// Voice response matching
print("\nVoice matching:")
do {
    let matcher = VoiceMatcher()
    let letters = ["B", "E", "R", "P"]

    let nato = matcher.interpret(["bravo"], candidates: letters)
    check("NATO 'bravo' → B, confidently", nato.token == "B" && nato.isConfident)

    let homophone = matcher.interpret(["bee"], candidates: ["B", "D", "E", "P"])
    check("letter name 'bee' → B", homophone.token == "B" && homophone.isConfident)

    let digit = matcher.interpret(["niner"], candidates: ["9", "1", "5", "E"])
    check("ham digit 'niner' → 9", digit.token == "9" && digit.isConfident)

    let word = matcher.interpret(["the"], candidates: ["THE", "HE", "BE", "TEN"])
    check("spoken word 'the' → THE", word.token == "THE" && word.isConfident)

    let spelled = matcher.interpret(["charlie quebec"], candidates: ["CQ", "DE", "RST"])
    check("spelled NATO 'charlie quebec' → CQ", spelled.token == "CQ")

    let garbled = matcher.interpret(["zzzz"], candidates: ["B", "E"])
    check("garbled input yields a guess but not confidently",
          garbled.token != nil && !garbled.isConfident)

    let ranked = matcher.rankedCandidates(["three"], pool: ["V", "E", "P", "3", "B", "T"], limit: 4)
    check("ranked fallback puts the closest ('3') first", ranked.first == "3")
    check("ranked fallback returns at most the limit", ranked.count == 4)

    let ctx = matcher.contextualStrings(for: ["B"])
    check("contextual strings include the NATO word for B", ctx.contains("bravo"))
    check("contextual strings include the letter name for B", ctx.contains("bee"))
}

print("\nVoice profile (personalization):")
do {
    var profile = VoiceProfile()
    check("a fresh profile is empty", profile.isEmpty)
    check("unknown phrase has no suggestion", profile.suggestion(for: "wotsit") == nil)

    profile.record(heard: "Wotsit", answer: "B")
    profile.record(heard: "wotsit", answer: "B")
    profile.record(heard: "wotsit", answer: "D")
    check("profile learns the user's mapping (majority wins)",
          profile.suggestion(for: "wotsit") == "B")
    check("suggestion is case/punctuation insensitive",
          profile.suggestion(for: "  wotsit! ") == "B")

    var matcher = VoiceMatcher(profile: profile)
    let ambiguous = matcher.interpret(["wotsit"], candidates: ["B", "D"])
    check("a learned phrase resolves an otherwise-ambiguous answer",
          ambiguous.token == "B" && ambiguous.isConfident)

    // Survives a JSON round-trip (how it's persisted).
    let data = try! JSONEncoder().encode(profile)
    let restored = try! JSONDecoder().decode(VoiceProfile.self, from: data)
    check("profile survives a JSON round-trip", restored == profile)
    matcher = VoiceMatcher(profile: restored)
    check("restored profile still personalizes",
          matcher.interpret(["wotsit"], candidates: ["B", "D"]).token == "B")
}

// QRQ high-speed timing (35 / 40 WPM)
print("\nQRQ high-speed timing:")
check("35 WPM dit ≈ 34.29 ms", approxEqual(MorseTiming(wpm: 35).dit, 1.2 / 35))
check("40 WPM dit ≈ 30.00 ms", approxEqual(MorseTiming(wpm: 40).dit, 0.030))
check("40 WPM is faster than 35 WPM", MorseTiming(wpm: 40).dit < MorseTiming(wpm: 35).dit)
check("QRQ uses standard spacing (no Farnsworth stretch)",
      abs(MorseTiming(wpm: 40).spacingUnit - MorseTiming(wpm: 40).unit) < 1e-9)

// Code proficiency exam (ARRL/FCC-style)
print("\nExam speeds & timing:")
check("'=' (BT) keys as -...-", MorseCode.pattern(for: "=") == "-...-")
check("novice exam is 5 WPM effective", ExamSpeed.novice5.effectiveWpm == 5)
check("novice uses Farnsworth (faster characters than effective)",
      ExamSpeed.novice5.characterWpm > ExamSpeed.novice5.effectiveWpm)
let novTiming = ExamSpeed.novice5.timing
check("novice character speed is ~13 WPM", approxEqual(novTiming.wpm, 13))
check("novice spacing is stretched (Farnsworth)",
      novTiming.characterGap > novTiming.elementGap * 3)
let genTiming = ExamSpeed.general13.timing
check("general exam is standard 13 WPM (no Farnsworth stretch)",
      genTiming.wpm == 13 && abs(genTiming.spacingUnit - genTiming.unit) < 1e-9)
check("extra exam is 20 WPM", ExamSpeed.extra20.effectiveWpm == 20)

print("\nExam passage & question generation:")
do {
    let session = ExamSession(speed: .general13, grading: .questions, rng: SeededRNG(seed: 42))
    let p = session.passage
    check("passage names the operator from the pool", MorseData.opNames.contains(p.name))
    check("passage QTH is a US state from the pool", MorseData.qthList.contains(p.qth))
    check("passage RST is from the pool", MorseData.rstValues.contains(p.rst))
    check("passage rig is from the pool", MorseData.rigs.contains(p.rig))
    check("two different callsigns in the exchange", p.toCall != p.deCall)
    check("sent text keys BT separators with '='", p.sentText.contains(" = "))
    check("sent text contains the operator name", p.sentText.contains(p.name))
    check("copy text drops the '=' separators", !p.copyText.contains("="))
    check("display text shows the <BT> prosign", p.displayText.contains("<BT>"))
    check("display text signs off with <KN>", p.displayText.contains("<KN>"))

    check("ten questions are generated", session.questions.count == 10)
    var allFour = true, allDistinct = true, allIncludeAnswer = true
    for q in session.questions {
        if q.options.count != 4 { allFour = false }
        if Set(q.options).count != q.options.count { allDistinct = false }
        if !q.options.contains(q.answer) { allIncludeAnswer = false }
    }
    check("every question offers 4 options", allFour)
    check("every question's options are distinct", allDistinct)
    check("every question includes its correct answer", allIncludeAnswer)

    let first = session.nextDrill()
    check("a question drill carries a prompt", !first.question.isEmpty)
    check("the first question plays the passage", first.playable == .text(p.sentText))
    check("a wrong answer scores incorrect",
          session.record(choice: "\u{1}nope", ttr: 0).correct == false)
    let d2 = session.nextDrill()
    check("a correct answer scores correct",
          session.record(choice: d2.correct, ttr: 0).correct == true)
    check("correct count tracks right answers", session.correctCount == 1)
    check("later questions don't replay the passage",
          d2.playable == .text(""))

    while !session.isComplete {
        let d = session.nextDrill()
        _ = session.record(choice: d.correct, ttr: 0)
    }
    check("exam completes after all questions are answered", session.isComplete)
}

print("\nExam solid-copy grading (25 in a row):")
do {
    let sample = MorseData.examSamples.first { $0.speed == .novice5 }!
    let session = ExamSession(speed: .novice5, grading: .solidCopy,
                              passage: sample.passage, rng: SeededRNG(seed: 1))
    let copy = session.passage.copyText
    check("required solid-copy run is the historical 25", ExamSession.requiredRun == 25)
    check("the passage is long enough to attempt 25 in a row", copy.count >= 25)

    let perfect = session.gradeSolidCopy(copy)
    check("a perfect copy passes", perfect.passed && perfect.longestRun == copy.count)
    check("exactly 25 characters in a row passes",
          session.gradeSolidCopy(String(copy.prefix(25))).passed)
    let r24 = session.gradeSolidCopy(String(copy.prefix(24)))
    check("24 characters in a row fails", !r24.passed)
    check("the 24-run reports a longest run of 24", r24.longestRun == 24)
    check("garbage copy fails", !session.gradeSolidCopy("zzzz qqqq wwww").passed)
    check("grading is case-insensitive",
          session.gradeSolidCopy(String(copy.prefix(25)).lowercased()).passed)
    check("a stray '=' in the copy is tolerated",
          session.gradeSolidCopy("= " + String(copy.prefix(25))).passed)

    check("record() grades a passing solid copy as correct",
          session.record(choice: copy, ttr: 0).correct == true)
    check("solid-copy drill exposes the copy target as the answer",
          ExamSession(speed: .novice5, grading: .solidCopy,
                      passage: sample.passage).nextDrill().correct == copy)

    // Bundled library is available at each speed.
    check("bundled exam passages exist for every speed",
          !MorseData.examSamples(for: .novice5).isEmpty
          && !MorseData.examSamples(for: .general13).isEmpty
          && !MorseData.examSamples(for: .extra20).isEmpty)
}

// MARK: - Pileup QSO engine

print("\nPileup QSO engine:")
do {
    func playCount(_ a: PileupEngine.Action) -> Int {
        if case .play(let v) = a { return v.count }
        return 0
    }

    // Callsign generator shape.
    var grng = SeededRNG(seed: 42)
    var allGood = true
    for _ in 0..<60 {
        let c = CallsignGenerator.generate(formats: [.oneByTwo], usOnly: true, using: &grng)
        let chars = Array(c)   // 1×2 US: prefix letter + digit + 2 letters
        if chars.count != 4 || !chars[0].isLetter || !chars[1].isNumber
            || !chars[2].isLetter || !chars[3].isLetter { allGood = false }
    }
    check("1×2 US callsigns have the shape L D L L", allGood)

    // CQ produces a pileup whose voice count matches the active stations.
    var cfg = PileupConfig()
    cfg.mode = .pota
    cfg.maxStations = 4
    cfg.minWPM = 20; cfg.maxWPM = 20
    let eng = PileupEngine(config: cfg, rng: SeededRNG(seed: 7))
    let cq = eng.callCQ()
    check("CQ raises a pileup", eng.activeCount >= 1)
    check("every active station answers the CQ", playCount(cq) == eng.activeCount)

    // Substring matching: a fragment repeats exactly the matching stations.
    let calls = eng.stations.map { $0.call }
    let frag = String(calls[0].prefix(1))
    let expectedMatches = calls.filter { $0.contains(frag) }.count
    if !calls.contains(frag) {     // only if the 1-char fragment isn't a full call
        let r = eng.send(frag)
        check("a fragment re-calls exactly the substring matches",
              playCount(r) == expectedMatches)
    }

    // Total bust under the forgiving default -> whole pileup re-calls.
    let before = eng.activeCount
    let bust = eng.send("ZZ9QXJ")
    check("a total bust re-calls the whole pileup (forgiving)", playCount(bust) == before)

    check("a miscopied call counts as a bust (issue #30)", eng.bustCount >= 1)

    // A miss-then-correct QSO must not report 100% accuracy (issue #30).
    do {
        var mcfg = PileupConfig()
        mcfg.mode = .pota
        mcfg.maxStations = 3
        mcfg.minWPM = 20; mcfg.maxWPM = 20
        let m = PileupEngine(config: mcfg, rng: SeededRNG(seed: 7))
        _ = m.callCQ()
        _ = m.send("ZZ9QXJ")                 // wrong call copy -> bust
        let realCall = m.stations[0].call
        _ = m.send(realCall)                 // work the right one
        _ = m.send(m.expectedCopy ?? "")     // clean exchange copy
        _ = m.logCurrent()                   // log it
        check("a QSO logged after a miscopy is below 100% accuracy",
              m.qsoCount == 1 && m.accuracy < 1.0)
    }

    // Exact full call -> exchange, then copy -> ready to log, then TU logs.
    let target = eng.stations[0].call
    let ex = eng.send(target)
    check("an exact full call triggers an exchange", playCount(ex) == 1)
    check("phase is now working that station", eng.workingStation?.call == target)
    let answer = eng.expectedCopy ?? ""
    check("a correct copy is accepted (silent, ready to log)", eng.send(answer) == .silence)
    if case .logged = eng.logCurrent() { check("TU logs the QSO", true) }
    else { check("TU logs the QSO", false) }
    check("the log now has one QSO", eng.qsoCount == 1 && eng.log.count == 1)

    // Cut numbers: the cut-letter form of a serial still grades correct.
    var ccfg = PileupConfig()
    ccfg.mode = .basicContest
    ccfg.maxStations = 1
    ccfg.cutNumbersEnabled = true
    ccfg.cutDigits = ["0", "9"]
    let ceng = PileupEngine(config: ccfg, rng: SeededRNG(seed: 11))
    _ = ceng.callCQ()
    _ = ceng.send(ceng.stations[0].call)
    let serial = ceng.expectedCopy ?? ""
    let cutForm = CutNumbers.encode(serial, enabled: ["0", "9"])
    check("typing the cut-number form grades correct", ceng.send(cutForm) == .silence)

    // Regression (issue #9): a faithful copy of what was *heard* — the exchange
    // sent twice for copyability, with the RST — must grade, not just the
    // de-duplicated expectedCopy. Previously "599 OH OH" was rejected and the
    // station endlessly repeated.
    var hcfg = PileupConfig()
    hcfg.mode = .pota
    hcfg.maxStations = 1
    let heng = PileupEngine(config: hcfg, rng: SeededRNG(seed: 11))
    _ = heng.callCQ()
    _ = heng.send(heng.stations[0].call)
    let potaCopy = heng.expectedCopy ?? ""              // e.g. "OH"
    check("typing the heard (doubled, with RST) POTA copy grades correct",
          heng.send("599 \(potaCopy) \(potaCopy)") == .silence)

    var sccfg = PileupConfig()
    sccfg.mode = .singleCaller
    sccfg.maxStations = 1
    let sceng = PileupEngine(config: sccfg, rng: SeededRNG(seed: 11))
    _ = sceng.callCQ()
    _ = sceng.send(sceng.stations[0].call)
    let opName = sceng.expectedCopy ?? ""               // e.g. "BOB"
    check("typing the heard 'OP BOB BOB' single-caller copy grades correct",
          sceng.send("599 OP \(opName) \(opName)") == .silence)

    // Field Day (issue #39): a correct exchange must grade however the operator
    // separates the class and section — spaced, run together, or with a slash.
    func fdWorked(_ seed: UInt64) -> (PileupEngine, String) {
        var f = PileupConfig(); f.mode = .fieldDay; f.maxStations = 1
        let e = PileupEngine(config: f, rng: SeededRNG(seed: seed))
        _ = e.callCQ(); _ = e.send(e.stations[0].call)
        return (e, e.expectedCopy ?? "")            // e.g. "9B KY"
    }
    do {
        let (_, copy) = fdWorked(11)
        let glued = copy.replacingOccurrences(of: " ", with: "")        // "9BKY"
        let slashed = copy.replacingOccurrences(of: " ", with: "/")     // "9B/KY"
        check("Field Day exchange grades when spaced", fdWorked(11).0.send(copy) == .silence)
        check("Field Day exchange grades run together (no space)", fdWorked(11).0.send(glued) == .silence)
        check("Field Day exchange grades with a slash separator", fdWorked(11).0.send(slashed) == .silence)
        let section = copy.split(separator: " ").last.map(String.init) ?? ""
        let wrong = "0Z" + section                                     // clearly wrong class, glued
        check("a wrong run-together Field Day copy is still rejected", fdWorked(11).0.send(wrong) != .silence)
    }

    // Give-up: an impatient station QRTs after repeated misses; pileup remains.
    var gcfg = PileupConfig()
    gcfg.mode = .pota
    gcfg.maxStations = 3
    gcfg.giveUpEnabled = true
    gcfg.giveUpMin = 1; gcfg.giveUpMax = 1     // patience 1 -> quits on the 2nd miss
    let geng = PileupEngine(config: gcfg, rng: SeededRNG(seed: 5))
    _ = geng.callCQ()
    _ = geng.send(geng.stations[0].call)       // start exchange
    let activeBefore = geng.activeCount
    _ = geng.send("WRONG")                      // miss 1
    _ = geng.send("WRONG")                      // miss 2 -> over patience, QRT
    check("an impatient station gives up after enough misses",
          geng.activeCount == activeBefore - 1)
    check("busts were tallied", geng.bustCount >= 2)

    // Prefix matching: a query brings back only stations starting with it.
    var pcfg = PileupConfig(); pcfg.mode = .pota; pcfg.maxStations = 4
    let peng = PileupEngine(config: pcfg, rng: SeededRNG(seed: 321))
    _ = peng.callCQ()
    let pcalls = peng.stations.map { $0.call }
    let pfx = String(pcalls[0].prefix(2))
    if !pcalls.contains(pfx) {
        let expected = pcalls.filter { $0.hasPrefix(pfx) }.count
        check("a prefix query brings back only prefix matches",
              playCount(peng.send(pfx + "?")) == expected)
    }

    // A trailing "?" is stripped: a full call + "?" still works the station.
    let qmarkEng = PileupEngine(config: pcfg, rng: SeededRNG(seed: 654))
    _ = qmarkEng.callCQ()
    let fullCall = qmarkEng.stations[0].call
    _ = qmarkEng.send(fullCall + "?")
    check("a full call with a trailing ? still works the station",
          qmarkEng.workingStation?.call == fullCall)

    // QRS actually slows the worked station.
    var scfg = PileupConfig(); scfg.mode = .pota; scfg.maxStations = 2
    scfg.minWPM = 30; scfg.maxWPM = 30
    let seng = PileupEngine(config: scfg, rng: SeededRNG(seed: 99))
    _ = seng.callCQ()
    _ = seng.send(seng.stations[0].call)
    let beforeWPM = seng.workingStation?.wpm ?? 0
    _ = seng.send("QRS")
    check("QRS slows the worked station", (seng.workingStation?.wpm ?? 99) < beforeWPM)
}

// Practice streak (consecutive-days motivation)
print("\nPractice streak (issue #20):")
do {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: "UTC")!
    func day(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 12) -> Date {
        cal.date(from: DateComponents(year: y, month: m, day: d, hour: h))!
    }

    var s = PracticeStreak()
    check("a fresh streak reads zero", s.current == 0 && s.longest == 0)
    check("display is zero before any practice", s.display(on: day(2026, 1, 1), calendar: cal) == 0)

    check("first practice starts a 1-day streak",
          s.record(on: day(2026, 1, 1), calendar: cal) == true && s.current == 1)
    check("practicing again the same day is a no-op",
          s.record(on: day(2026, 1, 1, 9), calendar: cal) == false && s.current == 1)
    check("a late-evening same-day session still doesn't double-count",
          s.record(on: day(2026, 1, 1, 23), calendar: cal) == false && s.current == 1)

    check("the next day extends the streak",
          s.record(on: day(2026, 1, 2), calendar: cal) == true && s.current == 2)
    s.record(on: day(2026, 1, 3), calendar: cal)
    check("a third consecutive day reaches 3", s.current == 3)
    check("longest tracks the running best", s.longest == 3)

    check("missing a full day resets the streak to 1",
          s.record(on: day(2026, 1, 5), calendar: cal) == true && s.current == 1)
    check("longest is preserved across a lapse", s.longest == 3)

    // display() reflects the streak as it stands "today" without mutating it.
    var d = PracticeStreak()
    d.record(on: day(2026, 1, 10), calendar: cal)
    d.record(on: day(2026, 1, 11), calendar: cal)   // current = 2
    check("display shows the streak on the practice day",
          d.display(on: day(2026, 1, 11), calendar: cal) == 2)
    check("display keeps the streak alive the next day (still extendable)",
          d.display(on: day(2026, 1, 12), calendar: cal) == 2)
    check("display zeroes out once a full day is missed",
          d.display(on: day(2026, 1, 13), calendar: cal) == 0)
    check("display is read-only (current is unchanged)", d.current == 2)

    do {
        let data = try JSONEncoder().encode(d)
        let restored = try JSONDecoder().decode(PracticeStreak.self, from: data)
        check("streak survives a save/load round-trip", restored == d)
    } catch {
        check("streak encode/decode without throwing", false)
    }

    // Milestones.
    check("3, 7, 30, 100 are milestones",
          PracticeStreak.isMilestone(3) && PracticeStreak.isMilestone(7)
          && PracticeStreak.isMilestone(30) && PracticeStreak.isMilestone(100))
    check("5 is not a milestone", !PracticeStreak.isMilestone(5))
    check("no milestone before the first one", PracticeStreak.milestone(forDay: 2) == nil)
    check("milestone(forDay:) reports the highest one reached",
          PracticeStreak.milestone(forDay: 10) == 7)
    check("exactly on a milestone reports that milestone",
          PracticeStreak.milestone(forDay: 30) == 30)
}

// Session history + recognition chart data (issue #19)
print("\nSession history & recognition chart (issue #19):")
do {
    // CharResult derived values.
    let b = SessionRecord.CharResult(character: "B", attempts: 5, correct: 5, medianTTR: 0.377)
    check("medianMS rounds seconds to whole milliseconds", b.medianMS == 377)
    check("accuracy is correct/attempts", abs(b.accuracy - 1.0) < 1e-9)
    let o = SessionRecord.CharResult(character: "O", attempts: 5, correct: 2, medianTTR: 0.219)
    check("a fast-but-inaccurate char reports low accuracy", abs(o.accuracy - 0.4) < 1e-9)
    let never = SessionRecord.CharResult(character: "Z", attempts: 3, correct: 0, medianTTR: nil)
    check("no correct answers ⇒ medianMS is nil", never.medianMS == nil)

    // Chart row ordering: letters A–Z first, then digits.
    check("character order puts letters before digits",
          SessionRecord.characterOrder("Z", "0") == true)
    check("character order is alphabetical within letters",
          SessionRecord.characterOrder("A", "B") == true)

    let rec = SessionRecord(
        id: UUID(), date: Date(), mode: "characters",
        characterWPM: 25, effectiveWPM: 7, attempts: 13, correct: 9,
        fastestTTR: 0.219, medianTTR: 0.45, durationSeconds: 300,
        characters: [b, o],
        activeCharacters: ["O", "B", "5"])
    let rows = rec.chartRows
    check("chart rows cover every active + drilled character",
          rows.map { $0.character } == ["B", "O", "5"])
    check("chart rows attach results to drilled characters",
          rows[0].result?.medianMS == 377)
    check("an active-but-undrilled character is a blank row",
          rows[2].character == "5" && rows[2].result == nil)

    // Axis ceiling.
    check("axis ceiling is at least 1000ms", SessionRecord.axisCeilingMS(300) == 1000)
    check("axis ceiling rounds up to the next 250", SessionRecord.axisCeilingMS(1105) == 1250)
    check("axis ceiling leaves an exact multiple alone", SessionRecord.axisCeilingMS(750) == 1000)

    // History: newest-first + bounded.
    var hist = SessionHistory()
    let older = SessionRecord(id: UUID(), date: Date(), mode: "words",
                              characterWPM: 20, effectiveWPM: 20, attempts: 1, correct: 1,
                              fastestTTR: nil, medianTTR: nil, durationSeconds: nil,
                              characters: [], activeCharacters: [])
    hist.add(older)
    hist.add(rec)
    check("most recent session is first", hist.sessions.first?.id == rec.id)
    check("history keeps prior sessions", hist.sessions.count == 2)

    var capped = SessionHistory()
    for _ in 0..<(SessionHistory.limit + 10) { capped.add(older) }
    check("history is bounded to the cap", capped.sessions.count == SessionHistory.limit)

    // Codable round-trip.
    do {
        let data = try JSONEncoder().encode(hist)
        let restored = try JSONDecoder().decode(SessionHistory.self, from: data)
        check("session history survives a save/load round-trip", restored == hist)
    } catch {
        check("session history encode/decode without throwing", false)
    }
}

// CW decoder
print("\nMorse decoder:")
check("reverse lookup: -..- is X", MorseCode.character(forPattern: "-..-") == "X")
check("reverse lookup of elements: .- is A", MorseCode.character(for: [.dit, .dah]) == "A")
check("unknown pattern returns nil", MorseCode.character(forPattern: "........") == nil)

// Build canned (tone, gap) timings for "PARIS" at 20 WPM (unit = 60 ms).
// dit=60 dah=180, element gap=60, letter gap=180, word gap=420.
do {
    let u = 60.0, dit = u, dah = 3 * u, eg = u, lg = 3 * u
    // Each character's elements, with element gaps between and a letter gap after.
    func charTimings(_ pattern: String, trailingGap: Double) -> [(tone: Double, gap: Double)] {
        let els = Array(pattern)
        return els.enumerated().map { i, c in
            (tone: c == "." ? dit : dah, gap: i == els.count - 1 ? trailingGap : eg)
        }
    }
    var timings: [(tone: Double, gap: Double)] = []
    timings += charTimings(".--.", trailingGap: lg) // P
    timings += charTimings(".-",   trailingGap: lg) // A
    timings += charTimings(".-.",  trailingGap: lg) // R
    timings += charTimings("..",   trailingGap: lg) // I
    timings += charTimings("...",  trailingGap: 0)  // S
    let decoded = MorseDecoder(wpm: 20).decodeTimings(timings)
    check("decodes PARIS at 20 WPM", decoded == "PARIS")

    // Two words separated by a word gap → a space.
    let twoWords = charTimings("-",  trailingGap: 7 * u) // T, then word gap
                 + charTimings(".",  trailingGap: 0)     // E
    check("word gap yields a space (T E → 'T E')",
          MorseDecoder(wpm: 20).decodeTimings(twoWords) == "T E")
}

// Custom word list (issue #32)
print("\nCustom word list:")
do {
    let parsed = MorseData.parseWordList("maine, ohio\ntexas  iowa,,\nMAINE")
    check("parse splits on commas/newlines/spaces and uppercases",
          parsed == ["MAINE", "OHIO", "TEXAS", "IOWA"])
    check("parse de-duplicates", parsed.filter { $0 == "MAINE" }.count == 1)
    check("empty input parses to no words", MorseData.parseWordList("  ,, \n ").isEmpty)

    let items = MorseData.customWordItems(["ohio", "ohio", "  "])
    check("custom items de-duplicate and drop blanks", items.count == 1)
    check("custom item plays the word as text", items.first?.answer == "OHIO")
}

print("\n────────────────────────────")
if failures == 0 {
    print("✅ All \(checks) checks passed.\n")
} else {
    print("❌ \(failures) of \(checks) checks FAILED.\n")
    exit(1)
}
