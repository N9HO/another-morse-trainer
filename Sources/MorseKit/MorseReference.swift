import Foundation

/// Reference-only data and helpers for the browsable Reference screen. These
/// aren't quiz modes — they back the "look it up" tables (cut numbers, the full
/// alphabet/number chart) and the per-signal detail view (spoken rhythm, ITU
/// name, "also written", description). Kept apart from the quiz item builders in
/// MorseData so the training data stays untouched.
public extension MorseData {

    // MARK: Cut numbers (contest shorthand)

    /// Contest "cut numbers": a digit shortened to a single letter to save time.
    /// You *hear* the letter; it *means* the digit. Mirrors `CutNumbers.map`.
    static let cutNumbers: [(digit: String, letter: String)] = [
        ("0", "T"), ("1", "A"), ("2", "U"), ("3", "V"),
        ("5", "E"), ("7", "G"), ("8", "D"), ("9", "N")
    ]

    /// Cut numbers as reference rows: the token shown is the letter you hear on
    /// the air, the meaning is the digit it stands for, and the Morse played is
    /// that letter's pattern.
    static var cutNumberItems: [MorseItem] {
        cutNumbers.map {
            MorseItem(id: "cut-\($0.digit)",
                      playable: .text($0.letter),
                      answer: "\($0.digit) — cut \(spokenDigit($0.digit))",
                      display: $0.letter)
        }
    }

    // MARK: Full Morse chart (letters · digits · punctuation)

    /// The complete alphabet/number/punctuation chart, in reading order. The
    /// "meaning" line carries the spoken rhythm (e.g. "dah-di-dah") since there's
    /// nothing to translate — the value is hearing the shape.
    static var chartItems: [MorseItem] {
        let order: [Character] =
            Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            + Array("0123456789")
            + Array(".,?'!/()&:;=+-_\"$@")
        return order.compactMap { ch in
            guard let pattern = MorseCode.pattern(for: ch) else { return nil }
            return MorseItem(id: "chart-\(ch)",
                             playable: .text(String(ch)),
                             answer: spokenRhythm(for: pattern),
                             display: String(ch))
        }
    }

    // MARK: Spoken rhythm

    /// Turn a dot-dash pattern into the way operators say it out loud: dits are
    /// "di" mid-character and "dit" when final, dahs are always "dah". So `-.-`
    /// becomes "dah-di-dah" and `...-` becomes "di-di-di-dah".
    static func spokenRhythm(for pattern: String) -> String {
        let elements = Array(pattern)
        guard !elements.isEmpty else { return "" }
        return elements.enumerated().map { index, element in
            if element == "-" { return "dah" }
            return index == elements.count - 1 ? "dit" : "di"
        }.joined(separator: "-")
    }

    /// Spell a single digit, for the cut-number meaning line.
    private static func spokenDigit(_ digit: String) -> String {
        switch digit {
        case "0": return "zero"
        case "1": return "one"
        case "2": return "two"
        case "3": return "three"
        case "4": return "four"
        case "5": return "five"
        case "6": return "six"
        case "7": return "seven"
        case "8": return "eight"
        case "9": return "nine"
        default:  return digit
        }
    }

    // MARK: Per-signal encyclopedic detail

    /// Extra detail shown on the per-signal screen. Not every token has one — the
    /// detail view falls back to the row's meaning and computed rhythm when
    /// absent. Currently populated for prosigns, the signals that most reward an
    /// explanation of when and why they're sent.
    struct ReferenceDetail: Sendable, Equatable {
        /// The formal ITU/operational name, when the signal has one.
        public let ituName: String?
        /// Other ways the same signal is written (e.g. "AR", "+").
        public let alsoWritten: [String]
        /// A sentence or two on what it means and when it's used.
        public let description: String
        /// A short, one-line gist shown above the full description.
        public let summary: String?
        /// Dated, sourced definitions tracing how the signal has been defined
        /// over time — the "where this comes from" trail, newest authorities
        /// last. Empty when we haven't sourced it.
        public let citations: [Citation]

        public init(ituName: String?,
                    alsoWritten: [String],
                    description: String,
                    summary: String? = nil,
                    citations: [Citation] = []) {
            self.ituName = ituName
            self.alsoWritten = alsoWritten
            self.description = description
            self.summary = summary
            self.citations = citations
        }
    }

    /// One dated definition from a primary source — the apparatus that turns a
    /// reference entry into something you can trust and chase down.
    struct Citation: Sendable, Equatable, Identifiable {
        /// Year (or year range) of the source, e.g. "1925" or "2010/2021".
        public let date: String
        /// The publication or standard, e.g. "ITU-R M.1677-1".
        public let source: String
        /// How that source defines or labels the signal.
        public let label: String

        public init(date: String, source: String, label: String) {
            self.date = date
            self.source = source
            self.label = label
        }

        public var id: String { date + source }
    }

