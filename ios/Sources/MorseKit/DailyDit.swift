import Foundation

/// **Daily Dit** — one five-letter word, sent in Morse, the same for every
/// player on a given date.
///
/// The shape of the game:
///
///   - Tap play to hear the day's word. Replays are free; guesses are the
///     currency.
///   - Up to `maxGuesses` guesses, each a real five-letter word.
///   - Every guess is scored per letter — right letter/right place, right
///     letter/wrong place, or not in the word at all.
///   - The code starts at whatever speed you chose (QRQ territory if you like)
///     and steps down `speedStepWpm` every `guessesPerSpeedStep` guesses, so a
///     word you can't catch at 75 WPM comes back within reach. What you brag
///     about is the speed you were still at when you got it.
///
/// Pure logic: no audio, no storage, no clock of its own — the caller passes
/// the date in. That keeps every rule here testable and keeps this port and the
/// Kotlin one honest against the same fixture.
public enum DailyDit {

    // MARK: - Rules

    /// Total guesses allowed in a day.
    public static let maxGuesses = 21
    /// Guesses between one speed step and the next.
    public static let guessesPerSpeedStep = 3
    /// How much the speed drops at each step.
    public static let speedStepWpm = 5.0
    /// The ladder never goes below this, however many guesses are spent.
    public static let minimumWpm = 10.0
    /// Every answer and every guess is this long.
    public static let wordLength = 5

    /// Speeds offered at the start of a puzzle, up to the 75 WPM the mode was
    /// asked for. Ordered easiest first.
    public static let startingSpeeds: [Double] = [20, 25, 30, 40, 50, 60, 75]

    /// Puzzle #1 is this civil date. Fixed forever: moving it renumbers every
    /// puzzle and changes which word every past share text refers to.
    public static let epoch = (year: 2026, month: 1, day: 1)

    /// Walks the answer list in steps of this size instead of straight through,
    /// so consecutive days aren't consecutive dictionary entries.
    ///
    /// It is prime, and the answer count (800) factors as 2⁵·5², so the two are
    /// coprime — which is the point: a stride coprime with the list length
    /// visits every word exactly once before repeating any of them. Sharing a
    /// factor would trap the puzzle in a short cycle, so the fixture pins the
    /// coprimality rather than trusting whoever next regenerates the words.
    public static let selectionStride = 389

    // MARK: - Which word, which day

    /// Days from 1970-01-01 to the given proleptic-Gregorian civil date.
    ///
    /// Howard Hinnant's `days_from_civil`, chosen over `Calendar` on purpose:
    /// it is exact integer arithmetic with no locale, no timezone database and
    /// no platform library in the way, so the Swift and Kotlin ports cannot
    /// disagree about what day it is — which for a *shared* daily puzzle is the
    /// whole ballgame.
    public static func daysFromCivil(year: Int, month: Int, day: Int) -> Int {
        let y = year - (month <= 2 ? 1 : 0)
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400                                       // [0, 399]
        let doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1  // [0, 365]
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy               // [0, 146096]
        return era * 146097 + doe - 719468
    }

    /// The puzzle number for a civil date. Puzzle #1 is `epoch`; dates before
    /// it clamp to #1 rather than going negative.
    public static func puzzleNumber(year: Int, month: Int, day: Int) -> Int {
        let today = daysFromCivil(year: year, month: month, day: day)
        let start = daysFromCivil(year: epoch.year, month: epoch.month, day: epoch.day)
        return max(1, today - start + 1)
    }

