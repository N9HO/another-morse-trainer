import Foundation

/// One thing the trainer can play and quiz on — a character, a word, an
/// abbreviation, or a prosign. The user hears `playable` in Morse and the
/// correct multiple-choice answer is `answer`.
public struct MorseItem: Identifiable, Sendable, Equatable {
    public enum Playable: Sendable, Equatable {
        case text(String)      // characters sent with normal spacing (word/abbr)
        case pattern(String)   // raw dot-dash sent run-together (prosigns)
    }

    public let id: String
    /// What gets sounded out in Morse.
    public let playable: Playable
    /// The correct multiple-choice answer (a meaning, a word, or a character).
    public let answer: String
    /// Big label shown when revealing the answer (e.g. "ES", "<AR>", "X").
    public let display: String

    public init(id: String, playable: Playable, answer: String, display: String) {
        self.id = id
        self.playable = playable
        self.answer = answer
        self.display = display
    }

    /// Concatenated dot-dash pattern, used to find sound-alike distractors.
    public var soundKey: String {
        switch playable {
        case .pattern(let p):
            return p
        case .text(let s):
            return s.compactMap { MorseCode.pattern(for: $0) }.joined()
        }
    }
}

/// Curated ham-radio reference data (high-frequency words, abbreviations,
/// Q-codes, and prosigns) used to build the quiz modes. Sourced from Morse
/// Code Ninja, ARRL, KB6NU, and the ITU prosign spec.
public enum MorseData {

    // MARK: Common words (frequency-ordered; the basis of MCN's "Top N Words")

    public static let commonWords: [String] = [
        "THE","OF","AND","TO","A","IN","FOR","IS","ON","THAT",
        "BY","THIS","WITH","YOU","IT","NOT","OR","BE","ARE","FROM",
        "AT","AS","YOUR","ALL","HAVE","NEW","MORE","WAS","WE","WILL",
        "HOME","CAN","ABOUT","IF","MY","HAS","BUT","OUR","ONE","DO",
        "TIME","THEY","UP","WHAT","WHICH","OUT","ANY","THERE","SEE","ONLY",
        "SO","HIS","WHEN","HERE","WHO","NOW","HELP","GET","FIRST","BEEN",
        "HOW","SOME","LIKE","THAN","FIND","BACK","NAME","JUST","OVER","YEAR",
        "DAY","TWO","NEXT","GO","WORK","LAST","MOST","MAKE","GOOD","WELL",
        "VERY","NEED","KNOW","WAY","PART","GREAT","REAL","MUST","MADE","LINE",
        "SEND","RIGHT","WANT","LONG","CODE","SHOW","SAME","FOUND","BOTH","CALL",
        "WORD","LOOK","COME","SOUND","THING","WRITE"
    ]

    // MARK: Ham/QSO vocabulary (heard constantly on the air)

    public static let hamWords: [String] = [
        "NAME","RIG","ANT","WX","HR","HW","QSO","QTH","RST","RPT",
        "TNX","PWR","FB","OM","YL","XYL","DX","SIG","TEMP","KEY",
        "GUD","COPY","HOPE","AGN","FREQ","BAND","DIPOLE","BEAM","WIRE","CONTEST"
    ]

    // MARK: Abbreviations → meaning ("what are they saying?")

    public static let abbreviations: [(token: String, meaning: String)] = [
        ("ABT","about"), ("AGN","again"), ("ANT","antenna"), ("BCNU","be seeing you"),
        ("BK","break"), ("B4","before"), ("CFM","confirm"), ("CL","closing down"),
        ("CPY","copy"), ("CQ","calling any station"), ("CUL","see you later"),
        ("DE","this is / from"), ("DR","dear"), ("DX","distance"), ("ES","and"),
        ("FB","fine business (great)"), ("FER","for"), ("GA","good afternoon"),
        ("GE","good evening"), ("GM","good morning"), ("GN","good night"),
        ("GND","ground"), ("GUD","good"), ("HI","laughter"), ("HR","here"),
        ("HV","have"), ("HW","how do you copy"), ("NR","number"), ("OB","old boy"),
        ("OM","old man"), ("OP","operator"), ("PSE","please"), ("PWR","power"),
        ("RPT","repeat / report"), ("RST","signal report"), ("RIG","radio"),
        ("SED","said"), ("SIG","signal"), ("SKED","schedule"), ("SN","soon"),
        ("SRI","sorry"), ("TFC","traffic"), ("TNX","thanks"), ("TU","thank you"),
        ("UR","your / you're"), ("VY","very"), ("WID","with"), ("WKD","worked"),
        ("WL","well"), ("WX","weather"), ("YL","young lady"),
        ("73","best regards"), ("88","love and kisses")
    ]

