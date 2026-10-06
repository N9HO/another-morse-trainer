// AccountSync.swift
// The account service's wire types and the pure rules behind signing in and
// syncing sessions to it. No networking here: the client actor lives in the
// app target (`AccountClient.swift`). Everything in this file is data the
// Android and desktop ports can mirror field for field.
//
// The service is the accounts Worker (`amt-accounts.n9ho-amt.workers.dev`).
// Sign-in is an emailed link with PKCE: the app keeps a random verifier,
// sends its SHA-256 as the challenge, and proves the tokens are its own by
// presenting the verifier when it polls. There is no password, client secret
// or API key. Sessions go up as the shape the service hands back to readers
// (its `/v1/me/sessions` record), so a session reads the same on every side.

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

/// `POST /v1/auth/verify/start`.
public struct AccountStartRequest: Codable, Sendable, Equatable {
    public var email: String
    public var pkceChallenge: String
    public var client: String
    public var deviceName: String
    public var platform: String
    public var scopes: String

    public init(email: String, pkceChallenge: String, client: String,
                deviceName: String, platform: String, scopes: String) {
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

/// Who signed in: `account` on the poll's 201, and `GET /v1/me`.
public struct AccountIdentity: Codable, Sendable, Equatable {
    public var id: String
    public var callsign: String?
    public var displayName: String?

    public init(id: String, callsign: String? = nil, displayName: String? = nil) {
        self.id = id
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

/// One session as the service stores it (`/v1/me/sessions` record,
/// `schemaVersion` 1): the app's `SessionRecord` with times in whole
/// milliseconds and the date as epoch milliseconds. `day` is the local
/// calendar day the session ended on, which the server needs for the
/// activity calendar and streak — epoch milliseconds alone cannot say which
/// day it was where the learner sat.
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
    /// Local calendar day, `yyyy-mm-dd`.
    public var day: String
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

    /// `record` as the service wants it. `calendar` names the time zone the
    /// day is taken in — the device's, normally; a parameter so the mapping
    /// can be checked.
    public init(_ record: SessionRecord, calendar: Calendar = .current) {
        id = record.id.uuidString.lowercased()
        date = Int64((record.date.timeIntervalSince1970 * 1000).rounded())
        day = BuddyDay.label(for: record.date, calendar: calendar)
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

/// `POST /v1/me/sessions`: a batch, oldest first.
public struct AccountSessionsUploadRequest: Codable, Sendable, Equatable {
    public var sessions: [AccountSessionUpload]
    public init(sessions: [AccountSessionUpload]) { self.sessions = sessions }
}

// MARK: - Upload queue

/// The sessions finished on this device that the service has not yet
/// acknowledged, oldest first. Persisted with the settings so a session
/// finished offline, or while the app was killed mid-upload, still goes up.
/// Bounded: past `limit` the oldest fall off, the same policy as
/// `SessionHistory` (the service is a copy of the history, not a longer
/// one).
public struct AccountSyncQueue: Codable, Sendable, Equatable {
    public private(set) var pending: [AccountSessionUpload]

    public static let limit = 500
    /// How many go in one request — well under any body limit, and a lost
    /// reply re-sends at most this many (deduplicated by id on the server).
    public static let batchSize = 50

    public init(pending: [AccountSessionUpload] = []) {
        self.pending = pending
    }

    public var isEmpty: Bool { pending.isEmpty }
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
    public mutating func enqueue(history: SessionHistory, calendar: Calendar = .current) {
        for record in history.sessions.reversed() {
            enqueue(AccountSessionUpload(record, calendar: calendar))
        }
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
    }
}
