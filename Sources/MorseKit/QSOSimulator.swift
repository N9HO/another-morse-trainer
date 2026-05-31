import Foundation

/// A POTA-style QSO simulator (inspired by MorseWalker), Phase 1: a single
/// station works you and you **type what you copy**.
///
/// You've called "CQ POTA" as a park activator. A hunter comes back. The
/// simulator plays each of their transmissions in Morse and asks you to copy
/// the exchange — their callsign, then their state — by typing it. POTA
/// exchanges are short and fixed (callsign + state), so this drills the two
/// things you copy on every contact. After both fields are copied the QSO is
/// logged and the next station calls.
///
/// Pure logic (no audio/UI) so it can be unit-tested; it plugs into the same
/// `QuizSource` loop every other mode uses. Answers are graded by the typed
/// entry, which upper-cases and trims before calling `record`.
///
/// Phase 2 (not yet built): pileups (multiple simultaneous callers), QSB
/// fading, QRN noise, adjustable caller count, and zero-beat vs. offset
/// callers — see the memory notes.
public final class QSOSimulator: QuizSource {

    /// The station you're currently working.
    public struct Station: Sendable, Equatable {
        public let call: String
        public let state: String
    }

    public private(set) var station: Station
    public private(set) var completedQSOs = 0

    private var rng: any RandomNumberGenerator
    private var steps: [Step] = []
    private var index = 0

    private struct Step {
        let transmission: String   // what the station sends, in Morse
        let question: String       // what to copy
        let answer: String         // the correct copy (upper-case, trimmed)
        let pool: [String]         // where sound-alike distractors come from
        let reveal: String         // shown when revealing on a miss
    }

    public init(rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.rng = rng
        // Placeholder so all stored properties are initialized before we can
        // call instance methods; immediately replaced by a real QSO.
        self.station = Station(call: "", state: "")
        startNewQSO()
    }

    // MARK: - QuizSource

    public var summary: String {
        completedQSOs == 0 ? "CQ POTA · \(station.call)" : "\(completedQSOs) in the log"
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
        // Normalize the same way the typed entry does, so "w1aw " == "W1AW".
        let given = choice.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let correct = given == steps[index].answer
        index += 1
        // A logged contact ("unlocked") lets the UI celebrate the completed QSO.
        var logged: String? = nil
        if index >= steps.count {
            completedQSOs += 1
            logged = station.call
        }
        return DrillOutcome(correct: correct, unlocked: logged)
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
            state: MorseData.qthList.randomElement(using: &rng) ?? "OH")
    }

    private static func buildSteps(for s: Station) -> [Step] {
        [
            Step(transmission: "\(s.call) \(s.call)",
                 question: "A station answered your CQ POTA — copy their callsign:",
                 answer: s.call, pool: MorseData.callSigns, reveal: s.call),
            Step(transmission: "599 \(s.state) \(s.state)",
                 question: "Copy their exchange — what's their state?",
                 answer: s.state, pool: MorseData.qthList, reveal: "599 \(s.state)"),
        ]
    }

    // MARK: - Options (kept for an optional hint; typed entry ignores them)

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
