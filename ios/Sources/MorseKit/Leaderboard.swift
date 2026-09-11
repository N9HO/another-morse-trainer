// Leaderboard.swift
// The shared leaderboard's wire types and the pure rules the app applies
// before talking to it (docs/high-scores-design.md, step 2). No networking
// here: the client actor lives in the app target. Everything in this file is
// data the Android port mirrors field for field, so the two apps grade and
// name things the same way the server does.
//
// The contract is the server repository's README (API, payload shapes) and
// its `src/grade.ts`, `src/names.ts` and `src/modes.ts`. Where a rule below
// copies one of those, the comment says which.

import Foundation

// MARK: - Modes

/// The nine ranked modes, by the id the server uses. Six of them ramp their
/// speed within a run, so their transcript items carry their own `wpm`; the
/// other three run at one fixed speed known from the run token.
public enum LeaderboardMode: String, CaseIterable, Codable, Sendable, Identifiable {
    case rapidFire, contest, pileup
    case invaders, galaga, defender, dungeon, frogger, asteroids

    public var id: String { rawValue }

    /// Speed changes item to item (`src/modes.ts` RAMPING): items must carry
    /// `wpm`. Fixed-speed modes must not.
    public var ramps: Bool {
        switch self {
        case .rapidFire, .contest, .pileup: return false
        case .invaders, .galaga, .defender, .dungeon, .frogger, .asteroids: return true
        }
    }

    /// The app's `TrainingMode` raw value for this board. Every id matches
    /// except the pileup, which the app calls `qso`.
    public var trainingModeRawValue: String {
        self == .pileup ? "qso" : rawValue
    }

    /// The board a training mode ranks on, or nil for an unranked mode.
    public init?(trainingModeRawValue raw: String) {
        if raw == "qso" { self = .pileup; return }
        guard let m = LeaderboardMode(rawValue: raw), m != .pileup else { return nil }
        self = m
    }

    public var title: String {
        switch self {
        case .rapidFire:  return "Rapid Fire"
        case .contest:    return "Contest"
        case .pileup:     return "Pileup Runner"
        case .invaders:   return "Morse Invaders"
        case .galaga:     return "CW Galaga"
        case .defender:   return "Morse Defender"
        case .dungeon:    return "CW Dungeon"
        case .frogger:    return "CW Frogger"
        case .asteroids:  return "CW Asteroids"
        }
    }
}

// MARK: - Transcript

/// One graded item of a run, as the server wants it (`src/grade.ts`
/// TranscriptItem). The server decides what was correct — `answered` equal
/// to `sent` after trimming, uppercasing and collapsing spaces — and ranks on
/// the speed summed over the correct items.
public struct LeaderboardTranscriptItem: Codable, Sendable, Equatable {
    /// What the player had to produce, as text (prosigns in <brackets>).
    public var sent: String
    /// What they produced; "" for a miss or a timeout.
    public var answered: String
    /// Time to respond from the end of the item's audio, 0 when unknown.
    public var reactionMs: Int
    /// Character speed this item was sent at. Required in the ramping games,
    /// forbidden in the fixed-speed modes (`src/grade.ts`).
    public var wpm: Int?

    /// The server's plausibility bounds (`src/grade.ts` LIMITS). An item
    /// outside them makes the whole submission a 422, so the builders below
    /// clamp to them rather than let one odd value sink a run.
    public static let wpmRange: ClosedRange<Int> = 5...60
    public static let reactionRange: ClosedRange<Int> = 0...60_000
    public static let maxSentLength = 64
    public static let maxItems = 2000

    public init(sent: String, answered: String, reactionMs: Int, wpm: Int? = nil) {
        self.sent = sent
        self.answered = answered
        self.reactionMs = reactionMs
        self.wpm = wpm
    }

    /// An item for a fixed-speed mode (Rapid Fire, Contest, Pileup Runner).
    /// `reaction` is the time to respond in seconds, the app's TTR.
    public static func fixed(sent: String, answered: String, reaction: TimeInterval) -> LeaderboardTranscriptItem {
        LeaderboardTranscriptItem(sent: Self.text(sent), answered: Self.text(answered),
                                  reactionMs: Self.reactionMs(reaction), wpm: nil)
    }

    /// An item for a ramping game, sent at `wpm` (the engine's `currentWpm`
    /// at the moment of the item, rounded).
    public static func ramping(sent: String, answered: String, reaction: TimeInterval, wpm: Double) -> LeaderboardTranscriptItem {
        LeaderboardTranscriptItem(sent: Self.text(sent), answered: Self.text(answered),
                                  reactionMs: Self.reactionMs(reaction),
                                  wpm: min(max(Int(wpm.rounded()), wpmRange.lowerBound), wpmRange.upperBound))
    }