    /// Detail keyed by the prosign token exactly as it appears in `prosigns`.
    static let prosignDetail: [String: ReferenceDetail] = [
        "<AR>": ReferenceDetail(
            ituName: "End of message / Cross or addition sign (+)",
            alsoWritten: ["AR", "+"],
            description: "Ends a transmission when you are not handing over to a "
                + "named station — sent at the end of a CQ call and on blind "
                + "transmissions. Once a contact is established, K replaces it for "
                + "handing over. Identical to the punctuation \"+\".",
            summary: "End of transmission, no specific reply expected.",
            citations: [
                Citation(date: "1925", source: "QST Apr 1925 (Wallace)", label: "Finish sign"),
                Citation(date: "1955", source: "ARRL Learning the Code, 7th ed.", label: "End of transmission / end of message"),
                Citation(date: "2009", source: "ITU-R M.1677-1", label: "End of message / cross or addition sign (+)"),
                Citation(date: "2010/2021", source: "IARU Ethics & Operating Procedures", label: "End of a transmission (not end of contact)")
            ]),
        "<K>": ReferenceDetail(
            ituName: "Invitation to transmit (any station)",
            alsoWritten: ["K"],
            description: "\"Go ahead.\" Hands the transmission over and invites any "
                + "station to reply. Sent at the end of a call once contact is "
                + "established."),
        "<KN>": ReferenceDetail(
            ituName: "Invitation to a specific named station",
            alsoWritten: ["KN", "(", "[K]"],
            description: "\"Go ahead — you only.\" Like K, but invites a reply from "
                + "the specific station you're working and asks others to stand by."),
        "<BT>": ReferenceDetail(
            ituName: "Break / new section (double dash, =)",
            alsoWritten: ["BT", "=", "="],
            description: "Separates parts of a message — a pause or new paragraph. "
                + "Identical to the punctuation \"=\"."),
        "<SK>": ReferenceDetail(
            ituName: "End of work / end of contact",
            alsoWritten: ["SK", "VA"],
            description: "Closes out the whole contact, not just one transmission. "
                + "Often sent as the last thing before you sign clear.",
            summary: "End of contact — you're done working this station.",
            citations: [
                Citation(date: "1955", source: "ARRL Learning the Code, 7th ed.", label: "End of work (VA)"),
                Citation(date: "2009", source: "ITU-R M.1677-1", label: "End of work …−·−·−")
            ]),
        "<AS>": ReferenceDetail(
            ituName: "Wait / stand by",
            alsoWritten: ["AS"],
            description: "\"Wait a moment.\" Asks the other station to hold while you "
                + "do something — look up a detail, deal with a distraction."),
        "<BK>": ReferenceDetail(
            ituName: "Break-in",
            alsoWritten: ["BK"],
            description: "\"Back to you.\" A quick informal hand-over used in a "
                + "relaxed rag-chew to pass the transmission without a full sign-off."),
        "<CT>": ReferenceDetail(
            ituName: "Commencing / attention (KA)",
            alsoWritten: ["CT", "KA"],
            description: "Marks the start of a formal transmission — \"attention, here "
                + "it comes.\" Sent before the body of a message."),
        "<CL>": ReferenceDetail(
            ituName: "Closing station",
            alsoWritten: ["CL"],
            description: "\"Closing down — going off the air.\" Sent when you are "
                + "shutting the station, not merely ending a contact."),
        "<SN>": ReferenceDetail(
            ituName: "Understood / verified (VE)",
            alsoWritten: ["SN", "VE"],
            description: "\"Understood\" — acknowledges that what was sent was "
                + "received and verified.")
    ]

    /// Detail for the cut numbers, keyed by the letter you hear on the air. Cut
    /// numbers are a modern amateur-contest convention, not part of the
    /// historical ITU Morse standard — worth saying plainly, since the depth here
    /// is knowing what *isn't* official.
    static let cutNumberDetail: [String: ReferenceDetail] = [
        "T": ReferenceDetail(
            ituName: "Cut number for 0 (zero)",
            alsoWritten: ["0", "T"],
            description: "A long dah heard for 0 — the most common cut number, used "
                + "in RST reports and serials (5NN TT = 599 00). The T=0 cut has no "
                + "Paris 1865 precedent; it is a modern amateur contest convention, "
                + "not part of the ITU Morse standard.",
            summary: "Contest shorthand: 0 sent as the single letter T.",
            citations: [
                Citation(date: "2010/2021", source: "IARU Ethics & Operating Procedures", label: "599 RST courtesy context (implicit cut numbers)")
            ]),
        "N": ReferenceDetail(
            ituName: "Cut number for 9 (nine)",
            alsoWritten: ["9", "N"],
            description: "A dah-dit heard for 9, most often inside RST reports "
                + "(599 → 5NN). Like T=0, the N=9 cut has no Paris 1865 precedent — "
                + "it is a modern amateur convention. Cut numbers must not be used "
                + "in call signs.",
            summary: "Contest shorthand: 9 sent as the single letter N.",
            citations: [
                Citation(date: "1865", source: "Paris Convention", label: "9 = ----. (no abbreviated/cut variant)"),
                Citation(date: "2009", source: "ITU-R M.1677-1", label: "9 = ----. (cut form not in the standard)")
            ]),
        "A": ReferenceDetail(
            ituName: "Cut number for 1 (one)",
            alsoWritten: ["1", "A"],
            description: "A di-dah heard for 1. A modern contest convention to save "
                + "time in serial numbers and reports, not part of the ITU standard.",
            summary: "Contest shorthand: 1 sent as the single letter A."),
        "U": ReferenceDetail(
            ituName: "Cut number for 2 (two)",
            alsoWritten: ["2", "U"],
            description: "Di-di-dah heard for 2, used in serials and exchanges. "
                + "An amateur convention rather than an official Morse character.",
            summary: "Contest shorthand: 2 sent as the single letter U.")
    ]

    /// Detail for any reference token, checking prosigns first, then cut numbers.
    static func detail(forDisplay display: String) -> ReferenceDetail? {
        prosignDetail[display] ?? cutNumberDetail[display]
    }
}
