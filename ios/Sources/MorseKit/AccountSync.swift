// AccountSync.swift
// The account service's wire types and the pure rules behind signing in and
// syncing sessions to it. No networking here: the client actor lives in the
// app target (`AccountClient.swift`). Everything in this file is data the
// Android and desktop ports can mirror field for field.
//
// The service is the accounts Worker (`amt-accounts.n9ho-amt.workers.dev`);
// its README is the contract (repository another-morse-trainer-accounts).
// Sign-in is an emailed link with PKCE: the app keeps a random verifier,
// sends its SHA-256 as the challenge, and proves the tokens are its own by
// presenting the verifier when it polls. There is no password, client secret
// or API key. Sessions go up through `POST /v1/sync/sessions` as the
// service's SessionRecord (README §9), practice days through
// `POST /v1/sync/days` (§7); both are idempotent, so resending is safe.

import CryptoKit
import Foundation

// MARK: - PKCE

/// RFC 7636, as the service applies it: a 32-byte random verifier and its
/// SHA-256 challenge, both base64url without padding (43 characters each).
public enum AccountPKCE {
    /// Base64url without padding — the service's encoding for both halves.
    public static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// A verifier from 32 random bytes. The caller draws the bytes
    /// (`SecRandomCopyBytes` in the app) so this stays deterministic to check.
    public static func verifier(from bytes: [UInt8]) -> String {
        base64url(Data(bytes))
    }

    /// `base64url(SHA-256(verifier))` — the only thing sent when sign-in starts.
    public static func challenge(for verifier: String) -> String {
        base64url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }
}

// MARK: - Wire types

/// `POST /v1/auth/verify/start`. `scopes` nil is omitted from the body,
/// which for a first-party client means the full grant (`sync stats:read
/// account`); a third-party app names `stats:read`.
public struct AccountStartRequest: Codable, Sendable, Equatable {
    public var email: String
    public var pkceChallenge: String
    public var client: String
    public var deviceName: String
    public var platform: String
    public var scopes: String?

    public init(email: String, pkceChallenge: String, client: String,
                deviceName: String, platform: String, scopes: String? = nil) {
        self.email = email
        self.pkceChallenge = pkceChallenge
        self.client = client
        self.deviceName = deviceName
        self.platform = platform
        self.scopes = scopes
    }
}

/// `202 { pollToken }` from start.
public struct AccountStartResponse: Codable, Sendable, Equatable {
    public var pollToken: String
    public init(pollToken: String) { self.pollToken = pollToken }
}

/// `POST /v1/auth/verify/poll`.
public struct AccountPollRequest: Codable, Sendable, Equatable {
    public var pollToken: String
    public var pkceVerifier: String
    public init(pollToken: String, pkceVerifier: String) {
        self.pollToken = pollToken
        self.pkceVerifier = pkceVerifier
    }
}

/// Who signed in: `account` on the poll's 201, and `GET /v1/me`. `email`
/// is present only when the grant includes `account` (a first-party sign-in).
public struct AccountIdentity: Codable, Sendable, Equatable {
    public var id: String
    public var email: String?
    public var callsign: String?
    public var displayName: String?

    public init(id: String, email: String? = nil, callsign: String? = nil, displayName: String? = nil) {
        self.id = id
        self.email = email
        self.callsign = callsign
        self.displayName = displayName
    }

    /// What Settings shows for the account: the callsign, else the display
    /// name, else nothing (the email the user typed is shown beside it).
    public var label: String {
        if let callsign, !callsign.isEmpty { return callsign }
        if let displayName, !displayName.isEmpty { return displayName }
        return ""
    }
}

/// `201` from poll and `200` from refresh: the tokens, and on the poll the
/// account they belong to.
public struct AccountTokens: Codable, Sendable, Equatable {
    public var access: String
    public var refresh: String
    /// Seconds the access token is good for.
    public var expiresIn: Int
    public var account: AccountIdentity?

    public init(access: String, refresh: String, expiresIn: Int, account: AccountIdentity? = nil) {
        self.access = access
        self.refresh = refresh
        self.expiresIn = expiresIn
        self.account = account
    }
}

/// `POST /v1/auth/token/refresh`.
public struct AccountRefreshRequest: Codable, Sendable, Equatable {
    public var refresh: String
    public init(refresh: String) { self.refresh = refresh }
}

