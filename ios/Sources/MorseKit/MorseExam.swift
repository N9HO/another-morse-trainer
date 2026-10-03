import Foundation

// An ARRL/FCC-style Morse code proficiency exam mode, graded the way the ARRL
// VEC graded it.
//
// Background: the historical FCC/VEC code exams (eliminated 2007-02-23) sent
// ~5 minutes of plain-language text styled as an on-air QSO — callsigns, name,
// QTH, rig, antenna, weather, RST, age, "73". License-tied speeds were 5 WPM
// (Novice), 13 WPM (General/Advanced) and 20 WPM (Amateur Extra); the 5 WPM
// test used Farnsworth (full-speed characters, stretched spacing). The text
// had to use every letter and numeral, period, comma, question mark, slant
// mark and the prosigns AR, BT and SK (47 CFR 97.503(a), 1998 edition).
//
// Grading, ARRL VEC practice (the FCC left the method to the VEs):
// - The candidate copies the whole message, then answers ten fill-in-the-blank
//   questions about it. Either path passes: one minute of solid copy, or seven
//   of the ten blanks right. (FCC 99-412 ¶37: the ARRL asked that VEs be held
//   to "a ten-question fill-in-the-blank examination or one minute of solid
//   copy".)
// - One minute of solid copy is 25 / 65 / 100 characters at 5 / 13 / 20 WPM,
//   with letters counting one and "numbers, punctuation and procedural
//   signals" counting two each (ARRL VE Manual, 8th edition, 2000). So the
//   prosigns are graded copy, not ignored.
// `fixtures/code-exam.json` pins every rule above, with its sources.
//
// The genuine secured exam transcripts were never published, so this mode
// reproduces the *format* with procedurally generated (and a few bundled)
// QSO-style passages. Pure logic, no audio/UI, so it can be unit-tested.

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

    /// Characters in a word, by the PARIS standard every WPM figure uses.
    public static let charactersPerWord = 5

    /// The solid-copy pass bar: one minute of copy at this exam's speed (#262),
    /// so 25 / 65 / 100 counted characters at 5 / 13 / 20 WPM. Counted the
    /// ARRL way: a numeral, punctuation mark or prosign is worth two letters
    /// (`ExamPassage.weight(of:)`).
    ///
    /// Two further choices, both pinned by `fixtures/code-exam.json`:
    /// - The speed is the *effective* (stated) WPM, not the character speed.
    ///   The 5 WPM exam sent 13 WPM characters, but a minute of it still held
    ///   five words, and the historical bar there was 25 characters.
    /// - Word spaces are not characters. PARIS is five letters; the space
    ///   between words is timing. `ExamSession.longestCommonRun` counts the
    ///   same way — a space inside a run has to match but adds nothing — so the
    ///   bar and the count measure the same thing.
    public var requiredRun: Int { Int(effectiveWpm) * Self.charactersPerWord }

    public var license: String {
        switch self {
        case .novice5:   return "Novice / Technician"
        case .general13: return "General / Advanced"
        case .extra20:   return "Amateur Extra"
        }
    }

    public var label: String { "\(wpmLabel) — \(license)" }

    /// The two ways to pass, as the setup, header and settings summary show
    /// them: "Solid copy (65) or 7 of 10 questions" at 13 WPM.
    public var passLabel: String {
        "Solid copy (\(requiredRun)) or \(ExamSession.questionsToPass) of \(ExamSession.questionCount) questions"
    }
}

// MARK: - Passage

/// A generated (or bundled) QSO-style exam passage plus the structured facts
/// behind it, so questions can be asked about what was sent.
public struct ExamPassage: Sendable, Equatable {
    public let toCall: String    // the station being called (the examinee)
    public let deCall: String    // the sending station (the examiner)
    /// The call area the sending station signs portable from ("4" → K9LA/4),
    /// so every passage keys a slash (#263).
    public let portable: String
    public let name: String
    public let qth: String       // US-state QTH
    public let rst: String
    public let rig: String
    public let power: String
    public let antenna: String
    public let weather: String
    public let temp: String
    public let age: String

    /// The keyed transmission. Prosigns are bracketed tokens (<BT> between
    /// sections, <AR> closing the message, <SK> on the final over), spelled as
    /// `MorseData.prosigns` spells them, which `MorseSynth` keys run-together;
    /// everything else is a sendable character. This is what gets played.
    public let sentText: String
    /// The version shown on the reveal screen. Now that the keyed text spells
    /// its prosigns the way the app displays them, it is the same string.
    public var displayText: String { sentText }
    /// The gradable copy a candidate would write: `sentText` through
    /// `normalize`. Prosigns stay in it, as bracketed tokens, because the ARRL
    /// graded them.
    public let copyText: String

