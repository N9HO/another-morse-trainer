import Foundation

/// One authored question for **Daily Comprehension** (#353): a short sentence
/// sent in Morse, a question about it, the right answer and three wrong ones.
public struct ComprehensionItem: Equatable, Sendable {
    public let id: String
    /// Upper-case A-Z and spaces only: everything in it is sendable.
    public let sentence: String
    public let question: String
    public let answer: String
    public let distractors: [String]
    /// 1 + the highest position, in `MorseCode.kochOrder`, of any letter in
    /// the sentence: how far down the Koch ladder a learner must be to know
    /// every character. Informational; see docs/daily-comprehension-design.md.
    public let kochLevel: Int

    public init(id: String, sentence: String, question: String, answer: String,
                distractors: [String], kochLevel: Int) {
        self.id = id
        self.sentence = sentence
        self.question = question
        self.answer = answer
        self.distractors = distractors
        self.kochLevel = kochLevel
    }
}

/// The question bank itself lives in the generated `ComprehensionBank.swift`.
public enum ComprehensionBank {}

/// **Daily Comprehension** (#353): one sentence in Morse, the same for every
/// player on a given date, answered by choosing among four options rather
/// than by copying it exactly.
///
/// Pure logic: no audio, no storage, no clock of its own. Selection mirrors
/// Daily Dit (same epoch, same puzzle number, same coprime-stride walk) and is
/// pinned by `fixtures/comprehension-selection.json`.
public enum DailyComprehension {

    /// Walks the bank in steps of this size so consecutive days are not
    /// consecutive entries. Coprime with the bank size (the fixture pins it),
    /// so every item is used once before any repeats.
    public static let selectionStride = 53
    /// Every question has its answer plus three distractors.
    public static let optionCount = 4

    static let lcgMultiplier: UInt32 = 1664525
    static let lcgIncrement: UInt32 = 1013904223

    /// Which bank entry a puzzle number selects.
    public static func itemIndex(forPuzzle number: Int, itemCount: Int) -> Int {
        guard itemCount > 0 else { return 0 }
        return ((max(1, number) - 1) % itemCount) * selectionStride % itemCount
    }

    public static func item(forPuzzle number: Int) -> ComprehensionItem {
        let bank = ComprehensionBank.items
        return bank[itemIndex(forPuzzle: number, itemCount: bank.count)]
    }

    /// The order the four options are shown in, seeded by the puzzle number so
    /// every player sees the same order. `result[k]` indexes
    /// `[answer, d1, d2, d3]` and is the option shown at position `k`.
    public static func optionOrder(forPuzzle number: Int) -> [Int] {
        var order = Array(0..<optionCount)
        var state = UInt32(truncatingIfNeeded: number)
        for i in stride(from: optionCount - 1, through: 1, by: -1) {
            state = state &* lcgMultiplier &+ lcgIncrement
            let j = Int(state >> 16) % (i + 1)
            order.swapAt(i, j)
        }
        return order
    }

    /// The option texts in display order, and where the right one landed.
    public static func options(forPuzzle number: Int) -> (texts: [String], correctPosition: Int) {
        let item = item(forPuzzle: number)
        let pool = [item.answer] + item.distractors
        let order = optionOrder(forPuzzle: number)
        return (order.map { pool[$0] }, order.firstIndex(of: 0) ?? 0)
    }

    /// Puzzle number for a date: the same numbering as Daily Dit.
    public static func puzzleNumber(for date: Date, timeZone: TimeZone = .current) -> Int {
        DailyDit.puzzleNumber(for: date, timeZone: timeZone)
    }
}

/// Today's Daily Comprehension: one answer, then it is over; listens are free
/// and counted. Codable so the app can keep it in UserDefaults.
public struct DailyComprehensionGame: Codable, Equatable, Sendable {
    public let puzzleNumber: Int
    /// The position the player picked, `nil` until they answer.
    public private(set) var chosenPosition: Int?
    public private(set) var listens: Int

    public init(puzzleNumber: Int, chosenPosition: Int? = nil, listens: Int = 0) {
        self.puzzleNumber = puzzleNumber
        self.chosenPosition = chosenPosition
        self.listens = listens
    }

    public var item: ComprehensionItem { DailyComprehension.item(forPuzzle: puzzleNumber) }
    public var options: [String] { DailyComprehension.options(forPuzzle: puzzleNumber).texts }
    public var correctPosition: Int { DailyComprehension.options(forPuzzle: puzzleNumber).correctPosition }
    public var isFinished: Bool { chosenPosition != nil }
    public var isCorrect: Bool { chosenPosition == correctPosition }

    /// Count a play of the sentence. Replays after answering are free.
    public mutating func noteListen() {
        if !isFinished { listens += 1 }
    }

    /// Lock in an answer. Returns false (and changes nothing) when the day is
    /// already answered or the position is out of range.
    @discardableResult
    public mutating func choose(_ position: Int) -> Bool {
        guard !isFinished, (0..<DailyComprehension.optionCount).contains(position) else { return false }
        chosenPosition = position
        return true
    }
}