    // MARK: Q-codes → meaning

    public static let qCodes: [(token: String, meaning: String)] = [
        ("QRL","is this frequency in use"), ("QRM","you are being interfered with"),
        ("QRN","I am troubled by static"), ("QRO","increase power"),
        ("QRP","low power"), ("QRQ","send faster"), ("QRS","send slower"),
        ("QRT","stop sending"), ("QRU","I have nothing for you"),
        ("QRV","I am ready"), ("QRX","stand by"), ("QRZ","who is calling me"),
        ("QSB","your signals are fading"), ("QSL","I acknowledge receipt"),
        ("QSO","a contact"), ("QSY","change frequency"),
        ("QTH","my location is"), ("QTR","the correct time is")
    ]

    // MARK: Call signs (realistic structure for word/call-sign practice)

    public static let callSigns: [String] = [
        "W1AW", "K9LA", "N0AX", "AA3B", "K4XYZ", "W7PHX", "N5XJ", "K0XYZ",
        "VE3KP", "G3ABC", "DL1XX", "JA1ABC", "VK2DEF", "KH6OO", "WB2OSZ",
        "W5KFT", "K3LR", "N2IC", "W6OAT", "K1TTT"
    ]

    // MARK: Prosigns → (run-together pattern, meaning)

    public static let prosigns: [(name: String, pattern: String, meaning: String)] = [
        ("<AR>", ".-.-.",     "over — end of message"),
        ("<SK>", "...-.-",    "end of contact"),
        ("<BT>", "-...-",     "break / new paragraph"),
        ("<KN>", "-.--.",     "go ahead, named station"),
        ("<AS>", ".-...",     "wait / stand by"),
        ("<CT>", "-.-.-",     "attention / start"),
        ("<SN>", "...-.",     "understood"),
        ("<BK>", "-...-.-",   "break — back to you"),
        ("<SOS>", "...---...", "distress")
    ]

    // MARK: Item builders for each quiz mode

    /// Words mode: hear the word, choose the word.
    public static var wordItems: [MorseItem] {
        let words = commonWords + hamWords.filter { !commonWords.contains($0) }
        return words.map { MorseItem(id: $0, playable: .text($0), answer: $0, display: $0) }
    }

    /// Abbreviations mode: hear the abbreviation/Q-code, choose its meaning.
    public static var abbreviationItems: [MorseItem] {
        (abbreviations + qCodes).map {
            MorseItem(id: $0.token, playable: .text($0.token),
                      answer: $0.meaning, display: $0.token)
        }
    }

    /// Prosign mode: hear the run-together prosign, choose its meaning.
    public static var prosignItems: [MorseItem] {
        prosigns.map {
            MorseItem(id: $0.name, playable: .pattern($0.pattern),
                      answer: $0.meaning, display: $0.name)
        }
    }

    /// Words + call signs, where the answer is the text itself (used by the
    /// advanced "Words & Call Signs" stage of the character ladder).
    public static var wordAndCallSignItems: [MorseItem] {
        let words = commonWords + hamWords.filter { !commonWords.contains($0) }
        let all = words + callSigns
        return all.map { MorseItem(id: $0, playable: .text($0), answer: $0, display: $0) }
    }

    /// Prosigns where the answer is the prosign token itself (recognize-by-sound,
    /// used when prosigns are mixed into the advanced character stages).
    public static var prosignTokenItems: [MorseItem] {
        prosigns.map {
            MorseItem(id: $0.name, playable: .pattern($0.pattern),
                      answer: $0.name, display: $0.name)
        }
    }
}
