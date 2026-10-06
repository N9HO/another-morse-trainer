// AccountSync.swift
// The optional account's sync rules, as pure functions over values: the wire
// codec for a session record, the practice-day batch, the five progress-state
// codecs, the client merge rules, the push outbox, the retry policy and PKCE.
// No network and no storage here: the client actor is AccountClient.swift,
// and the Keychain and URLSession halves live in the app target.
//
// The contract is the accounts Worker's README (sections 5, 7 and 9).
// `fixtures/sync-wire.json` pins every rule below; the Android and desktop
// ports read the same file, each with its own code.

import CryptoKit
import Foundation

// MARK: - JSON values

/// Any JSON value. A progress-state entry's `value` has a different shape per
/// key, so the entry carries it as this and each key's codec reads it.
/// Integers and fractional numbers are kept apart so a whole number goes back
/// on the wire without a decimal point; `==` compares them by value.
public enum JSONValue: Codable, Sendable, Equatable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let i = try? c.decode(Int.self) { self = .int(i) }
        else if let d = try? c.decode(Double.self) { self = .double(d) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .int(let i): try c.encode(i)
        case .double(let d): try c.encode(d)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public static func == (lhs: JSONValue, rhs: JSONValue) -> Bool {
        switch (lhs, rhs) {
        case (.null, .null): return true
        case let (.bool(a), .bool(b)): return a == b
        case let (.int(a), .int(b)): return a == b
        case let (.double(a), .double(b)): return a == b
        case let (.int(a), .double(b)), let (.double(b), .int(a)): return Double(a) == b
        case let (.string(a), .string(b)): return a == b
        case let (.array(a), .array(b)): return a == b
        case let (.object(a), .object(b)): return a == b
        default: return false
        }
    }

    /// `value` as JSON, by way of its own `Encodable` conformance.
    public init<T: Encodable>(encoding value: T) throws {
        self = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value))
    }

    /// This value read as `T`, by way of `T`'s own `Decodable` conformance.
    public func decode<T: Decodable>(as type: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONEncoder().encode(self))
    }
}

// MARK: - Session records on the wire