    /// The puzzle number for a date, read in the given time zone. Local date,
    /// not UTC: a player's "today" is the one on their own wall, and everyone
    /// playing on the 3rd gets the 3rd's word.
    public static func puzzleNumber(for date: Date, timeZone: TimeZone = .current) -> Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return puzzleNumber(year: c.year ?? epoch.year,
                            month: c.month ?? epoch.month,
                            day: c.day ?? epoch.day)
    }

    /// The answer for a puzzle number.
    public static func answer(forPuzzle number: Int) -> String {
        let pool = MorseData.dailyDitAnswers
        guard !pool.isEmpty else { return "" }
        // (number - 1) so puzzle #1 is the first step, not a no-op.
        let index = ((number - 1) % pool.count) * selectionStride % pool.count
        return pool[index]
    }

    /// The answer for a date in the given time zone.
    public static func answer(for date: Date, timeZone: TimeZone = .current) -> String {
        answer(forPuzzle: puzzleNumber(for: date, timeZone: timeZone))
    }

    // MARK: - Guess validation

    private static let allowedSet: Set<String> = Set(MorseData.dailyDitAllowed)
        .union(MorseData.dailyDitAnswers)

    /// Is this a word we accept as a guess? Case-insensitive; the answer pool
    /// is folded in, so the day's word is always guessable even if the corpus
    /// that built the allowed list moves under us.
    public static func isAllowedGuess(_ word: String) -> Bool {
        allowedSet.contains(normalize(word))
    }

    /// Upper-cased and stripped of anything that isn't a letter.
    public static func normalize(_ word: String) -> String {
        word.uppercased().filter { $0.isLetter }
    }

    // MARK: - Scoring

    /// How one letter of a guess came out.
    public enum Tile: String, Sendable, Codable, Equatable {
        /// Right letter, right place.
        case correct
        /// The word contains this letter, but not here.
        case present
        /// Not in the word (or not in it this many times).
        case absent

        /// The square used in the shareable grid.
        public var emoji: String {
            switch self {
            case .correct: return "🟩"
            case .present: return "🟨"
            case .absent:  return "⬜"
            }
        }
    }

    /// Score a guess against the answer.
    ///
    /// Two passes, and the second pass is the reason: exact matches are claimed
    /// first, then each remaining guess letter can only be marked `present` if
    /// an *unclaimed* copy of it is left in the answer. One pass would tell a
    /// player guessing SPEED against SPEND that both Es are somewhere in the
    /// word, when only one is.
    public static func score(guess: String, answer: String) -> [Tile] {
        let g = Array(normalize(guess))
        let a = Array(normalize(answer))
        guard g.count == a.count else { return [] }

        var tiles = [Tile](repeating: .absent, count: g.count)
        var unclaimed: [Character: Int] = [:]

        for i in g.indices where g[i] == a[i] { tiles[i] = .correct }
        for i in a.indices where g[i] != a[i] { unclaimed[a[i], default: 0] += 1 }
        for i in g.indices where tiles[i] != .correct {
            if let left = unclaimed[g[i]], left > 0 {
                tiles[i] = .present
                unclaimed[g[i]] = left - 1
            }
        }
        return tiles
    }

    // MARK: - The speed ladder

    /// The speed the next word is sent at, having spent `guessesUsed` guesses.
    public static func wpm(startingAt start: Double, guessesUsed: Int) -> Double {
        let steps = max(0, guessesUsed) / guessesPerSpeedStep
        return max(minimumWpm, start - speedStepWpm * Double(steps))
    }
}

// MARK: - A day's game

/// One scored guess, and the speed the word was being sent at when it was made.
public struct DailyDitRound: Sendable, Equatable, Codable {
    public let guess: String
    public let tiles: [DailyDit.Tile]
    public let wpm: Double

    public init(guess: String, tiles: [DailyDit.Tile], wpm: Double) {
        self.guess = guess
        self.tiles = tiles
        self.wpm = wpm
    }

    public var solved: Bool { !tiles.isEmpty && tiles.allSatisfy { $0 == .correct } }
}

/// Where a day's game stands.
public enum DailyDitOutcome: String, Sendable, Equatable, Codable {
    case playing, solved, lost
}

/// Why a guess wasn't scored. Each one is a message the UI shows as-is.
public enum DailyDitRejection: String, Sendable, Equatable, Codable {
    /// Not five letters.
    case wrongLength
    /// Five letters, but not a word we know.
    case notAWord
    /// The day is already won or lost.
    case finished

    public var message: String {
        switch self {
        case .wrongLength: return "Five letters."
        case .notAWord:    return "Not in the word list."
        case .finished:    return "Today's Daily Dit is done."
        }
    }
}

public enum DailyDitSubmission: Sendable, Equatable {
    case scored(DailyDitRound)
    case rejected(DailyDitRejection)
}

/// A single day's play, from first guess to share text.
///
/// A value type on purpose: it is the *whole* saved state of the day, so
/// persisting a game is encoding this and nothing else, and reloading it can't
/// half-restore. Repeating a guess you've already made is deliberately legal —
/// guesses buy speed steps, so spending one to drag the code slower is a real
/// tactic, not a mistake to guard against.
public struct DailyDitGame: Sendable, Equatable, Codable {
    public let puzzleNumber: Int
    public let answer: String
    /// The speed the first guess was played at.
    public let startingWpm: Double
    /// Playing without the dit-dah chart on screen. Recorded here because it
    /// belongs in the share text — the logic doesn't care.
    public var hideReference: Bool
    public private(set) var rounds: [DailyDitRound]

