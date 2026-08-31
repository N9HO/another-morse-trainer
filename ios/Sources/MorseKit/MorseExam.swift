import Foundation

// An ARRL/FCC-style Morse code proficiency exam mode.
//
// Background: the historical FCC/VEC code exams (eliminated 2007-02-23) sent
// ~5 minutes of plain-language text styled as an on-air QSO — callsigns, name,
// QTH, rig, antenna, weather, RST, age, "73". To pass you needed EITHER one
// minute of solid copy (25 consecutive correct characters) OR to correctly
// answer ~10 fill-in questions about the content. License-tied speeds were
// 5 WPM (Novice), 13 WPM (General/Advanced) and 20 WPM (Amateur Extra); the
// 5 WPM test used Farnsworth (full-speed characters, stretched spacing).
//
// The genuine secured exam transcripts were never published, so this mode
// reproduces the *format* with procedurally generated (and a few bundled)
// QSO-style passages, plus both grading modes. Pure logic, no audio/UI, so it
// can be unit-tested and reuses the same `QuizSource` loop as the other modes.

// MARK: - Speed

/// A license-tied exam speed. Mirrors the three historical Morse requirements.
public enum ExamSpeed: String, Sendable, Codable, CaseIterable, Identifiable {
    case novice5     // 5 WPM  — Novice / Technician (the final single requirement)
    case general13   // 13 WPM — General / Advanced
    case extra20     // 20 WPM — Amateur Extra

    public var id: String { rawValue }

    /// Overall (effective) words-per-minute the passage is sent at.
    public var effectiveWpm: Double {
        switch self {
        case .novice5:   return 5
        case .general13: return 13
        case .extra20:   return 20
        }
    }

    /// Character (element) speed. The 5 WPM test used Farnsworth: characters sent
    /// at ~13 WPM with the spacing stretched so the *effective* rate is 5 WPM.
    public var characterWpm: Double {
        switch self {
        case .novice5:   return 13
        case .general13: return 13
        case .extra20:   return 20
        }
    }

    /// Correct timing for the speed (Farnsworth for the 5 WPM test).
    public var timing: MorseTiming {
        characterWpm > effectiveWpm
            ? MorseTiming(characterWpm: characterWpm, effectiveWpm: effectiveWpm)
            : MorseTiming(wpm: effectiveWpm)
    }

    public var wpmLabel: String { "\(Int(effectiveWpm)) WPM" }

    public var license: String {
        switch self {
        case .novice5:   return "Novice / Technician"
        case .general13: return "General / Advanced"
        case .extra20:   return "Amateur Extra"
        }
    }

    public var label: String { "\(wpmLabel) — \(license)" }
}

// MARK: - Grading mode

/// The two historical ways to pass the code exam.
public enum ExamGrading: String, Sendable, Codable, CaseIterable, Identifiable {
    case solidCopy   // one minute of solid copy: 25 consecutive correct chars
    case questions   // ~10 fill-in questions about the content of the message

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .solidCopy: return "Solid copy (25 in a row)"
        case .questions: return "Answer questions"
        }
    }
}

// MARK: - Passage

/// A generated (or bundled) QSO-style exam passage plus the structured facts
/// behind it, so questions can be asked about what was sent.
public struct ExamPassage: Sendable, Equatable {
    public let toCall: String    // the station being called (the examinee)
    public let deCall: String    // the sending station (the examiner)
    public let name: String
    public let qth: String       // US-state QTH
    public let rst: String
    public let rig: String
    public let power: String
    public let antenna: String
    public let weather: String
    public let temp: String
    public let age: String

    /// The keyed transmission, using "=" (BT) section separators and a final
    /// "K". Every character is sendable. This is what gets played in Morse.
    public let sentText: String
    /// A prosign-annotated, human-readable version for the reveal screen
    /// (separators shown as <BT>, sign-off as <KN>).
    public let displayText: String
    /// The gradable plain-text copy a candidate would write: `sentText` with the
    /// "=" separators removed and whitespace collapsed.
    public let copyText: String

    public init(toCall: String, deCall: String, name: String, qth: String,
                rst: String, rig: String, power: String, antenna: String,
                weather: String, temp: String, age: String) {
        self.toCall = toCall
        self.deCall = deCall
        self.name = name
        self.qth = qth
        self.rst = rst
        self.rig = rig
        self.power = power
        self.antenna = antenna
        self.weather = weather
        self.temp = temp
        self.age = age
        self.sentText = ExamPassage.render(toCall: toCall, deCall: deCall, name: name,
                                           qth: qth, rst: rst, rig: rig, power: power,
                                           antenna: antenna, weather: weather, temp: temp,
                                           age: age, sep: "=", closer: "K")
        self.displayText = ExamPassage.render(toCall: toCall, deCall: deCall, name: name,
                                              qth: qth, rst: rst, rig: rig, power: power,
                                              antenna: antenna, weather: weather, temp: temp,
                                              age: age, sep: "<BT>", closer: "<KN>")
        self.copyText = ExamPassage.normalize(self.sentText)
    }

