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

        public init(ituName: String?, alsoWritten: [String], description: String) {
            self.ituName = ituName
            self.alsoWritten = alsoWritten
            self.description = description
        }
    }

    /// Detail keyed by the prosign token exactly as it appears in `prosigns`.
    static let prosignDetail: [String: ReferenceDetail] = [
        "<AR>": ReferenceDetail(
            ituName: "End of message / Cross or addition sign (+)",
            alsoWritten: ["AR", "+"],
            description: "Ends a transmission when you are not handing over to a "
                + "named station — sent at the end of a CQ call and on blind "
                + "transmissions. Once a contact is established, K replaces it for "
                + "handing over. Identical to the punctuation \"+\"."),
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
                + "Often sent as the last thing before you sign clear."),
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
}
