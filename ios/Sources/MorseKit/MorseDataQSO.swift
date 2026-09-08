import Foundation

// Curated on-air QSO vocabulary for Listen & Learn's "QSO elements" tiers
// (issue #182). One ordered list, most common first, so Top 20 is the first
// twenty and Top 100 is the whole list. Pinned for both ports by
// fixtures/qso-elements.json — change the list there and here together.
//
// Token spelling: letters, digits, ? and / are sent as text and a space is a
// word gap; a token in angle brackets is a prosign spelled exactly as
// `MorseData.prosigns` spells it and is sent run-together from that table's
// pattern. Meanings repeat the wording of `abbreviations` / `qCodes` /
// `prosigns` wherever the token already lives there.

extension MorseData {
    /// Sizes of the QSO-element tiers: the first N of `qsoElements`.
    public static let qsoTop20Count = 20
    public static let qsoTop100Count = 100

    /// The 100 most-heard on-air QSO elements, most common first.
    public static let qsoElements: [(token: String, meaning: String)] = [
        // Top 20: the skeleton of every contact.
        ("CQ", "calling any station"),
        ("DE", "this is / from"),
        ("<K>", "go ahead — any station"),
        ("<KN>", "go ahead — named station only"),
        ("<AR>", "over — end of message"),
        ("<SK>", "end of contact"),
        ("<BK>", "break — back to you"),
        ("TU", "thank you"),
        ("73", "best regards"),
        ("RST", "signal report"),
        ("599", "five nine nine — a perfect signal report"),
        ("5NN", "five nine nine — with N cut for 9"),
        ("UR", "your / you're"),
        ("NAME", "name"),
        ("QTH", "my location is"),
        ("HR", "here"),
        ("ES", "and"),
        ("HW?", "how do you copy?"),
        ("R", "received / roger"),
        ("TNX", "thanks"),
        // 21–100: the rest of a ragchew, a DX exchange and the weather.
        ("QSL", "acknowledge / received"),
        ("QRZ?", "who is calling me?"),
        ("QRL?", "is this frequency in use?"),
        ("AGN", "again"),
        ("PSE", "please"),
        ("RIG", "radio"),
        ("ANT", "antenna"),
        ("WX", "weather"),
        ("OM", "old man"),
        ("YL", "young lady"),
        ("XYL", "wife (ex young lady)"),
        ("GM", "good morning"),
        ("GA", "good afternoon"),
        ("GE", "good evening"),
        ("GN", "good night"),
        ("CUL", "see you later"),
        ("CUAGN", "see you again"),
        ("DX", "distance"),
        ("FB", "fine business (great)"),
        ("GUD", "good"),
        ("VY", "very"),
        ("ABT", "about"),
        ("CPY", "copy"),
        ("HW", "how do you copy"),
        ("NW", "now"),
        ("WID", "with"),
        ("PWR", "power"),
        ("WATTS", "watts"),
        ("DIPOLE", "dipole antenna"),
        ("VERT", "vertical antenna"),
        ("YAGI", "Yagi beam antenna"),
        ("TEMP", "temperature"),
        ("DEG", "degrees"),
        ("SUNNY", "sunny"),
        ("CLDY", "cloudy"),
        ("RAIN", "rain"),
        ("QRM", "man-made interference"),
        ("QRN", "atmospheric noise / static"),
        ("QSB", "your signals are fading"),
        ("QRS", "send slower"),
        ("QRQ", "send faster"),
        ("QRP", "low power"),
        ("QRO", "increase power"),
        ("QRT", "stop sending / going off air"),
        ("QRV", "I am ready"),
        ("QRX", "wait / stand by"),
        ("QSY", "change frequency"),
        ("QSO", "a contact"),
        ("QRZ", "who is calling me?"),
        ("QRL", "this frequency is busy / in use"),
        ("QSK", "full break-in"),
        ("BTU", "back to you"),
        ("HPE", "hope"),
        ("SRI", "sorry"),
        ("TKS", "thanks"),
        ("RR", "roger roger — all received"),
        ("TEST", "contest — calling CQ contest"),
        ("BCNU", "be seeing you"),
        ("CU", "see you"),
        ("NR", "number"),
        ("NIL", "nothing received"),
        ("OK", "okay"),
        ("FER", "for"),
        ("HI", "laughter"),
        ("MNI", "many"),
        ("RPT", "repeat / report"),
        ("SIG", "signal"),
        ("SKED", "schedule"),
        ("SN", "soon"),
        ("TMW", "tomorrow"),
        ("WKD", "worked"),
        ("WL", "well"),
        ("88", "love and kisses"),
        ("CONDX", "conditions"),
        ("OP", "operator"),
        ("HV", "have"),
        ("GL", "good luck"),
        ("CQ CQ CQ", "calling any station — the full call"),
        ("TNX FER CALL", "thanks for the call"),
        ("5NN TU", "five nine nine, thank you — the contest exchange")
    ]

    /// The short spoken form of a meaning, for Listen & Learn's "Meaning only"
    /// readback (#210): the text before the first " — " qualifier, trimmed,
    /// so "go ahead — named station only" is spoken as "go ahead" and a
    /// meaning with no qualifier is spoken as it is. Pinned by the `brief`
    /// examples in fixtures/qso-elements.json; the Kotlin twin is
    /// `MorseData.briefMeaning`.
    public static let briefSeparator = " — "
    public static func briefMeaning(_ meaning: String) -> String {
        let head = meaning.range(of: briefSeparator).map { String(meaning[..<$0.lowerBound]) } ?? meaning
        return head.trimmingCharacters(in: .whitespaces)
    }

    /// Listen & Learn's "QSO elements · Top N" pool: the first `limit` of
    /// `qsoElements` as items whose answer is the meaning, like
    /// `abbreviationItems`. A bracketed token plays the run-together prosign
    /// pattern; anything else plays as text.
    public static func qsoElementItems(_ limit: Int) -> [MorseItem] {
        qsoElements.prefix(limit).map { element -> MorseItem in
            let playable: MorseItem.Playable
            if let prosign = prosigns.first(where: { $0.name == element.token }) {
                playable = .pattern(prosign.pattern)
            } else {
                playable = .text(element.token)
            }
            return MorseItem(id: "qso-\(element.token)", playable: playable,
                             answer: element.meaning, display: element.token)
        }
    }
}