/// A `SessionRecord` as `POST /v1/sync/sessions` takes it and the pull routes
/// return it (README §9). The local record keeps a `Date` and TTRs in
/// seconds; the wire has epoch milliseconds and whole-millisecond TTRs, an
/// absent value is an explicit `null`, and the id is lowercase.
public struct SyncSession: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    /// Epoch milliseconds.
    public var date: Int
    /// The canonical mode id: on iOS, the `TrainingMode` raw value as recorded.
    public var mode: String
    public var characterWpm: Int
    public var effectiveWpm: Int
    public var attempts: Int
    public var correct: Int
    public var fastestTtrMs: Int?
    public var medianTtrMs: Int?
    public var durationSeconds: Double?
    public var score: Int?
    public var characters: [CharResult]
    public var activeCharacters: [String]
    public var schemaVersion: Int
    /// The account's sequence number. Only on records the server returns;
    /// never sent.
    public var seq: Int?

    public struct CharResult: Codable, Sendable, Equatable {
        public var character: String
        public var attempts: Int
        public var correct: Int
        public var medianTtrMs: Int?

        public init(character: String, attempts: Int, correct: Int, medianTtrMs: Int?) {
            self.character = character
            self.attempts = attempts
            self.correct = correct
            self.medianTtrMs = medianTtrMs
        }

        enum CodingKeys: String, CodingKey { case character, attempts, correct, medianTtrMs }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            character = try c.decode(String.self, forKey: .character)
            attempts = try c.decode(Int.self, forKey: .attempts)
            correct = try c.decode(Int.self, forKey: .correct)
            medianTtrMs = try c.decodeIfPresent(Int.self, forKey: .medianTtrMs)
        }

        /// Synthesized encoding would leave a nil median out; the wire wants `null`.
        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(character, forKey: .character)
            try c.encode(attempts, forKey: .attempts)
            try c.encode(correct, forKey: .correct)
            if let medianTtrMs { try c.encode(medianTtrMs, forKey: .medianTtrMs) } else { try c.encodeNil(forKey: .medianTtrMs) }
        }
    }

    /// The wire form of a local record.
    public init(record r: SessionRecord) {
        id = r.id.uuidString.lowercased()
        date = AccountSync.epochMilliseconds(r.date)
        mode = r.mode
        characterWpm = r.characterWPM
        effectiveWpm = r.effectiveWPM
        attempts = r.attempts
        correct = r.correct
        fastestTtrMs = r.fastestTTR.map(AccountSync.milliseconds)
        medianTtrMs = r.medianTTR.map(AccountSync.milliseconds)
        durationSeconds = r.durationSeconds
        score = r.score
        characters = r.characters.map {
            CharResult(character: $0.character, attempts: $0.attempts, correct: $0.correct,
                       medianTtrMs: $0.medianTTR.map(AccountSync.milliseconds))
        }
        activeCharacters = r.activeCharacters
        schemaVersion = AccountSync.schemaVersion
        seq = nil
    }

    /// The local record this wire record decodes to, or nil when its id is not
    /// a UUID (no record of ours, so nothing to keep). TTRs come back in
    /// seconds at millisecond precision.
    public var record: SessionRecord? {
        guard let uuid = UUID(uuidString: id) else { return nil }
        return SessionRecord(
            id: uuid,
            date: AccountSync.date(epochMilliseconds: date),
            mode: mode,
            characterWPM: characterWpm, effectiveWPM: effectiveWpm,
            attempts: attempts, correct: correct,
            fastestTTR: fastestTtrMs.map(AccountSync.seconds),
            medianTTR: medianTtrMs.map(AccountSync.seconds),
            durationSeconds: durationSeconds,
            characters: characters.map {
                SessionRecord.CharResult(character: $0.character, attempts: $0.attempts, correct: $0.correct,
                                         medianTTR: $0.medianTtrMs.map(AccountSync.seconds))
            },
            activeCharacters: activeCharacters,
            score: score)
    }

    enum CodingKeys: String, CodingKey {
        case id, date, mode, characterWpm, effectiveWpm, attempts, correct
        case fastestTtrMs, medianTtrMs, durationSeconds, score
        case characters, activeCharacters, schemaVersion, seq
    }

    /// Lenient where the README allows: `schemaVersion` absent means 1, and
    /// the stats-read pages leave the two lists out without `detail=1`.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        date = try c.decode(Int.self, forKey: .date)
        mode = try c.decode(String.self, forKey: .mode)
        characterWpm = try c.decodeIfPresent(Int.self, forKey: .characterWpm) ?? 0
        effectiveWpm = try c.decodeIfPresent(Int.self, forKey: .effectiveWpm) ?? 0
        attempts = try c.decode(Int.self, forKey: .attempts)
        correct = try c.decode(Int.self, forKey: .correct)
        fastestTtrMs = try c.decodeIfPresent(Int.self, forKey: .fastestTtrMs)
        medianTtrMs = try c.decodeIfPresent(Int.self, forKey: .medianTtrMs)
        durationSeconds = try c.decodeIfPresent(Double.self, forKey: .durationSeconds)
        score = try c.decodeIfPresent(Int.self, forKey: .score)
        characters = try c.decodeIfPresent([CharResult].self, forKey: .characters) ?? []
        activeCharacters = try c.decodeIfPresent([String].self, forKey: .activeCharacters) ?? []
        schemaVersion = try c.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? AccountSync.schemaVersion
        seq = try c.decodeIfPresent(Int.self, forKey: .seq)
    }

    /// Every optional goes out as `null`, never left out and never -1.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        func put<T: Encodable>(_ value: T?, _ key: CodingKeys) throws {
            if let value { try c.encode(value, forKey: key) } else { try c.encodeNil(forKey: key) }
        }
        try c.encode(id, forKey: .id)
        try c.encode(date, forKey: .date)
        try c.encode(mode, forKey: .mode)
        try c.encode(characterWpm, forKey: .characterWpm)
        try c.encode(effectiveWpm, forKey: .effectiveWpm)
        try c.encode(attempts, forKey: .attempts)
        try c.encode(correct, forKey: .correct)
        try put(fastestTtrMs, .fastestTtrMs)
        try put(medianTtrMs, .medianTtrMs)
        try put(durationSeconds, .durationSeconds)
        try put(score, .score)
        try c.encode(characters, forKey: .characters)
        try c.encode(activeCharacters, forKey: .activeCharacters)
        try c.encode(schemaVersion, forKey: .schemaVersion)
        try c.encodeIfPresent(seq, forKey: .seq)
    }
}