    /// Seconds to whole milliseconds inside the server's range.
    public static func reactionMs(_ seconds: TimeInterval) -> Int {
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return min(Int((seconds * 1000).rounded()), reactionRange.upperBound)
    }

    /// The characters the server's Morse table knows (`src/grade.ts`
    /// SENT_CHARS): letters, digits, space, punctuation, and the angle
    /// brackets that mark a prosign. A `sent` outside this set is a 422 for
    /// the whole submission.
    public static let allowedSentCharacters: Set<Character> =
        Set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,?/=+-@'!()&:;\"$_<>")

    /// Whether the server would accept this item at all: a non-empty target
    /// within the length cap made of known characters, and a reaction time
    /// and speed in range. `sent` is checked as the server normalises it.
    public var isPlausible: Bool {
        let s = Self.text(sent)
        guard !s.isEmpty, s.count <= Self.maxSentLength,
              s.allSatisfy({ Self.allowedSentCharacters.contains($0) }),
              Self.reactionRange.contains(reactionMs) else { return false }
        if let wpm { return Self.wpmRange.contains(wpm) }
        return true
    }

    /// Trimmed and uppercased, the way the server normalises before grading,
    /// so a transcript reads the same on both apps and in the admin view.
    static func text(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    // Explicit coding so `wpm` is absent, not `null`, when there is none: the
    // server's check is `it.wpm !== undefined`, and a fixed-speed run with a
    // `null` would read as "per-item speed on a fixed-speed mode".
    enum CodingKeys: String, CodingKey { case sent, answered, reactionMs, wpm }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(sent, forKey: .sent)
        try c.encode(answered, forKey: .answered)
        try c.encode(reactionMs, forKey: .reactionMs)
        try c.encodeIfPresent(wpm, forKey: .wpm)
    }
}

// MARK: - Display names

/// The server's display-name rule (`src/names.ts`), applied locally so the
/// Settings field can say what is wrong before a submission is refused.
public enum LeaderboardDisplayName {
    public static let lengthRange: ClosedRange<Int> = 2...12

    // The same short deny list as the server; the admin delete is the real
    // moderation tool (design §4).
    static let deny = ["FUCK", "SHIT", "CUNT", "NIGG", "FAG", "KIKE", "SPIC", "RAPE", "NAZI", "HITLER"]

    /// Trim, uppercase and collapse runs of whitespace — the server's
    /// normalisation, and the form the field stores.
    public static func normalize(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }

    /// Why `raw` would be refused, or nil when it is acceptable. The wording
    /// matches the server's so the two never disagree in front of the user.
    public static func problem(with raw: String) -> String? {
        let name = normalize(raw)
        guard lengthRange.contains(name.count) else {
            return "Display name must be 2 to 12 characters"
        }
        guard let first = name.first, isAlphanumeric(first),
              name.allSatisfy({ isAlphanumeric($0) || $0 == " " || $0 == "/" || $0 == "-" }) else {
            return "Display name may use letters, digits, space, / and -"
        }
        let squashed = name.filter { $0.isLetter }
        if deny.contains(where: { squashed.contains($0) }) {
            return "Display name not allowed"
        }
        return nil
    }

    public static func isValid(_ raw: String) -> Bool { problem(with: raw) == nil }

    /// ASCII letters and digits only — the server's `[A-Z0-9]`, not Unicode's
    /// idea of a letter.
    static func isAlphanumeric(_ ch: Character) -> Bool {
        guard let a = ch.asciiValue else { return false }
        return (a >= 0x41 && a <= 0x5A) || (a >= 0x30 && a <= 0x39)
    }
}

// MARK: - Run speeds

/// The two whole-WPM numbers a run token is issued for. The server needs
/// `effective <= character`, both in the range it accepts; the metric for a
/// fixed-speed mode is `correct × effective`.
public struct LeaderboardRunSpeeds: Sendable, Equatable {
    public var characterWpm: Int
    public var effectiveWpm: Int

    public init(characterWpm: Double, effectiveWpm: Double) {
        let r = LeaderboardTranscriptItem.wpmRange
        let c = min(max(Int(characterWpm.rounded()), r.lowerBound), r.upperBound)
        let e = min(max(Int(effectiveWpm.rounded()), r.lowerBound), r.upperBound)
        self.characterWpm = c
        self.effectiveWpm = min(e, c)
    }