/// The service's one error shape: `{ error, message }`.
public struct AccountErrorResponse: Codable, Sendable, Equatable {
    public var error: String?
    public var message: String?
    public init(error: String? = nil, message: String? = nil) {
        self.error = error
        self.message = message
    }
}

// MARK: - Sessions

/// One session as the service stores it (README §9 SessionRecord,
/// `schemaVersion` 1): the app's `SessionRecord` with times in whole
/// milliseconds and the date as epoch milliseconds. The day it counts
/// toward is not here — practice days are their own route (`AccountDayUpload`).
public struct AccountSessionUpload: Codable, Sendable, Equatable, Identifiable {
    public struct CharacterResult: Codable, Sendable, Equatable {
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
    }

    public static let schemaVersion = 1

    /// The record's UUID, lowercased — the service's dedupe key, so a batch
    /// sent twice (a lost reply, a retry) lands once.
    public var id: String
    /// Epoch milliseconds.
    public var date: Int64
    public var mode: String
    public var characterWpm: Int
    public var effectiveWpm: Int
    public var attempts: Int
    public var correct: Int
    public var fastestTtrMs: Int?
    public var medianTtrMs: Int?
    public var durationSeconds: Double?
    public var score: Int?
    public var schemaVersion: Int
    public var characters: [CharacterResult]
    public var activeCharacters: [String]

    /// `record` as the service wants it.
    public init(_ record: SessionRecord) {
        id = record.id.uuidString.lowercased()
        date = Int64((record.date.timeIntervalSince1970 * 1000).rounded())
        mode = record.mode
        characterWpm = record.characterWPM
        effectiveWpm = record.effectiveWPM
        attempts = record.attempts
        correct = record.correct
        fastestTtrMs = record.fastestTTR.map(Self.milliseconds)
        medianTtrMs = record.medianTTR.map(Self.milliseconds)
        durationSeconds = record.durationSeconds
        score = record.score
        schemaVersion = Self.schemaVersion
        characters = record.characters.map {
            CharacterResult(character: $0.character, attempts: $0.attempts,
                            correct: $0.correct, medianTtrMs: $0.medianTTR.map(Self.milliseconds))
        }
        activeCharacters = record.activeCharacters
    }

    static func milliseconds(_ seconds: TimeInterval) -> Int {
        Int((seconds * 1000).rounded())
    }
}

/// `POST /v1/sync/sessions`: a batch, oldest first, at most 200.
public struct AccountSessionsUploadRequest: Codable, Sendable, Equatable {
    public var sessions: [AccountSessionUpload]
    public init(sessions: [AccountSessionUpload]) { self.sessions = sessions }
}

/// The push's reply: every id sent lands in exactly one list. `accepted`
/// was stored, `skipped` was already on the account (never counted twice),
/// `rejected` failed validation and will never be taken — all three leave
/// the outbox (README §7). The `stats` the reply also carries are not
/// decoded here yet.
public struct AccountPushResponse: Codable, Sendable, Equatable {
    public struct Rejected: Codable, Sendable, Equatable {
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

    public init(accepted: [String], skipped: [String], rejected: [Rejected]) {
        self.accepted = accepted
        self.skipped = skipped
        self.rejected = rejected
    }

    /// Every id the service has dealt with, one way or another: what to
    /// drop from the outbox.
    public var settled: [String] {
        accepted + skipped + rejected.compactMap(\.id)
    }
}

/// One entry of this device's practice ledger, for `POST /v1/sync/days`:
/// the local calendar day and whole seconds practised (0–86,400; 0 still
/// marks the day). The server keeps the larger of what it has and what is
/// sent, so resending is idempotent.
public struct AccountDayUpload: Codable, Sendable, Equatable {
    public var day: String
    public var seconds: Int

    public init(day: String, seconds: Int) {
        self.day = day
        self.seconds = min(max(0, seconds), 86_400)
    }
}

/// `POST /v1/sync/days`: at most 400 entries.
public struct AccountDaysUploadRequest: Codable, Sendable, Equatable {
    public var days: [AccountDayUpload]
    public init(days: [AccountDayUpload]) { self.days = days }
}

/// The days reply: the account's summed figure for each day sent, and the
/// entries it refused.
public struct AccountDaysResponse: Codable, Sendable, Equatable {
    public struct Rejected: Codable, Sendable, Equatable {
        public var day: String?
        public var reason: String
        public init(day: String?, reason: String) {
            self.day = day
            self.reason = reason
        }
    }