// MARK: - Practice days

/// One ledger day for `POST /v1/sync/days`: a local `yyyy-MM-dd` key and whole
/// seconds. A day with 0 seconds is still a practice day.
public struct SyncDay: Codable, Sendable, Equatable {
    public var day: String
    public var seconds: Int
    public init(day: String, seconds: Int) {
        self.day = day
        self.seconds = seconds
    }
}

/// The `POST /v1/sync/days` body: `{ days: [{ day, seconds }] }`, oldest first.
public struct SyncDayBatch: Codable, Sendable, Equatable {
    public var days: [SyncDay]
    public init(days: [SyncDay]) { self.days = days }
    /// This device's own ledger, oldest day first.
    public init(ledger: [String: Int]) {
        days = ledger.keys.sorted().map { SyncDay(day: $0, seconds: ledger[$0] ?? 0) }
    }
}

// MARK: - Progress state

/// The five progress-state keys every app syncs (README §9).
public enum SyncStateKey: String, CaseIterable, Sendable {
    case journey, characters, firstFour, operatingProcedure, storyBookmarks
}

/// One key's value and when it was last changed, in epoch milliseconds.
public struct SyncStateEntry: Codable, Sendable, Equatable {
    public var value: JSONValue
    public var updatedAt: Int
    public init(value: JSONValue, updatedAt: Int) {
        self.value = value
        self.updatedAt = updatedAt
    }
}

// MARK: - Server replies

/// The stored stats row a push or pull reply carries, and `stats` in the
/// snapshot. Only the fields the app adopts are read; the rest are ignored.
public struct SyncServerStats: Decodable, Sendable, Equatable {
    public struct Totals: Decodable, Sendable, Equatable {
        public var sessions: Int
        public var answered: Int
        public var correct: Int
        public var practiceSeconds: Double
        public init(sessions: Int, answered: Int, correct: Int, practiceSeconds: Double) {
            self.sessions = sessions
            self.answered = answered
            self.correct = correct
            self.practiceSeconds = practiceSeconds
        }
    }
    public var totals: Totals
    public var bestTtrMs: Int?
    public var personalBests: [String: Int]

    public init(totals: Totals, bestTtrMs: Int?, personalBests: [String: Int]) {
        self.totals = totals
        self.bestTtrMs = bestTtrMs
        self.personalBests = personalBests
    }

    enum CodingKeys: String, CodingKey { case totals, bestTtrMs, personalBests }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        totals = try c.decode(Totals.self, forKey: .totals)
        bestTtrMs = try c.decodeIfPresent(Int.self, forKey: .bestTtrMs)
        personalBests = try c.decodeIfPresent([String: Int].self, forKey: .personalBests) ?? [:]
    }
}

/// `POST /v1/sync/sessions`'s reply.
public struct SyncPushReply: Decodable, Sendable, Equatable {
    public struct Rejected: Decodable, Sendable, Equatable {
        public var id: String?
        public var reason: String
        public init(id: String?, reason: String) {
            self.id = id
            self.reason = reason
        }
    }
    public var accepted: [String]
    public var skipped: [String]
    public var rejected: [Rejected]
    public var stats: SyncServerStats?

    public init(accepted: [String], skipped: [String], rejected: [Rejected], stats: SyncServerStats? = nil) {
        self.accepted = accepted
        self.skipped = skipped
        self.rejected = rejected
        self.stats = stats
    }
}

/// `GET /v1/sync/sessions`'s reply. Advance the stored cursor to `nextSince`
/// only once `sessions` are merged and saved.
public struct SyncPullReply: Decodable, Sendable, Equatable {
    public var sessions: [SyncSession]
    public var nextSince: Int
    public var hasMore: Bool
}