    /// One ragchew template, parameterized by the section separator and sign-off
    /// so the keyed ("=" / "K") and pretty ("<BT>" / "<KN>") forms stay in sync.
    private static func render(toCall: String, deCall: String, name: String,
                               qth: String, rst: String, rig: String, power: String,
                               antenna: String, weather: String, temp: String,
                               age: String, sep: String, closer: String) -> String {
        "\(toCall) DE \(deCall) \(sep) GE OM ES TNX FER CALL \(sep) " +
        "UR RST \(rst) \(rst) \(sep) NAME HR IS \(name) \(name) \(sep) " +
        "QTH \(qth) \(qth) \(sep) RIG HR IS \(rig) ES PWR \(power) \(sep) " +
        "ANT IS \(antenna) \(sep) WX \(weather) ES TEMP \(temp) \(sep) " +
        "AGE \(age) \(sep) HW? \(toCall) DE \(deCall) \(closer)"
    }

    /// Reduce a string to a comparable copy stream: upper-cased, "=" separators
    /// dropped (candidates aren't expected to transcribe BT), and runs of
    /// whitespace collapsed to a single space. Used for both the reference text
    /// and the learner's typed copy so grading is apples-to-apples.
    public static func normalize(_ s: String) -> String {
        var out = ""
        var pendingSpace = false
        for ch in s.uppercased() {
            if ch == "=" { continue }
            if ch == " " || ch == "\n" || ch == "\t" {
                if !out.isEmpty { pendingSpace = true }
            } else {
                if pendingSpace { out.append(" "); pendingSpace = false }
                out.append(ch)
            }
        }
        return out
    }
}

/// The outcome of grading a solid-copy attempt.
public struct ExamCopyResult: Sendable, Equatable {
    /// Length of the longest run of consecutive characters the copy got right.
    public let longestRun: Int
    /// The bar to clear (the historical FCC rule: 25 in a row).
    public let required: Int
    public var passed: Bool { longestRun >= required }
    public init(longestRun: Int, required: Int) {
        self.longestRun = longestRun
        self.required = required
    }
}

// MARK: - Question

/// One fill-in question about the passage's content.
public struct ExamQuestion: Sendable, Equatable {
    public let prompt: String
    public let options: [String]   // distinct, includes `answer`
    public let answer: String
    public init(prompt: String, options: [String], answer: String) {
        self.prompt = prompt
        self.options = options
        self.answer = answer
    }
}

// MARK: - Session

/// Drives one exam: holds the passage, the questions, and the grading. Plugs
/// into the shared `QuizSource` loop, and also exposes a richer API the app's
/// bespoke exam screen uses (play the whole passage, then copy or answer).
public final class ExamSession: QuizSource {

    /// The historical "one minute of solid copy" bar: 25 consecutive characters.
    public static let requiredRun = 25

    public let speed: ExamSpeed
    public let grading: ExamGrading
    public private(set) var passage: ExamPassage
    public private(set) var questions: [ExamQuestion]

    public private(set) var questionIndex = 0
    public private(set) var correctCount = 0
    public private(set) var lastCopyResult: ExamCopyResult?

    private var rng: any RandomNumberGenerator

    /// Generate a random passage at the given speed.
    public convenience init(speed: ExamSpeed,
                            grading: ExamGrading,
                            questionCount: Int = 10,
                            rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        var rng = rng
        let passage = ExamSession.randomPassage(using: &rng)
        self.init(speed: speed, grading: grading, passage: passage,
                  questionCount: questionCount, rng: rng)
    }

    /// Build a session around a specific passage (e.g. a bundled one).
    public init(speed: ExamSpeed,
                grading: ExamGrading,
                passage: ExamPassage,
                questionCount: Int = 10,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        var r: any RandomNumberGenerator = rng
        let qs = ExamSession.makeQuestions(for: passage, count: questionCount, using: &r)
        self.speed = speed
        self.grading = grading
        self.passage = passage
        self.questions = qs
        self.rng = r
    }

    // MARK: QuizSource

    public var summary: String {
        switch grading {
        case .solidCopy:
            return "Code exam · \(speed.wpmLabel)"
        case .questions:
            return "Q \(min(questionIndex + 1, questions.count)) of \(questions.count)"
        }
    }

    public func nextDrill() -> Drill {
        switch grading {
        case .solidCopy:
            return Drill(
                playable: .text(passage.sentText),
                options: [],
                correct: passage.copyText,
                revealPrimary: passage.copyText,
                revealSecondary: "",
                question: "Copy the transmission, then type what you got. " +
                    "Pass = \(Self.requiredRun) correct characters in a row.")
        case .questions:
            let q = questions[min(questionIndex, max(0, questions.count - 1))]
            // The first question carries the passage so the loop plays it once;
            // later questions are silent (the passage isn't replayed).
            return Drill(
                playable: .text(questionIndex == 0 ? passage.sentText : ""),
                options: q.options,
                correct: q.answer,
                revealPrimary: q.answer,
                revealSecondary: "",
                question: q.prompt)
        }
    }