    public var days: [String: Int]
    public var rejected: [Rejected]

    public init(days: [String: Int], rejected: [Rejected]) {
        self.days = days
        self.rejected = rejected
    }
}

// MARK: - Upload queue

/// The outbox: the sessions finished on this device that the service has
/// not yet dealt with, oldest first, and the practice days whose figure
/// changed since they were last sent. Persisted so a session finished
/// offline, or while the app was killed mid-upload, still goes up. Bounded:
/// past `limit` the oldest sessions fall off, the same policy as
/// `SessionHistory` (the service is a copy of the history, not a longer
/// one); days are capped by the ledger itself.
public struct AccountSyncQueue: Codable, Sendable, Equatable {
    public private(set) var pending: [AccountSessionUpload]
    /// Days (`yyyy-mm-dd`) to send with their current ledger figure.
    public private(set) var pendingDays: Set<String>

    public static let limit = 500
    /// How many sessions go in one request: the service's maximum (README
    /// §7), and what the merge rules say a first sign-in pushes at a time.
    public static let batchSize = 200
    /// The most days one request takes — the ledger's own cap.
    public static let daysBatchSize = ActivityLedger.capDays

    public init(pending: [AccountSessionUpload] = [], pendingDays: Set<String> = []) {
        self.pending = pending
        self.pendingDays = pendingDays
    }

    enum CodingKeys: String, CodingKey { case pending, pendingDays }

    /// Row-tolerant, like `SessionHistory`: a session that no longer
    /// decodes is dropped rather than losing the outbox; an outbox saved
    /// before days were queued has none pending.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let rows = try c.decodeIfPresent([FailableDecodable<AccountSessionUpload>].self, forKey: .pending) ?? []
        pending = rows.compactMap(\.value)
        pendingDays = try c.decodeIfPresent(Set<String>.self, forKey: .pendingDays) ?? []
    }

    /// Nothing to send: no sessions and no days.
    public var isEmpty: Bool { pending.isEmpty && pendingDays.isEmpty }
    /// Sessions waiting.
    public var count: Int { pending.count }

    /// Add one session. A record already queued (the same id) is replaced
    /// in place, not duplicated.
    public mutating func enqueue(_ session: AccountSessionUpload) {
        if let i = pending.firstIndex(where: { $0.id == session.id }) {
            pending[i] = session
            return
        }
        pending.append(session)
        if pending.count > Self.limit {
            pending.removeFirst(pending.count - Self.limit)
        }
    }

    /// Queue every session of `history` not already pending — what signing
    /// in does, so the account starts with the history the device has.
    /// Oldest first, so the server's sequence follows the calendar.
    public mutating func enqueue(history: SessionHistory) {
        for record in history.sessions.reversed() {
            enqueue(AccountSessionUpload(record))
        }
    }

    /// A day whose ledger figure changed (a session ended on it).
    public mutating func enqueue(day: String) {
        pendingDays.insert(day)
    }

    /// Every day the ledger holds — what signing in does.
    public mutating func enqueue(ledger: ActivityLedger) {
        pendingDays.formUnion(ledger.days.keys)
    }

    /// The pending days with their current figure from `ledger`, oldest
    /// first, at most `daysBatchSize`. A pending day the ledger no longer
    /// holds is sent as 0: it was practised, whatever the ledger forgot.
    public func nextDays(from ledger: ActivityLedger) -> [AccountDayUpload] {
        pendingDays.sorted().prefix(Self.daysBatchSize).map {
            AccountDayUpload(day: $0, seconds: ledger.days[$0] ?? 0)
        }
    }

    /// The service took these days.
    public mutating func acknowledge(days: [String]) {
        pendingDays.subtract(days)
    }

    /// The next batch to send: the oldest `batchSize`, or fewer.
    public func nextBatch() -> [AccountSessionUpload] {
        Array(pending.prefix(Self.batchSize))
    }

    /// The service acknowledged these ids: drop them.
    public mutating func acknowledge(_ ids: [String]) {
        let done = Set(ids)
        pending.removeAll { done.contains($0.id) }
    }

    public mutating func removeAll() {
        pending.removeAll()
        pendingDays.removeAll()
    }
}