    public init(puzzleNumber: Int,
                answer: String,
                startingWpm: Double,
                hideReference: Bool = false,
                rounds: [DailyDitRound] = []) {
        self.puzzleNumber = puzzleNumber
        self.answer = DailyDit.normalize(answer)
        self.startingWpm = startingWpm
        self.hideReference = hideReference
        self.rounds = rounds
    }

    /// Today's game, ready to play.
    public static func today(startingWpm: Double,
                             hideReference: Bool = false,
                             date: Date = Date(),
                             timeZone: TimeZone = .current) -> DailyDitGame {
        let number = DailyDit.puzzleNumber(for: date, timeZone: timeZone)
        return DailyDitGame(puzzleNumber: number,
                            answer: DailyDit.answer(forPuzzle: number),
                            startingWpm: startingWpm,
                            hideReference: hideReference)
    }

    public var guessesUsed: Int { rounds.count }
    public var guessesLeft: Int { max(0, DailyDit.maxGuesses - guessesUsed) }

    /// The speed the word plays at right now.
    public var currentWpm: Double {
        DailyDit.wpm(startingAt: startingWpm, guessesUsed: guessesUsed)
    }

    /// The speed the winning guess was made at — what the brag sheet reports.
    public var solvedWpm: Double? { rounds.last(where: { $0.solved })?.wpm }

    public var outcome: DailyDitOutcome {
        if rounds.contains(where: { $0.solved }) { return .solved }
        return guessesUsed >= DailyDit.maxGuesses ? .lost : .playing
    }

    public var isFinished: Bool { outcome != .playing }

    /// Score a guess and, if it's a real one, spend a guess on it.
    public mutating func submit(_ raw: String) -> DailyDitSubmission {
        guard !isFinished else { return .rejected(.finished) }
        let word = DailyDit.normalize(raw)
        guard word.count == DailyDit.wordLength else { return .rejected(.wrongLength) }
        guard DailyDit.isAllowedGuess(word) else { return .rejected(.notAWord) }

        // Read the speed *before* appending: a guess is played at the speed it
        // was made at, and only the next one feels the step down.
        let round = DailyDitRound(guess: word,
                                  tiles: DailyDit.score(guess: word, answer: answer),
                                  wpm: currentWpm)
        rounds.append(round)
        return .scored(round)
    }

    /// Letters ruled out so far, for greying the on-screen keyboard. A letter
    /// only appears here if no guess has ever placed it in the word.
    public var eliminatedLetters: Set<Character> {
        var seen: Set<Character> = []
        var found: Set<Character> = []
        for round in rounds {
            for (letter, tile) in zip(round.guess, round.tiles) {
                seen.insert(letter)
                if tile != .absent { found.insert(letter) }
            }
        }
        return seen.subtracting(found)
    }

    // MARK: Share

    /// The pasteable brag sheet: a headline, the emoji grid, and where to play.
    ///
    /// Text, not an image, because it has to survive being pasted into a chat
    /// window. The grid is every guess — a long grid is the story of a hard
    /// day, and trimming it would misreport the score in the headline.
    public var shareText: String {
        var lines: [String] = [headline]
        lines.append(contentsOf: rounds.map { round in
            round.tiles.map(\.emoji).joined()
        })
        lines.append(DailyDit.shareLink)
        return lines.joined(separator: "\n")
    }

    /// "Daily Dit #245 — 4/21 at 60 WPM"
    public var headline: String {
        var line = "Daily Dit #\(puzzleNumber) — "
        switch outcome {
        case .solved:
            line += "\(guessesUsed)/\(DailyDit.maxGuesses)"
            if let wpm = solvedWpm { line += " at \(DailyDit.format(wpm: wpm)) WPM" }
        case .lost:
            line += "X/\(DailyDit.maxGuesses)"
        case .playing:
            line += "\(guessesUsed)/\(DailyDit.maxGuesses) so far"
        }
        if hideReference { line += " (no reference)" }
        return line
    }
}

public extension DailyDit {
    /// Where the share text sends people. Our own page, per the brief.
    static let shareLink = "anothermorsetrainer.app"

    /// Speeds are whole numbers in practice; don't print "60.0 WPM".
    static func format(wpm: Double) -> String {
        wpm == wpm.rounded() ? String(Int(wpm)) : String(format: "%.1f", wpm)
    }
}
