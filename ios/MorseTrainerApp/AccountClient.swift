// AccountClient.swift
// The account service's HTTP client — an actor, like LeaderboardClient, so
// the tokens and every request that carries them are serialised. Wire types
// and the pure rules (PKCE, the session record, the queue) live in
// MorseKit/AccountSync.swift; this file is only the network and the Keychain.
//
// The service is the accounts Worker at `baseURL`; its README (repository
// another-morse-trainer-accounts) is the contract. The parts that matter:
//
//   * Sign-in is an emailed link. `/v1/auth/verify/start` takes the address
//     and a PKCE challenge and answers 202 with a poll token whether or not
//     the address has an account; the app then polls
//     `/v1/auth/verify/poll` every two seconds with the verifier until the
//     user presses Confirm in the email (201 with tokens), the link expires
//     (410, fifteen minutes), or the user cancels.
//   * The access token lasts fifteen minutes and goes in `Authorization:
//     Bearer`. The refresh token lasts sixty days and ROTATES ON EVERY USE:
//     presenting an old one signs the device out, so refreshes are
//     single-flight here and the new token is saved before it is used.
//   * The refresh token is a credential: it lives in the Keychain, bound to
//     this device (not iCloud Keychain — the device id inside it is this
//     sign-in's). The access token is held in memory only.
//   * A 401 that a refresh cannot cure means the user removed this device
//     from their account, or the token leaked and was reused; either way
//     the device is signed out locally (`AccountError.signedOut`).
//   * Sessions go up with `POST /v1/sync/sessions`, at most 200 a call,
//     idempotent by id; the reply says which were accepted, skipped (already
//     there) or rejected (never will be). Practice days go up with
//     `POST /v1/sync/days`, idempotent per day. Both need the `sync` scope,
//     which a first-party sign-in gets by not naming any scopes.
//
// Nothing here blocks the UI: AppModel+Account.swift fires these as Tasks.

import Foundation
import OSLog
import Security

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "account")

enum AccountError: Error {
    /// No refresh token: nobody is signed in on this device.
    case notSignedIn
    /// The emailed link expired or was already used (410).
    case linkExpired
    /// The poll's verifier did not match the challenge — a bug here, not
    /// the user's (400 invalid_verifier).
    case invalidVerifier
    /// The service refused the refresh token: this device was removed from
    /// the account, or the token was reused. Signed out locally.
    case signedOut
    /// 429: wait this many seconds.
    case rateLimited(seconds: Int)
    /// The service answered with an error body (`{ error, message }`).
    case server(status: Int, code: String?, message: String?)
    /// No answer, or one we could not read.
    case transport(String)

    /// One line for Settings.
    var message: String {
        switch self {
        case .notSignedIn: return "Sign in to sync your sessions."
        case .linkExpired: return "That sign-in link has expired. Send another."
        case .invalidVerifier: return "The sign-in could not be verified. Send another link."
        case .signedOut: return "This device was signed out of your account."
        case .rateLimited(let s): return "Too many requests; try again in \(max(1, s)) seconds."
        case .server(let status, _, let message):
            if let message, !message.isEmpty { return message }
            return "The account service answered \(status)."
        case .transport(let s): return s
        }
    }

    var isSignedOut: Bool {
        if case .signedOut = self { return true }
        return false
    }
}

/// A sign-in in progress: what the poll needs. The verifier never leaves
/// memory and is dropped once tokens arrive.
struct AccountSignInAttempt: Sendable {
    let pollToken: String
    let verifier: String
    let startedAt: Date
}