/// `POST /v1/sync/days`'s reply: the account's summed figure for each day sent.
public struct SyncDaysReply: Decodable, Sendable, Equatable {
    public struct Rejected: Decodable, Sendable, Equatable {
        public var day: String?
        public var reason: String
    }
    public var days: [String: Int]
    public var rejected: [Rejected]

    enum CodingKeys: String, CodingKey { case days, rejected }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        days = try c.decodeIfPresent([String: Int].self, forKey: .days) ?? [:]
        rejected = try c.decodeIfPresent([Rejected].self, forKey: .rejected) ?? []
    }
}

/// `PUT /v1/sync/state`'s reply: the winning entry for every key sent.
public struct SyncStateReply: Decodable, Sendable, Equatable {
    public struct Rejected: Decodable, Sendable, Equatable {
        public var key: String?
        public var reason: String
    }
    public var entries: [String: SyncStateEntry]
    public var rejected: [Rejected]

    enum CodingKeys: String, CodingKey { case entries, rejected }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        entries = try c.decodeIfPresent([String: SyncStateEntry].self, forKey: .entries) ?? [:]
        rejected = try c.decodeIfPresent([Rejected].self, forKey: .rejected) ?? []
    }
}

/// `GET /v1/sync/snapshot`: everything a fresh install needs. `seq` is the
/// pull cursor to store.
public struct SyncSnapshot: Decodable, Sendable, Equatable {
    public var stats: SyncServerStats
    public var sessions: [SyncSession]
    public var days: [String: Int]
    public var state: [String: SyncStateEntry]
    public var seq: Int
}

/// The server's lifetime counters in the units `SessionHistory` keeps them
/// (README §7, merge rule 3). Adopted whole by
/// `SessionHistory.adoptServerTotals(_:)`.
public struct SyncLifetimeTotals: Sendable, Equatable {
    public var totalSessions: Int
    public var totalAnswered: Int
    public var totalCorrect: Int
    public var totalPracticeSeconds: TimeInterval
    public var bestTTR: TimeInterval?
    public var bestScores: [String: Int]

    public init(stats: SyncServerStats) {
        totalSessions = stats.totals.sessions
        totalAnswered = stats.totals.answered
        totalCorrect = stats.totals.correct
        totalPracticeSeconds = stats.totals.practiceSeconds
        bestTTR = stats.bestTtrMs.map(AccountSync.seconds)
        bestScores = stats.personalBests
    }
}

// MARK: - Outbox

/// What is waiting to be pushed, oldest first, at most `max` entries. An entry
/// leaves only when a push reply names its id: accepted, skipped (already on
/// the account) or rejected (it stays in local history, but resending it can
/// never succeed). Persisted by the app between launches.
public struct SyncOutbox<Entry: Codable & Sendable & Equatable & Identifiable>: Codable, Sendable, Equatable
where Entry.ID == String {
    public private(set) var entries: [Entry]
    public let max: Int

    /// The apps keep up to a thousand unsent records.
    public static var appMax: Int { 1000 }
    /// The server takes at most 200 sessions per push.
    public static var batchSize: Int { 200 }

    public init(entries: [Entry] = [], max: Int = Self.appMax) {
        self.max = max
        self.entries = Array(entries.suffix(Swift.max(0, max)))
    }

    public var isEmpty: Bool { entries.isEmpty }

    /// Queue `entry` at the end, dropping the oldest past `max`. An id already
    /// waiting is not queued twice.
    public mutating func enqueue(_ entry: Entry) {
        let id = entry.id.lowercased()
        guard !entries.contains(where: { $0.id.lowercased() == id }) else { return }
        entries.append(entry)
        if entries.count > max { entries.removeFirst(entries.count - max) }
    }

    /// The oldest `size` entries: the next push.
    public func nextBatch(size: Int = Self.batchSize) -> [Entry] {
        Array(entries.prefix(size))
    }

    /// Remove every entry the reply names; the rest stay, in order.
    public mutating func apply(_ reply: SyncPushReply) {
        var done = Set(reply.accepted.map { $0.lowercased() })
        done.formUnion(reply.skipped.map { $0.lowercased() })
        done.formUnion(reply.rejected.compactMap { $0.id?.lowercased() })
        entries.removeAll { done.contains($0.id.lowercased()) }
    }
}

