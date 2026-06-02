import Foundation

/// Lookup tables for turning spoken English back into Morse answer tokens, used
/// by the "voice response" training option. Kept as plain data so they can be
/// reused both for matching and for biasing the speech recognizer.
public enum VoicePhonetics {

    /// NATO/ICAO phonetic alphabet — the recommended way to say a single letter
    /// (far more reliable for recognition than the bare letter name).
    public static let natoWords: [Character: String] = [
        "A": "alpha", "B": "bravo", "C": "charlie", "D": "delta", "E": "echo",
        "F": "foxtrot", "G": "golf", "H": "hotel", "I": "india", "J": "juliet",
        "K": "kilo", "L": "lima", "M": "mike", "N": "november", "O": "oscar",
        "P": "papa", "Q": "quebec", "R": "romeo", "S": "sierra", "T": "tango",
        "U": "uniform", "V": "victor", "W": "whiskey", "X": "xray", "Y": "yankee",
        "Z": "zulu"
    ]

    /// How the recognizer commonly transcribes a spoken *letter name* (and a few
    /// near-homophones), mapped back to the letter. Keys must be unique.
    public static let letterNameWords: [String: Character] = [
        "ay": "A", "eh": "A", "aye": "A",
        "bee": "B", "be": "B", "bea": "B",
        "see": "C", "sea": "C", "cee": "C",
        "dee": "D",
        "ee": "E",
        "ef": "F", "eff": "F",
        "gee": "G", "jee": "G",
        "aitch": "H", "haitch": "H",
        "eye": "I",
        "jay": "J",
        "kay": "K", "kaye": "K",
        "el": "L", "ell": "L",
        "em": "M",
        "en": "N",
        "owe": "O",
        "pee": "P", "pea": "P",
        "cue": "Q", "queue": "Q", "kew": "Q",
        "ar": "R", "are": "R", "arr": "R",
        "es": "S", "ess": "S",
        "tee": "T", "tea": "T",
        "you": "U", "yoo": "U", "ewe": "U",
        "vee": "V",
        "double you": "W", "double u": "W", "dub": "W",
        "ex": "X", "eks": "X",
        "why": "Y", "wye": "Y",
        "zee": "Z", "zed": "Z"
    ]

    /// Digits as words, including the standard ham/aviation pronunciations.
    public static let digitWords: [String: Character] = [
        "zero": "0", "nought": "0",
        "one": "1", "won": "1",
        "two": "2", "to": "2", "too": "2",
        "three": "3", "tree": "3",
        "four": "4", "for": "4", "fower": "4",
        "five": "5", "fife": "5",
        "six": "6",
        "seven": "7",
        "eight": "8", "ate": "8",
        "nine": "9", "niner": "9"
    ]

    /// Punctuation/symbol tokens spoken as words.
    public static let symbolWords: [String: String] = [
        "comma": ",", "period": ".", "full stop": ".", "stop": ".",
        "slash": "/", "stroke": "/",
        "question mark": "?", "question": "?",
        "equals": "=", "equal": "=", "plus": "+"
    ]

    /// One canonical letter-name pronunciation per letter (for spelling words).
    public static let primaryLetterName: [Character: String] = [
        "A": "ay", "B": "bee", "C": "see", "D": "dee", "E": "ee", "F": "ef",
        "G": "gee", "H": "aitch", "I": "eye", "J": "jay", "K": "kay", "L": "el",
        "M": "em", "N": "en", "O": "oh", "P": "pee", "Q": "cue", "R": "ar",
        "S": "es", "T": "tee", "U": "you", "V": "vee", "W": "double you",
        "X": "ex", "Y": "why", "Z": "zee"
    ]

    /// One canonical word per digit.
    public static let primaryDigitWord: [Character: String] = [
        "0": "zero", "1": "one", "2": "two", "3": "three", "4": "four",
        "5": "five", "6": "six", "7": "seven", "8": "eight", "9": "nine"
    ]
}

/// The result of trying to understand what the learner said.
public struct VoiceInterpretation: Equatable {
    /// The best-guess answer token (one of the supplied candidates), or nil.
    public let token: String?
    /// 0…1, where 1 means an exact match.
    public let confidence: Double
    /// True only when we're confident enough to grade without confirming.
    public let isConfident: Bool

    public init(token: String?, confidence: Double, isConfident: Bool = false) {
        self.token = token
        self.confidence = confidence
        self.isConfident = isConfident && token != nil
    }
}

/// Turns speech-recognizer transcripts into Morse answer tokens. Pure and
/// deterministic so it can be unit-tested without the Speech framework: the iOS
/// layer passes in the recognizer's best guess plus its alternatives, and this
/// decides what the learner most likely meant. An optional ``VoiceProfile``
/// personalizes the result from the user's past confirmations.
public struct VoiceMatcher {

    public var profile: VoiceProfile

    public init(profile: VoiceProfile = VoiceProfile()) {
        self.profile = profile
    }

    // MARK: - Public API