actor AccountClient {
    /// The accounts Worker.
    static let baseURL = URL(string: "https://amt-accounts.n9ho-amt.workers.dev")!
    /// How the service names this app: in the sign-in email, on the confirm
    /// page and in the account's devices list. One of the three first-party
    /// ids the README reserves.
    static let clientID = "amt-ios"
    /// The poll cadence the service allows: faster is a 429.
    static let pollInterval: TimeInterval = 2
    /// How long the emailed link works, plus a little for the clock.
    static let linkLifetime: TimeInterval = 15 * 60 + 30
    static let timeout: TimeInterval = 15

    private let session: URLSession
    private let keychain = AccountKeychain()
    private var access: String?
    private var accessExpiry = Date.distantPast
    /// The refresh in flight, if any, so two requests that both met a 401
    /// share one rotation instead of racing with the same token.
    private var refreshInFlight: Task<Void, Error>?

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = Self.timeout
        config.timeoutIntervalForResource = Self.timeout * 2
        config.waitsForConnectivity = false
        config.urlCache = nil   // replies are `Cache-Control: private, no-store`
        session = URLSession(configuration: config)
    }

    /// Whether this device holds a refresh token. The settings say who is
    /// signed in; this says whether the credential is still here.
    nonisolated var hasRefreshToken: Bool { keychain.read() != nil }

    // MARK: - Sign-in

    /// `POST /v1/auth/verify/start`: ask for the email. Always 202, whether
    /// or not the address has an account — nothing says which.
    func startSignIn(email: String, deviceName: String, platform: String) async throws -> AccountSignInAttempt {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else { throw AccountError.transport("Could not make a sign-in secret.") }
        let verifier = AccountPKCE.verifier(from: bytes)
        // No `scopes`: a first-party client gets the full grant.
        let body = AccountStartRequest(email: email, pkceChallenge: AccountPKCE.challenge(for: verifier),
                                       client: Self.clientID, deviceName: deviceName,
                                       platform: platform, scopes: nil)
        let (data, http) = try await post("v1/auth/verify/start", body: body, bearer: nil)
        guard http.statusCode == 202 else { throw Self.serverError(http, data) }
        let reply = try Self.decode(AccountStartResponse.self, from: data)
        log.info("sign-in started")
        return AccountSignInAttempt(pollToken: reply.pollToken, verifier: verifier, startedAt: Date())
    }

    /// `POST /v1/auth/verify/poll` every two seconds until the user presses
    /// Confirm (tokens saved, the account returned), the link expires, or
    /// the task is cancelled. A network blip mid-poll is not the end of the
    /// sign-in: the loop keeps going until the link's lifetime is up.
    func waitForConfirmation(_ attempt: AccountSignInAttempt) async throws -> AccountIdentity {
        let body = AccountPollRequest(pollToken: attempt.pollToken, pkceVerifier: attempt.verifier)
        while true {
            try Task.checkCancellation()
            guard Date().timeIntervalSince(attempt.startedAt) < Self.linkLifetime else {
                throw AccountError.linkExpired
            }
            try await Task.sleep(nanoseconds: UInt64(Self.pollInterval * 1_000_000_000))
            let data: Data
            let http: HTTPURLResponse
            do {
                (data, http) = try await post("v1/auth/verify/poll", body: body, bearer: nil)
            } catch AccountError.transport {
                continue
            }
            switch http.statusCode {
            case 202:
                continue
            case 429:
                let wait = Self.retryAfter(http) ?? Int(Self.pollInterval)
                try await Task.sleep(nanoseconds: UInt64(max(1, wait)) * 1_000_000_000)
                continue
            case 201:
                let tokens = try Self.decode(AccountTokens.self, from: data)
                try store(tokens)
                log.info("signed in")
                if let account = tokens.account { return account }
                return try await me()
            case 410:
                throw AccountError.linkExpired
            case 400:
                let error = Self.serverError(http, data)
                if case .server(_, let code, _) = error, code == "invalid_verifier" {
                    throw AccountError.invalidVerifier
                }
                throw error
            default:
                throw Self.serverError(http, data)
            }
        }
    }

    /// `GET /v1/me`: who the tokens belong to.
    func me() async throws -> AccountIdentity {
        let (data, _) = try await authorized("v1/me", method: "GET", body: nil as AccountStartRequest?)
        return try Self.decode(AccountIdentity.self, from: data)
    }

    // MARK: - Sync

    /// `POST /v1/sync/sessions`: a batch, oldest first, at most 200. The
    /// reply settles every id one way or another; a batch whose reply was
    /// lost is safe to send again (the repeats come back as `skipped`).
    func pushSessions(_ sessions: [AccountSessionUpload]) async throws -> AccountPushResponse {
        let (data, _) = try await authorized("v1/sync/sessions", method: "POST",
                                             body: AccountSessionsUploadRequest(sessions: sessions))
        let reply = try Self.decode(AccountPushResponse.self, from: data)
        log.info("pushed \(sessions.count) session(s): \(reply.accepted.count) accepted, \(reply.skipped.count) skipped, \(reply.rejected.count) rejected")
        for r in reply.rejected { log.notice("rejected \(r.id ?? "?", privacy: .public): \(r.reason, privacy: .public)") }
        return reply
    }

    /// `POST /v1/sync/days`: this device's ledger entries, at most 400.
    /// The server keeps the larger figure per day, so resending is harmless.
    func pushDays(_ days: [AccountDayUpload]) async throws -> AccountDaysResponse {
        let (data, _) = try await authorized("v1/sync/days", method: "POST",
                                             body: AccountDaysUploadRequest(days: days))
        let reply = try Self.decode(AccountDaysResponse.self, from: data)
        log.info("pushed \(days.count) day(s): \(reply.rejected.count) rejected")
        return reply
    }

    // MARK: - Sign-out

    /// `POST /v1/auth/logout` (best effort — the device may be offline),
    /// then forget both tokens. The service's answer does not matter: the
    /// user asked to be signed out, and they are.
    func signOut() async {
        if let token = try? await validAccessToken() {
            var request = URLRequest(url: Self.baseURL.appendingPathComponent("v1/auth/logout"))
            request.httpMethod = "POST"
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            _ = try? await session.data(for: request)
        }
        forgetTokens()
        log.info("signed out")
    }

    /// Drop the tokens without telling the service (it already refused them).
    func forgetTokens() {
        access = nil
        accessExpiry = .distantPast
        keychain.delete()
    }

    // MARK: - Tokens

    private func store(_ tokens: AccountTokens) throws {
        // The refresh token first: once the service has rotated it, the old
        // one is dead, and a crash between the two writes must not lose it.
        try keychain.write(tokens.refresh)
        access = tokens.access
        accessExpiry = Date(timeIntervalSinceNow: TimeInterval(tokens.expiresIn))
    }

    /// An access token with at least a minute left, refreshing if not.
    private func validAccessToken() async throws -> String {
        if let access, accessExpiry.timeIntervalSinceNow > 60 { return access }
        try await refreshTokens()
        guard let access else { throw AccountError.signedOut }
        return access
    }

    /// `POST /v1/auth/token/refresh`, once at a time. A 401 here is final:
    /// the device is signed out. A transport error leaves the tokens alone
    /// — the old refresh token is still good if the request never arrived.
    private func refreshTokens() async throws {
        if let refreshInFlight {
            try await refreshInFlight.value
            return
        }
        let task = Task { try await self.performRefresh() }
        refreshInFlight = task
        defer { refreshInFlight = nil }
        try await task.value
    }

    private func performRefresh() async throws {
        guard let refresh = keychain.read() else { throw AccountError.notSignedIn }
        let (data, http) = try await post("v1/auth/token/refresh", body: AccountRefreshRequest(refresh: refresh), bearer: nil)
        switch http.statusCode {
        case 200:
            try store(try Self.decode(AccountTokens.self, from: data))
        case 401:
            log.notice("refresh refused: signed out")
            forgetTokens()
            throw AccountError.signedOut
        default:
            throw Self.serverError(http, data)
        }
    }

    // MARK: - HTTP

    /// A request with the bearer token, retried once through a refresh on
    /// 401. The second 401 is the service's final word.
    private func authorized<Body: Encodable>(_ path: String, method: String, body: Body?) async throws -> (Data, HTTPURLResponse) {
        for attempt in 0..<2 {
            let token = try await validAccessToken()
            let (data, http) = try await send(path, method: method, body: body, bearer: token)
            if http.statusCode == 401 && attempt == 0 {
                access = nil   // force the refresh
                continue
            }
            guard (200..<300).contains(http.statusCode) else { throw Self.serverError(http, data) }
            return (data, http)
        }
        forgetTokens()
        throw AccountError.signedOut
    }

    private func post<Body: Encodable>(_ path: String, body: Body, bearer: String?) async throws -> (Data, HTTPURLResponse) {
        try await send(path, method: "POST", body: body, bearer: bearer)
    }

    private func send<Body: Encodable>(_ path: String, method: String, body: Body?, bearer: String?) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: Self.baseURL.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        if let bearer { request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw AccountError.transport("The account service's answer could not be read.")
            }
            return (data, http)
        } catch let error as AccountError {
            throw error
        } catch {
            log.error("\(path, privacy: .public): \(error.localizedDescription, privacy: .public)")
            throw AccountError.transport("The account service could not be reached.")
        }
    }

    private static func serverError(_ http: HTTPURLResponse, _ data: Data) -> AccountError {
        if http.statusCode == 429 { return .rateLimited(seconds: retryAfter(http) ?? 5) }
        let body = try? JSONDecoder().decode(AccountErrorResponse.self, from: data)
        log.notice("\(http.url?.path ?? "?", privacy: .public): \(http.statusCode) \(body?.error ?? "", privacy: .public)")
        return .server(status: http.statusCode, code: body?.error, message: body?.message)
    }

    private static func retryAfter(_ http: HTTPURLResponse) -> Int? {
        http.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }
    }

    private static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            log.error("unreadable answer: \(error.localizedDescription, privacy: .public)")
            throw AccountError.transport("The account service's answer could not be read.")
        }
    }
}

// MARK: - Keychain

/// The refresh token's home: one generic-password item, readable after the
/// first unlock (a sync can run with the phone in a pocket) and never
/// synchronised to iCloud Keychain — the token names this device's sign-in,
/// and a second device presenting it would count as reuse and sign both out.
struct AccountKeychain: Sendable {
    private let service = "com.justinrogers.MorseTrainer.account"
    private let account = "refresh-token"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    func read() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(_ token: String) throws {
        SecItemDelete(query as CFDictionary)
        var q = query
        q[kSecValueData as String] = Data(token.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw AccountError.transport("Could not save the sign-in to the Keychain (\(status)).")
        }
    }

    func delete() {
        SecItemDelete(query as CFDictionary)
    }
}
