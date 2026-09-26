import Foundation

// The CWOps "CW 77" list (issue #240): the most-used words and abbreviations
// in a CW contact, which CW Academy advisors have students listen to at speed.
// The maintainer-supplied file (77_all_current.txt) is the authoritative
// source; it has 75 entries with RR, CL, WX, BEAM and AR each twice, so the
// set is those entries with repeats dropped, first occurrence kept: 70. The
// "CW 77" name is kept as the list is known. Pinned for both ports by
// fixtures/cw77.json — change the list there and here together.
//
// Token spelling is fixtures/qso-elements.json's: the five prosigns in the
// list (AR, AS, BT, KN, SK) are bracketed as `MorseData.prosigns` spells them
// and sent run-together; everything else is text — BK and CL as the
// abbreviations table's letter forms, K as a letter (its pattern is <K>'s),
// HW? with its question mark. Meanings repeat the wording of the
// abbreviation, Q-code, prosign and QSO-element tables where the token lives
// there too. The Kotlin twin is MorseDataCW77.kt.

extension MorseData {
    /// The set's name everywhere it is shown.
    public static let cw77Name = "CW 77"

    /// Bob Carter WR7Q's recommended playback for this list: 40 WPM with no
    /// Farnsworth stretching. The apps offer it as a one-tap preset.
    public static let cw77RecommendedWpm: Double = 40

    /// The CW 77 list, de-duplicated, in the source file's order.
    public static let cw77: [(token: String, meaning: String)] = [
        ("VVV", "test signal — tuning up"),
        ("VERT", "vertical antenna"),
        ("OM", "old man"),
        ("HR", "here"),
        ("XYL", "wife (ex young lady)"),
        ("QTH", "my location is"),
        ("RR", "roger roger — all received"),
        ("AGE", "age"),
        ("FB", "fine business (great)"),
        ("LID", "poor operator"),
        ("QSL", "acknowledge / received"),
        ("TKS", "thanks"),
        ("RIG", "radio"),
        ("QSB", "your signals are fading"),
        ("CQ", "calling any station"),
        ("CL", "closing down"),
        ("<KN>", "go ahead — named station only"),
        ("NAME", "name"),
        ("PKT", "packet radio"),
        ("QSO", "a contact"),
        ("TEST", "contest — calling CQ contest"),
        ("DE", "this is / from"),
        ("<BT>", "separator / new section"),
        ("73", "best regards"),
        ("QRM", "man-made interference"),
        ("BK", "break"),
        ("AGN", "again"),
        ("DIPOLE", "dipole antenna"),
        ("<SK>", "end of contact"),
        ("HW?", "how do you copy?"),
        ("QRP", "low power"),
        ("TNX", "thanks"),
        ("YRS", "years"),
        ("YL", "young lady"),
        ("QRX", "wait / stand by"),
        ("QRL", "this frequency is busy / in use"),
        ("ES", "and"),
        ("WX", "weather"),
        ("QRT", "stop sending / going off air"),
        ("K", "go ahead — any station"),
        ("QRS", "send slower"),
        ("88", "love and kisses"),
        ("PWR", "power"),
        ("RUNS", "runs — as in rig runs 100 watts"),
        ("CALL", "call sign"),
        ("VY", "very"),
        ("WIRE", "wire antenna"),
        ("YAGI", "Yagi beam antenna"),
        ("NR", "number"),
        ("RPT", "repeat / report"),
        ("OP", "operator"),
        ("<AR>", "over — end of message"),
        ("PSE", "please"),
        ("EL", "elements — of a beam antenna"),
        ("LOOP", "loop antenna"),
        ("ABT", "about"),
        ("QSY", "change frequency"),
        ("TU", "thank you"),
        ("HI", "laughter"),
        ("BEAM", "beam antenna"),
        ("RST", "signal report"),
        ("WATT", "watt"),
        ("<AS>", "wait / stand by"),
        ("TEMP", "temperature"),
        ("CPY", "copy"),
        ("QRZ", "who is calling me?"),
        ("ANT", "antenna"),
        ("QRN", "atmospheric noise / static"),
        ("CW", "continuous wave — Morse code"),
        ("DX", "distance")
    ]

    // MARK: Your own callsign and name (#240)

    /// The "Your callsign" field's default. Nobody's own call, so it counts
    /// as unset.
    public static let cw77PlaceholderCallsign = "W1AW"
    public static let cw77CallsignMeaning = "your call sign"
    public static let cw77NameMeaning = "your name"

    /// The learner's own callsign and name as extra CW 77 items, the rule
    /// pinned by `personal` in fixtures/cw77.json. Each is uppercased and
    /// loses every character with no Morse pattern; a name keeps one space
    /// between its words. An empty or placeholder callsign and an empty name
    /// are unset. Callsign first, then name; either is dropped when it is
    /// already a CW 77 token or repeats the one before it. Empty means there
    /// is nothing to add, and the switch is not offered.
    public static func cw77Personal(callsign: String, name: String) -> [(token: String, meaning: String)] {
        func sendable(_ s: Substring) -> String {
            String(s.uppercased().filter { MorseCode.pattern(for: $0) != nil })
        }
        let listed = Set(cw77.map(\.token))
        var out: [(token: String, meaning: String)] = []
        let call = callsign.split(whereSeparator: \.isWhitespace).map(sendable).joined()
        if !call.isEmpty, call != cw77PlaceholderCallsign, !listed.contains(call) {
            out.append((call, cw77CallsignMeaning))
        }
        let who = name.split(whereSeparator: \.isWhitespace).map(sendable)
            .filter { !$0.isEmpty }.joined(separator: " ")
        if !who.isEmpty, !listed.contains(who), who != out.last?.token {
            out.append((who, cw77NameMeaning))
        }
        return out
    }

    /// Listen & Learn's "CW 77" pool: items whose answer is the meaning, like
    /// `qsoElementItems`, with `personal` (from `cw77Personal`) after the 70.
    /// A bracketed token plays the run-together prosign; anything else, text.
    public static func cw77Items(personal: [(token: String, meaning: String)] = []) -> [MorseItem] {
        (cw77 + personal).map { entry in
            MorseItem(id: "cw77-\(entry.token)", playable: cw77Playable(entry.token),
                      answer: entry.meaning, display: entry.token)
        }
    }

    /// Common Words' "CW 77" pool: hear the token, choose the token.
    public static func cw77WordItems(personal: [(token: String, meaning: String)] = []) -> [MorseItem] {
        (cw77 + personal).map { entry in
            MorseItem(id: "cw77-\(entry.token)", playable: cw77Playable(entry.token),
                      answer: entry.token, display: entry.token)
        }
    }

    private static func cw77Playable(_ token: String) -> MorseItem.Playable {
        if let prosign = prosigns.first(where: { $0.name == token }) {
            return .pattern(prosign.pattern)
        }
        return .text(token)
    }
}