// MARK: - Rules

/// What to do with an HTTP outcome (fixture `merge.retry`).
public enum SyncRetryAction: String, Sendable {
    /// It landed.
    case done
    /// Refresh the token once and retry once; sign out if the refresh fails.
    case refresh
    /// Try again later, after `AccountSync.backoffSeconds`.
    case backoff
    /// Retrying cannot help.
    case drop
}

public enum AccountSync {
    /// The `client` id this app signs in as.
    public static let clientId = "amt-ios"
    public static let schemaVersion = 1

    // MARK: Units

    /// A date as integer epoch milliseconds.
    public static func epochMilliseconds(_ date: Date) -> Int {
        Int((date.timeIntervalSince1970 * 1000).rounded())
    }

    public static func date(epochMilliseconds ms: Int) -> Date {
        Date(timeIntervalSince1970: Double(ms) / 1000)
    }

    /// Seconds to whole milliseconds, `round(seconds * 1000)`.
    public static func milliseconds(_ seconds: TimeInterval) -> Int {
        Int((seconds * 1000).rounded())
    }

    public static func seconds(_ milliseconds: Int) -> TimeInterval {
        Double(milliseconds) / 1000
    }

    // MARK: State codecs

    /// `journey`: `{ unlockedThrough, currentLevel, completed }`, completed ascending.
    public static func journeyValue(_ p: JourneyProgress) -> JSONValue {
        .object([
            "unlockedThrough": .int(p.unlockedThrough),
            "currentLevel": .int(p.currentLevel),
            "completed": .array(p.completed.sorted().map(JSONValue.int)),
        ])
    }

    public static func journeyProgress(from value: JSONValue) -> JourneyProgress? {
        struct Wire: Decodable { var unlockedThrough: Int; var currentLevel: Int; var completed: [Int] }
        guard let w = try? value.decode(as: Wire.self) else { return nil }
        return JourneyProgress(unlockedThrough: w.unlockedThrough, currentLevel: w.currentLevel,
                               completed: Set(w.completed))
    }

    /// `characters`: the ladder's position only — active set (in ladder
    /// order), exposed set, stage and pinned stage. Per-character stats and
    /// confusions stay on the device.
    public static func charactersValue(_ s: ProgressiveCharacters.Snapshot) -> JSONValue {
        let active = s.engine.activeCharacters
        // The exposed set has no order of its own: ladder order, then any
        // stragglers sorted, so the same set always reads the same.
        let inLadder = active.filter { s.engine.exposedCharacters.contains($0) }
        let rest = s.engine.exposedCharacters.subtracting(active).sorted()
        return .object([
            "activeCharacters": .array(active.map { .string(String($0)) }),
            "exposedCharacters": .array((inLadder + rest).map { .string(String($0)) }),
            "stage": .string(s.stage.rawValue),
            "pinnedStage": s.pinnedStage.map { JSONValue.string($0.rawValue) } ?? .null,
        ])
    }

    /// `local` with a received `characters` value applied: the position comes
    /// from `value`, the per-character stats and confusions stay local. Nil
    /// when the value is not that shape (an unknown stage included), so the
    /// caller keeps what it has.
    public static func applyingCharacters(_ value: JSONValue,
                                          to local: ProgressiveCharacters.Snapshot) -> ProgressiveCharacters.Snapshot? {
        struct Wire: Decodable {
            var activeCharacters: [String]
            var exposedCharacters: [String]
            var stage: String
            var pinnedStage: String?
        }
        guard let w = try? value.decode(as: Wire.self),
              let stage = ProgressiveCharacters.Stage(rawValue: w.stage) else { return nil }
        var pinned: ProgressiveCharacters.Stage?
        if let p = w.pinnedStage {
            guard let s = ProgressiveCharacters.Stage(rawValue: p) else { return nil }
            pinned = s
        }
        let engine = TrainerEngine.Snapshot(
            activeCharacters: w.activeCharacters.compactMap(\.first),
            stats: local.engine.stats,
            confusions: local.engine.confusions,
            exposedCharacters: Set(w.exposedCharacters.compactMap(\.first)))
        return ProgressiveCharacters.Snapshot(engine: engine, stage: stage, pinnedStage: pinned)
    }