    public init(toCall: String, deCall: String, portable: String, name: String,
                qth: String, rst: String, rig: String, power: String,
                antenna: String, weather: String, temp: String, age: String) {
        self.toCall = toCall
        self.deCall = deCall
        self.portable = portable
        self.name = name
        self.qth = qth
        self.rst = rst
        self.rig = rig
        self.power = power
        self.antenna = antenna
        self.weather = weather
        self.temp = temp
        self.age = age
        self.sentText = ExamPassage.render(toCall: toCall, deCall: deCall,
                                           portable: portable, name: name, qth: qth,
                                           rst: rst, rig: rig, power: power,
                                           antenna: antenna, weather: weather,
                                           temp: temp, age: age)
        self.copyText = ExamPassage.normalize(self.sentText)
    }

    /// One ragchew template. Its fixed wording carries everything the FCC/VEC
    /// exams had to include, whatever fields were drawn (#263): every letter
    /// (B, J, V, Y and Z come from "JUST GOT BACK … VY GLAD" and "1830Z"), every
    /// numeral ("1830Z ON 14.052 OR 7.069"), . , ? and the portable slash, and
    /// the prosigns — <BT> between sections, <AR> closing the message and <SK>
    /// on the final over. `fixtures/code-exam.json` lists the requirement.
    private static func render(toCall: String, deCall: String, portable: String,
                               name: String, qth: String, rst: String, rig: String,
                               power: String, antenna: String, weather: String,
                               temp: String, age: String) -> String {
        let de = "\(deCall)/\(portable)"
        return "\(toCall) DE \(de) <BT> GE OM ES TNX FER CALL. " +
            "UR RST \(rst) \(rst), NAME HR IS \(name) \(name), QTH \(qth) \(qth). <BT> " +
            "RIG HR IS \(rig) ES PWR \(power), ANT IS \(antenna). <BT> " +
            "WX \(weather) ES TEMP \(temp). AGE \(age), JUST GOT BACK ON THE AIR " +
            "ES VY GLAD TO WORK U. <BT> CAN WE SKED TMW AT 1830Z ON 14.052 OR 7.069? " +
            "HW? 73 <AR> \(toCall) DE \(de) <SK>"
    }

    /// Reduce a string to a comparable copy stream, used for both the reference
    /// text and the learner's typed copy so grading is apples-to-apples.
    ///
    /// Grading rule (pinned by `fixtures/code-exam.json`, identical in the
    /// Kotlin port). The ARRL VEC graded prosigns as copy, so they stay in the
    /// stream, each as its canonical bracketed token standing as its own word:
    /// - a bracketed token is a prosign: `<AR>`, `<sk>`, `< BT >` all read as
    ///   the token with its spaces dropped, upper-cased;
    /// - `+` is AR and `=` is BT, the written forms copy sheets used;
    /// - the letters `AR` without brackets are the letters A and R (Arkansas
    ///   in a QTH), never the prosign. The bracket or the symbol decides.
    /// Letters, digits and `. , ? /` are kept as written (`/` is DN). Upper-
    /// cased; whitespace runs collapse to one space, none leading or trailing.
    public static func normalize(_ s: String) -> String {
        var words: [String] = []
        var word = ""
        func endWord() { if !word.isEmpty { words.append(word); word = "" } }
        let chars = Array(s.uppercased())
        var i = 0
        while i < chars.count {
            let ch = chars[i]
            if ch == "<", let close = chars[(i + 1)...].firstIndex(of: ">") {
                let inner = String(chars[(i + 1)..<close]).filter { !$0.isWhitespace }
                endWord()
                if !inner.isEmpty { words.append("<\(inner)>") }
                i = close + 1
                continue
            }
            if ch == "+" || ch == "=" {
                endWord()
                words.append(ch == "+" ? "<AR>" : "<BT>")
            } else if ch.isWhitespace {
                endWord()
            } else {
                word.append(ch)
            }
            i += 1
        }
        endWord()
        return words.joined(separator: " ")
    }