    /// Best interpretation of `transcripts` restricted to the drill's
    /// `candidates`. Used to decide whether to grade outright or to ask
    /// "Did you say X?".
    public func interpret(_ transcripts: [String],
                          candidates: [String],
                          confidenceThreshold: Double = 0.6) -> VoiceInterpretation {
        let cands = VoiceMatcher.orderedUnique(candidates)
        guard !cands.isEmpty else { return VoiceInterpretation(token: nil, confidence: 0) }
        let norms = transcripts.map(VoiceMatcher.normalize).filter { !$0.isEmpty }
        guard !norms.isEmpty else { return VoiceInterpretation(token: nil, confidence: 0) }

        // 1) Personalized override: if this user reliably means a candidate by
        //    this exact phrasing, trust it.
        if let learned = profile.suggestion(for: norms[0]), cands.contains(learned) {
            return VoiceInterpretation(token: learned, confidence: 0.97, isConfident: true)
        }

        // 2) Score every candidate by closeness, best (lowest distance) first.
        let scored = cands
            .map { (token: $0, score: bestNormalizedDistance(token: $0, against: norms)) }
            .sorted { $0.score != $1.score ? $0.score < $1.score : $0.token < $1.token }
        guard let best = scored.first else { return VoiceInterpretation(token: nil, confidence: 0) }

        var confidence = max(0, 1 - best.score)
        // A near-tie with the runner-up means we're not sure which they said.
        if scored.count > 1, scored[1].score - best.score < 0.15 {
            confidence *= 0.6
        }
        return VoiceInterpretation(token: best.token,
                                   confidence: confidence,
                                   isConfident: confidence >= confidenceThreshold)
    }

    /// The `limit` answers from `pool` that sound closest to what was heard,
    /// best first. Used to populate the "pick the closest" buttons after the
    /// learner rejects the "Did you say X?" guess.
    public func rankedCandidates(_ transcripts: [String],
                                 pool: [String],
                                 limit: Int = 4) -> [String] {
        let candidates = VoiceMatcher.orderedUnique(pool)
        let norms = transcripts.map(VoiceMatcher.normalize).filter { !$0.isEmpty }
        guard !norms.isEmpty else { return Array(candidates.prefix(limit)) }
        let scored = candidates
            .map { (token: $0, score: bestNormalizedDistance(token: $0, against: norms)) }
            .sorted { $0.score != $1.score ? $0.score < $1.score : $0.token < $1.token }
        return scored.prefix(limit).map { $0.token }
    }

    /// Phrases to bias the recognizer toward (every spoken form of every
    /// candidate). Used for `contextualStrings` and the custom language model.
    public func contextualStrings(for candidates: [String]) -> [String] {
        var out: [String] = []
        for c in candidates { out.append(contentsOf: VoiceMatcher.spokenForms(for: c)) }
        return VoiceMatcher.orderedUnique(out)
    }

    // MARK: - Scoring

    private func bestNormalizedDistance(token: String, against norms: [String]) -> Double {
        let forms = VoiceMatcher.spokenForms(for: token)
        guard !forms.isEmpty else { return 1 }
        var best = Double.greatestFiniteMagnitude
        for n in norms {
            for f in forms { best = min(best, VoiceMatcher.normalizedDistance(n, f)) }
        }
        return best
    }

    // MARK: - Spoken forms

    /// Every plausible way to say `token`: NATO words, letter names, digit
    /// words, the word itself, and (for multi-character tokens) spelled-out
    /// variants.
    public static func spokenForms(for token: String) -> [String] {
        let upper = token.uppercased()
        let chars = Array(upper)
        if chars.count == 1 { return singleCharForms(chars[0]) }

        var forms: [String] = []
        forms.append(normalize(upper))                              // said as a word
        forms.append(chars.map(spelledNato).joined(separator: " ")) // spelled (NATO)
        forms.append(chars.map(spelledName).joined(separator: " ")) // spelled (names)
        return orderedUnique(forms).filter { !$0.isEmpty }
    }

    private static func singleCharForms(_ ch: Character) -> [String] {
        let c = Character(ch.uppercased())
        var forms: [String] = []
        if let nato = VoicePhonetics.natoWords[c] { forms.append(nato) }
        for (word, letter) in VoicePhonetics.letterNameWords where letter == c { forms.append(word) }
        for (word, digit) in VoicePhonetics.digitWords where digit == c { forms.append(word) }
        for (word, sym) in VoicePhonetics.symbolWords where sym == String(c) { forms.append(word) }
        if c.isLetter || c.isNumber { forms.append(String(c).lowercased()) }
        return orderedUnique(forms).filter { !$0.isEmpty }
    }

    private static func spelledNato(_ ch: Character) -> String {
        let c = Character(ch.uppercased())
        if let w = VoicePhonetics.natoWords[c] { return w }
        if let d = VoicePhonetics.primaryDigitWord[c] { return d }
        return String(c).lowercased()
    }

    private static func spelledName(_ ch: Character) -> String {
        let c = Character(ch.uppercased())
        if let w = VoicePhonetics.primaryLetterName[c] { return w }
        if let d = VoicePhonetics.primaryDigitWord[c] { return d }
        return String(c).lowercased()
    }

    // MARK: - Helpers

    /// Lowercase, strip punctuation to spaces, and collapse whitespace.
    public static func normalize(_ s: String) -> String {
        var out = ""
        for ch in s.lowercased() {
            out.append((ch.isLetter || ch.isNumber) ? ch : " ")
        }
        return out.split(separator: " ").joined(separator: " ")
    }

    /// Levenshtein distance normalized to 0…1 by the longer string's length.
    public static func normalizedDistance(_ a: String, _ b: String) -> Double {
        if a.isEmpty && b.isEmpty { return 0 }
        let d = MorseDistance.distance(a, b, substitutionCost: 1, indelCost: 1)
        let m = Double(max(a.count, b.count))
        return m == 0 ? 0 : d / m
    }

    static func orderedUnique(_ items: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for i in items where seen.insert(i).inserted { out.append(i) }
        return out
    }
}