    /// `firstFour`: `FirstFourProgress`'s own encoding is the wire shape.
    public static func firstFourValue(_ p: FirstFourProgress) -> JSONValue {
        (try? JSONValue(encoding: p)) ?? .object([:])
    }

    public static func firstFourProgress(from value: JSONValue) -> FirstFourProgress? {
        try? value.decode(as: FirstFourProgress.self)
    }

    /// `operatingProcedure`: `OperatingProcedureProgress`'s own encoding is the
    /// wire shape.
    public static func operatingProcedureValue(_ p: OperatingProcedureProgress) -> JSONValue {
        (try? JSONValue(encoding: p)) ?? .object([:])
    }

    public static func operatingProcedureProgress(from value: JSONValue) -> OperatingProcedureProgress? {
        try? value.decode(as: OperatingProcedureProgress.self)
    }

    /// `storyBookmarks`: the app's `[shelfOrSerialId: partIndex]` map as is.
    public static func storyBookmarksValue(_ bookmarks: [String: Int]) -> JSONValue {
        .object(bookmarks.mapValues(JSONValue.int))
    }

    public static func storyBookmarks(from value: JSONValue) -> [String: Int]? {
        try? value.decode(as: [String: Int].self)
    }

    // MARK: Merge rules (README §7, "Client merge rules")

    /// Pulled or snapshot records join the local list when their id is not
    /// already there; a local record is never replaced. Newest first (equal
    /// dates by lowercase id), capped at `limit`.
    public static func mergeSessions(local: [SessionRecord], pulled: [SessionRecord],
                                     limit: Int) -> [SessionRecord] {
        var merged = local
        var ids = Set(local.map(\.id))   // UUID equality ignores the string's case
        for record in pulled where !ids.contains(record.id) {
            ids.insert(record.id)
            merged.append(record)
        }
        merged.sort {
            $0.date != $1.date ? $0.date > $1.date
                : $0.id.uuidString.lowercased() < $1.id.uuidString.lowercased()
        }
        return Array(merged.prefix(Swift.max(0, limit)))
    }

    /// The server's summed per-day figures replace the local ones day by day;
    /// days the reply does not mention are kept.
    public static func mergeLedger(local: [String: Int], server: [String: Int]) -> [String: Int] {
        local.merging(server) { _, theirs in theirs }
    }

    /// Per key, a reply entry wins only when strictly newer; keys the reply
    /// leaves out keep the local entry.
    public static func mergeState(local: [String: SyncStateEntry],
                                  reply: [String: SyncStateEntry]) -> [String: SyncStateEntry] {
        var merged = local
        for (key, theirs) in reply where theirs.updatedAt > (local[key]?.updatedAt ?? Int.min) {
            merged[key] = theirs
        }
        return merged
    }

    // MARK: Retry

    /// Longest wait between retries: ten minutes.
    public static let backoffCapSeconds = 600

    /// Seconds before retry `n` (1-based): 2^n, capped at ten minutes.
    public static func backoffSeconds(_ n: Int) -> Int {
        let n = Swift.max(1, n)
        return n >= 10 ? backoffCapSeconds : Swift.min(backoffCapSeconds, 1 << n)
    }

    /// The action for an HTTP status, or for no reply at all (`nil`). README
    /// §10: never retry a 4xx other than 401 (refresh first) and 429.
    public static func retryAction(status: Int?) -> SyncRetryAction {
        guard let status else { return .backoff }
        switch status {
        case 200..<300: return .done
        case 401: return .refresh
        case 429, 500...: return .backoff
        default: return .drop
        }
    }

    // MARK: PKCE (RFC 7636, S256)

    /// A fresh verifier: 32 random bytes, base64url without padding (43 chars).
    public static func newPKCEVerifier() -> String {
        base64URL(SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })
    }

    /// base64url(SHA-256(ASCII(verifier))), no padding.
    public static func pkceChallenge(verifier: String) -> String {
        base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    public static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
