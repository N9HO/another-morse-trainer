import Foundation

/// A single-station ragchew QSO simulator (inspired by MorseWalker).
///
/// You've called CQ and a station comes back. The simulator plays each of the
/// other operator's transmissions in Morse and, step by step, asks you to copy
/// what they sent — their callsign, the signal report they gave you, their
/// name, and their QTH — as a multiple-choice question with sound-alike
/// distractors. After the four-step exchange completes, a fresh station calls
/// and the next QSO begins.
///
/// Pure logic (no audio/UI) so it can be unit-tested on its own; it plugs into
/// the same `QuizSource` loop every other mode uses.
public final class QSOSimulator: QuizSource {

    /// The station you're currently working.
    public struct Station: Sendable, Equatable {
        public let call: String
        public let name: String
        public let qth: String
        public let rst: String   // the report THEY give YOU
    }

    public private(set) var station: Station
    public private(set) var completedQSOs = 0

    private var rng: any RandomNumberGenerator
    private var steps: [Step] = []
    private var index = 0

    private struct Step {
        let transmission: String   // what the station sends, in Morse
        let question: String       // what to copy
        let answer: String         // the correct copy
        let pool: [String]         // where sound-alike distractors come from
        let reveal: String         // shown when revealing on a miss
    }

    public init(rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.rng = rng
        // Placeholder so all stored properties are initialized before we can
        // call instance methods; immediately replaced by a real QSO.
        self.station = Station(call: "", name: "", qth: "", rst: "")
        startNewQSO()
    }

    // MARK: - QuizSource

    public var summary: String {
        completedQSOs == 0 ? "Working \(station.call)" : "\(completedQSOs) QSOs · \(station.call)"
    }

    public func nextDrill() -> Drill {
        if index >= steps.count { startNewQSO() }
        let step = steps[index]
        return Drill(
            playable: .text(step.transmission),
            options: makeOptions(answer: step.answer, pool: step.pool),
            correct: step.answer,
            revealPrimary: step.reveal,
            revealSecondary: "",
            question: step.question)
    }

    public func record(choice: String, ttr: TimeInterval) -> DrillOutcome {
        guard index < steps.count else { return DrillOutcome(correct: false, unlocked: nil) }
        let correct = choice == steps[index].answer
        index += 1
        if index >= steps.count { completedQSOs += 1 }
        return DrillOutcome(correct: correct, unlocked: nil)
    }

    // MARK: - QSO generation

    private func startNewQSO() {
        station = Self.randomStation(using: &rng)
        steps = Self.buildSteps(for: station)
        index = 0
    }

    private static func randomStation(using rng: inout any RandomNumberGenerator) -> Station {
        Station(
            call: MorseData.callSigns.randomElement(using: &rng) ?? "W1AW",
            name: MorseData.opNames.randomElement(using: &rng) ?? "JIM",
            qth: MorseData.qthList.randomElement(using: &rng) ?? "OH",
            rst: MorseData.rstValues.randomElement(using: &rng) ?? "599")
    }

    private static func buildSteps(for s: Station) -> [Step] {
        [
            Step(transmission: "\(s.call) \(s.call)",
                 question: "A station answered your CQ — what's their callsign?",
                 answer: s.call, pool: MorseData.callSigns, reveal: s.call),
            Step(transmission: "UR RST \(s.rst) \(s.rst)",
                 question: "What signal report did they give you?",
                 answer: s.rst, pool: MorseData.rstValues, reveal: "RST \(s.rst)"),
            Step(transmission: "NAME \(s.name) \(s.name)",
                 question: "What's their name?",
                 answer: s.name, pool: MorseData.opNames, reveal: "NAME \(s.name)"),
            Step(transmission: "QTH \(s.qth) \(s.qth)",
                 question: "What's their QTH (location)?",
                 answer: s.qth, pool: MorseData.qthList, reveal: "QTH \(s.qth)"),
        ]
    }

    // MARK: - Options

    /// Correct answer plus the three closest-sounding others from `pool`.
    private func makeOptions(answer: String, pool: [String]) -> [String] {
        let key = Self.soundKey(answer)
        let others = pool
            .filter { $0 != answer }
            .map { (val: $0, dist: MorseDistance.distance(key, Self.soundKey($0))) }
            .sorted { $0.dist != $1.dist ? $0.dist < $1.dist : $0.val < $1.val }

        var opts = [answer]
        for o in others where opts.count < 4 {
            if !opts.contains(o.val) { opts.append(o.val) }
        }
        // Safety pad for unusually small pools so there are always 4 buttons.
        var i = 0
        while opts.count < 4 {
            let filler = "\(answer)\(i)"
            if !opts.contains(filler) { opts.append(filler) }
            i += 1
        }
        opts.shuffle(using: &rng)
        return opts
    }

    private static func soundKey(_ s: String) -> String {
        s.uppercased().compactMap { MorseCode.pattern(for: $0) }.joined()
    }
}