    public func record(choice: String, ttr: TimeInterval) -> DrillOutcome {
        switch grading {
        case .solidCopy:
            let result = gradeSolidCopy(choice)
            lastCopyResult = result
            return DrillOutcome(correct: result.passed, unlocked: nil)
        case .questions:
            guard questionIndex < questions.count else {
                return DrillOutcome(correct: false, unlocked: nil)
            }
            let correct = choice == questions[questionIndex].answer
            if correct { correctCount += 1 }
            questionIndex += 1
            let done = questionIndex >= questions.count
            return DrillOutcome(correct: correct, unlocked: done ? "exam complete" : nil)
        }
    }

    /// Whether every question has been answered (question mode).
    public var isComplete: Bool { questionIndex >= questions.count }

    // MARK: Solid-copy grading

    /// Grade a typed copy against the passage: find the longest run of
    /// consecutive characters that exactly matches the sent text.
    public func gradeSolidCopy(_ typed: String) -> ExamCopyResult {
        let a = Array(ExamPassage.normalize(typed))
        let b = Array(passage.copyText)
        return ExamCopyResult(longestRun: Self.longestCommonRun(a, b),
                              required: Self.requiredRun)
    }

    /// Length of the longest substring common to both character arrays
    /// (classic O(n·m) longest-common-substring DP, rolling row).
    static func longestCommonRun(_ a: [Character], _ b: [Character]) -> Int {
        if a.isEmpty || b.isEmpty { return 0 }
        var prev = [Int](repeating: 0, count: b.count + 1)
        var best = 0
        for i in 1...a.count {
            var cur = [Int](repeating: 0, count: b.count + 1)
            for j in 1...b.count where a[i - 1] == b[j - 1] {
                cur[j] = prev[j - 1] + 1
                if cur[j] > best { best = cur[j] }
            }
            prev = cur
        }
        return best
    }

    // MARK: Passage generation

    static func randomPassage(using rng: inout any RandomNumberGenerator) -> ExamPassage {
        let calls = MorseData.callSigns
        let toCall = calls.randomElement(using: &rng) ?? "W1AW"
        var deCall = calls.randomElement(using: &rng) ?? "K3LR"
        // Two different stations make a sensible exchange.
        var guard0 = 0
        while deCall == toCall && guard0 < 8 {
            deCall = calls.randomElement(using: &rng) ?? "K3LR"
            guard0 += 1
        }
        return ExamPassage(
            toCall: toCall,
            deCall: deCall,
            name: MorseData.opNames.randomElement(using: &rng) ?? "BOB",
            qth: MorseData.qthList.randomElement(using: &rng) ?? "OH",
            rst: MorseData.rstValues.randomElement(using: &rng) ?? "599",
            rig: MorseData.rigs.randomElement(using: &rng) ?? "K3",
            power: MorseData.powers.randomElement(using: &rng) ?? "100W",
            antenna: MorseData.antennas.randomElement(using: &rng) ?? "DIPOLE",
            weather: MorseData.weathers.randomElement(using: &rng) ?? "SUNNY",
            temp: MorseData.temps.randomElement(using: &rng) ?? "72F",
            age: MorseData.ages.randomElement(using: &rng) ?? "45")
    }

    // MARK: Question generation

    static func makeQuestions(for p: ExamPassage,
                              count: Int,
                              using rng: inout any RandomNumberGenerator) -> [ExamQuestion] {
        // (prompt, correct answer, pool to draw distractors from)
        let fields: [(String, String, [String])] = [
            ("What was the operator's name?",        p.name,    MorseData.opNames),
            ("What state (QTH) were they in?",       p.qth,     MorseData.qthList),
            ("What RST signal report did they send?", p.rst,    MorseData.rstValues),
            ("What rig (radio) were they using?",    p.rig,     MorseData.rigs),
            ("How much power were they running?",     p.power,  MorseData.powers),
            ("What antenna were they using?",        p.antenna, MorseData.antennas),
            ("What was the weather (WX) like?",      p.weather, MorseData.weathers),
            ("What was the temperature?",            p.temp,    MorseData.temps),
            ("How old is the operator?",             p.age,     MorseData.ages),
            ("What was the sending station's callsign?", p.deCall, MorseData.callSigns),
        ]
        var built = fields.map { field -> ExamQuestion in
            ExamQuestion(prompt: field.0,
                         options: makeOptions(answer: field.1, pool: field.2, using: &rng),
                         answer: field.1)
        }
        built.shuffle(using: &rng)
        if count < built.count { built = Array(built.prefix(count)) }
        return built
    }

    /// Four distinct options: the correct answer plus three random distractors
    /// from the same pool, shuffled.
    static func makeOptions(answer: String,
                            pool: [String],
                            using rng: inout any RandomNumberGenerator) -> [String] {
        var distractors = pool.filter { $0 != answer }
        distractors.shuffle(using: &rng)
        var options = [answer]
        for d in distractors where options.count < 4 {
            if !options.contains(d) { options.append(d) }
        }
        options.shuffle(using: &rng)
        return options
    }
}