    /// A pileup has no single speed: every caller draws its own from the
    /// configured band. The run is credited at the band's floor — the slowest
    /// caller the operator agreed to accept, so raising the floor is the way
    /// to score more — and the server's audio bound is taken at its ceiling,
    /// the fastest a caller could have sent, which keeps that bound a true
    /// lower bound on the real playback.
    public static func pileup(minWpm: Double, maxWpm: Double) -> LeaderboardRunSpeeds {
        LeaderboardRunSpeeds(characterWpm: max(minWpm, maxWpm), effectiveWpm: min(minWpm, maxWpm))
    }
}

// MARK: - Wire shapes

/// `{ platform, payload }` on every write. `payload` is the App Attest shape
/// from the server README: `{ keyId, attestation?, assertion, clientData }`,
/// all base64 (standard alphabet, padded).
public struct LeaderboardAttestation: Codable, Sendable, Equatable {
    public struct Payload: Codable, Sendable, Equatable {
        public var keyId: String
        public var attestation: String?
        public var assertion: String
        public var clientData: String

        public init(keyId: String, attestation: String?, assertion: String, clientData: String) {
            self.keyId = keyId
            self.attestation = attestation
            self.assertion = assertion
            self.clientData = clientData
        }

        enum CodingKeys: String, CodingKey { case keyId, attestation, assertion, clientData }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(keyId, forKey: .keyId)
            try c.encodeIfPresent(attestation, forKey: .attestation)
            try c.encode(assertion, forKey: .assertion)
            try c.encode(clientData, forKey: .clientData)
        }
    }

    public var platform: String
    public var payload: Payload

    public init(payload: Payload) {
        self.platform = "ios"
        self.payload = payload
    }
}

public struct LeaderboardChallengeResponse: Codable, Sendable {
    public var challenge: String
    public var expiresAt: Double
}

public struct LeaderboardStartRequest: Codable, Sendable {
    public var platform: String = "ios"
    public var mode: LeaderboardMode
    public var characterWpm: Int
    public var effectiveWpm: Int
    public var challenge: String
    public var attestation: LeaderboardAttestation

    public init(mode: LeaderboardMode, speeds: LeaderboardRunSpeeds, challenge: String, attestation: LeaderboardAttestation) {
        self.mode = mode
        self.characterWpm = speeds.characterWpm
        self.effectiveWpm = speeds.effectiveWpm
        self.challenge = challenge
        self.attestation = attestation
    }
}

public struct LeaderboardStartResponse: Codable, Sendable {
    public var runToken: String
    public var expiresAt: Double
}

public struct LeaderboardSubmitRequest: Codable, Sendable {
    public var runToken: String
    public var displayName: String
    public var transcript: [LeaderboardTranscriptItem]
    public var attestation: LeaderboardAttestation

    public init(runToken: String, displayName: String, transcript: [LeaderboardTranscriptItem], attestation: LeaderboardAttestation) {
        self.runToken = runToken
        self.displayName = displayName
        self.transcript = transcript
        self.attestation = attestation
    }
}

/// `200` from `/v1/run/submit`. `metric` is a REAL on the server; whole in
/// practice, since it sums whole WPM values.
public struct LeaderboardSubmitResponse: Codable, Sendable, Equatable {
    public var accepted: Bool
    public var metric: Double
    public var correct: Int
    public var total: Int
    public var rank: Int
    public var personalBest: Bool
}

public struct LeaderboardDeleteRequest: Codable, Sendable {
    public var challenge: String
    public var attestation: LeaderboardAttestation

    public init(challenge: String, attestation: LeaderboardAttestation) {
        self.challenge = challenge
        self.attestation = attestation
    }
}

/// Every error body: `{ accepted: false, reason }`.
public struct LeaderboardErrorResponse: Codable, Sendable {
    public var accepted: Bool
    public var reason: String
}

/// One row of `GET /v1/board/{mode}`.
public struct LeaderboardBoardRow: Codable, Sendable, Equatable, Identifiable {
    public var rank: Int
    public var displayName: String
    public var metric: Double
    public var platform: String
    /// `yyyy-mm-dd`, the day the score was submitted (UTC).
    public var date: String

    public var id: Int { rank }

    /// The metric as the board shows it: a whole number, since it is a sum of
    /// whole WPM values.
    public var metricLabel: String { String(Int(metric.rounded())) }
}

public struct LeaderboardBoardResponse: Codable, Sendable {
    public var mode: String
    public var rows: [LeaderboardBoardRow]
}

/// Which servers answer "unknown key": the client re-attests once when it
/// sees this in a 401 reason (the server forgot the key, or the app was
/// reinstalled with its UserDefaults restored from a backup on a different
/// device — App Attest keys do not travel).
public enum LeaderboardServerHints {
    public static func isUnknownKey(_ reason: String) -> Bool {
        reason.lowercased().contains("unknown key")
    }
}