    /// Split a normalized copy stream into the symbols the grader compares: a
    /// bracketed prosign is one symbol, a word space is one symbol, and every
    /// other character is one symbol.
    public static func symbols(_ normalized: String) -> [String] {
        var out: [String] = []
        let chars = Array(normalized)
        var i = 0
        while i < chars.count {
            if chars[i] == "<", let close = chars[(i + 1)...].firstIndex(of: ">") {
                out.append(String(chars[i...close]))
                i = close + 1
            } else {
                out.append(String(chars[i]))
                i += 1
            }
        }
        return out
    }

    /// What one copied symbol counts toward the one-minute bar, by the ARRL VEC
    /// rule: a letter counts one; a numeral, punctuation mark or prosign counts
    /// two; a word space counts nothing (it has to be in the right place, but
    /// it is timing, not a character).
    public static func weight(of symbol: String) -> Int {
        if symbol == " " { return 0 }
        if symbol.count == 1, let c = symbol.first, c.isASCII, c.isLetter { return 1 }
        return 2
    }
}

/// The outcome of grading a typed copy for one minute of solid copy.
public struct ExamCopyResult: Sendable, Equatable {
    /// The longest run of consecutive correct copy, counted the ARRL way
    /// (letters 1, numerals / punctuation / prosigns 2, word spaces 0).
    public let longestRun: Int
    /// The bar to clear: one minute of copy at the exam's speed.
    public let required: Int
    public var passed: Bool { longestRun >= required }
    public init(longestRun: Int, required: Int) {
        self.longestRun = longestRun
        self.required = required
    }
}

// MARK: - Question

/// One fill-in-the-blank question about the message, answered from the copy.
public struct ExamQuestion: Sendable, Equatable {
    /// The blank to fill, e.g. "The operator's name is ____."
    public let prompt: String
    /// The answer as it was sent.
    public let answer: String
    /// Every answer marked right (e.g. both K9LA/4 and K9LA).
    public let accepted: [String]
    public init(prompt: String, answer: String, accepted: [String]? = nil) {
        self.prompt = prompt
        self.answer = answer
        self.accepted = accepted ?? [answer]
    }

    /// Whether a typed answer fills the blank: compared the way copy is
    /// compared, with case and spacing ignored ("100 w" fills "100W").
    public func accepts(_ typed: String) -> Bool {
        let t = ExamQuestion.compact(typed)
        return !t.isEmpty && accepted.contains { ExamQuestion.compact($0) == t }
    }

    static func compact(_ s: String) -> String {
        ExamPassage.normalize(s).filter { $0 != " " }
    }
}

// MARK: - Result

/// Which way a sitting passed. The ARRL VEC passed a candidate on either.
public enum ExamPassPath: String, Sendable, Equatable {
    case solidCopy, questions, both, none
}

/// Both grades for one sitting.
public struct ExamResult: Sendable, Equatable {
    public let copy: ExamCopyResult
    public let questionsCorrect: Int
    public let questionsAsked: Int
    public let questionsRequired: Int

    public init(copy: ExamCopyResult, questionsCorrect: Int,
                questionsAsked: Int, questionsRequired: Int) {
        self.copy = copy
        self.questionsCorrect = questionsCorrect
        self.questionsAsked = questionsAsked
        self.questionsRequired = questionsRequired
    }

    public var passedByCopy: Bool { copy.passed }
    public var passedByQuestions: Bool {
        questionsAsked > 0 && questionsCorrect >= questionsRequired
    }
    public var passed: Bool { passedByCopy || passedByQuestions }
    public var path: ExamPassPath {
        switch (passedByCopy, passedByQuestions) {
        case (true, true):   return .both
        case (true, false):  return .solidCopy
        case (false, true):  return .questions
        case (false, false): return .none
        }
    }

    /// One line naming the way the sitting passed, for the results screen.
    public var pathText: String {
        switch path {
        case .both:      return "Passed on both: solid copy and the questions"
        case .solidCopy: return "Passed on one minute of solid copy"
        case .questions: return "Passed on the questions"
        case .none:      return "Neither solid copy nor the questions reached the bar"
        }
    }
}

// MARK: - Session

/// Drives one sitting, graded the way the ARRL VEC graded it: copy the whole
/// message, then fill in ten blanks about it from that copy. One minute of
/// solid copy passes, and so do seven right answers out of ten.
public final class ExamSession {

    /// Questions asked about the message, and how many must be right.
    public static let questionCount = 10
    public static let questionsToPass = 7

    public let speed: ExamSpeed

    /// The historical "one minute of solid copy" bar at this exam's speed.
    public var requiredRun: Int { speed.requiredRun }

    public let passage: ExamPassage
    public let questions: [ExamQuestion]

    public private(set) var questionIndex = 0
    public private(set) var correctCount = 0
    public private(set) var copyResult: ExamCopyResult

    /// Generate a random passage at the given speed.
    public convenience init(speed: ExamSpeed,
                            rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        var rng = rng
        self.init(speed: speed, passage: ExamSession.randomPassage(using: &rng))
    }

    /// Build a session around a specific passage (e.g. a bundled one).
    public init(speed: ExamSpeed, passage: ExamPassage) {
        self.speed = speed
        self.passage = passage
        self.questions = ExamSession.makeQuestions(for: passage)
        self.copyResult = ExamCopyResult(longestRun: 0, required: speed.requiredRun)
    }

    public var summary: String { "Code exam · \(speed.wpmLabel)" }

    // MARK: Solid copy

    /// Grade a typed copy against the passage: the longest run of consecutive
    /// symbols matching the sent text, weighted by the ARRL counting rule.
    public func gradeSolidCopy(_ typed: String) -> ExamCopyResult {
        let a = ExamPassage.symbols(ExamPassage.normalize(typed))
        let b = ExamPassage.symbols(passage.copyText)
        return ExamCopyResult(longestRun: Self.longestCommonRun(a, b),
                              required: requiredRun)
    }

    /// Hand in the copy: graded now and kept for the result.
    @discardableResult
    public func submitCopy(_ typed: String) -> ExamCopyResult {
        copyResult = gradeSolidCopy(typed)
        return copyResult
    }

    /// The longest run of symbols common to both, in order and unbroken, each
    /// cell holding the run's ARRL weight rather than its length: letters one,
    /// numerals / punctuation / prosigns two, word spaces nothing (a space
    /// inside the run still has to match). Longest-common-substring DP with a
    /// rolling row.
    public static func longestCommonRun(_ a: [String], _ b: [String]) -> Int {
        if a.isEmpty || b.isEmpty { return 0 }
        var prev = [Int](repeating: 0, count: b.count + 1)
        var best = 0
        for i in 1...a.count {
            var cur = [Int](repeating: 0, count: b.count + 1)
            for j in 1...b.count where a[i - 1] == b[j - 1] {
                cur[j] = prev[j - 1] + ExamPassage.weight(of: a[i - 1])
                if cur[j] > best { best = cur[j] }
            }
            prev = cur
        }
        return best
    }

    // MARK: Questions

    /// The blank being filled in, or nil once all are done.
    public var currentQuestion: ExamQuestion? {
        questionIndex < questions.count ? questions[questionIndex] : nil
    }

    /// Fill in the current blank and move on. Returns whether it was right.
    @discardableResult
    public func answer(_ typed: String) -> Bool {
        guard let q = currentQuestion else { return false }
        let right = q.accepts(typed)
        if right { correctCount += 1 }
        questionIndex += 1
        return right
    }

    /// Whether every question has been answered.
    public var isComplete: Bool { questionIndex >= questions.count }

    /// Both grades so far.
    public var result: ExamResult {
        ExamResult(copy: copyResult, questionsCorrect: correctCount,
                   questionsAsked: questions.count,
                   questionsRequired: Self.questionsToPass)
    }

    // MARK: Passage generation

    public static func randomPassage(using rng: inout any RandomNumberGenerator) -> ExamPassage {
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
            portable: String(Int.random(in: 0...9, using: &rng)),
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

    /// Ten fill-in blanks drawn from the passage's fields, in the order the
    /// message sends them, so they can be answered reading down the copy.
    public static func makeQuestions(for p: ExamPassage) -> [ExamQuestion] {
        let de = "\(p.deCall)/\(p.portable)"
        return [
            ExamQuestion(prompt: "The sending station's call sign is ____.",
                         answer: de, accepted: [de, p.deCall]),
            ExamQuestion(prompt: "The signal report (RST) is ____.", answer: p.rst),
            ExamQuestion(prompt: "The operator's name is ____.", answer: p.name),
            ExamQuestion(prompt: "The QTH (state) is ____.", answer: p.qth),
            ExamQuestion(prompt: "The rig is ____.", answer: p.rig),
            ExamQuestion(prompt: "The power is ____.", answer: p.power),
            ExamQuestion(prompt: "The antenna is ____.", answer: p.antenna),
            ExamQuestion(prompt: "The weather (WX) is ____.", answer: p.weather),
            ExamQuestion(prompt: "The temperature is ____.", answer: p.temp),
            ExamQuestion(prompt: "The operator's age is ____.", answer: p.age),
        ]
    }
}

