// AccountClient.swift
// The optional account's HTTP client — an actor, like LeaderboardClient, so
// sign-in state and token rotation are serialised. It speaks to the accounts
// Worker through two injected halves, so the harness can drive it with canned
// replies and no test touches the network:
//
//   * `AccountTransport` sends one request and returns status and body. The
//     app's is URLSession (MorseTrainerApp/AccountServices.swift).
//   * `AccountTokenStore` keeps the access/refresh pair. The app's is the
//     Keychain. The account id, email, pull cursor and outbox are not
//     secrets and are the caller's to keep elsewhere.
//
// The contract is the accounts Worker's README, sections 5, 7, 8 and 10.
// What matters most here: the refresh token rotates on every use and a
// reused one signs the device out, so the new pair is stored before the new
// access token is used, and two refreshes never run at once.

import Foundation

/// One request to the accounts Worker. `path` is relative to its base URL.
public struct AccountRequest: Sendable, Equatable {
    public var method: String
    public var path: String
    public var query: [String: String]
    public var body: Data?
    /// The access token for `Authorization: Bearer`, on routes that need one.
    public var bearer: String?

    public init(method: String, path: String, query: [String: String] = [:],
                body: Data? = nil, bearer: String? = nil) {
        self.method = method
        self.path = path
        self.query = query
        self.body = body
        self.bearer = bearer
    }
}

/// The Worker's answer: status, raw body, and `Retry-After` when it sent one.
public struct AccountResponse: Sendable, Equatable {
    public var status: Int
    public var body: Data
    public var retryAfter: Int?

    public init(status: Int, body: Data = Data(), retryAfter: Int? = nil) {
        self.status = status
        self.body = body
        self.retryAfter = retryAfter
    }
}

/// Sends a request. Throws only when there was no HTTP reply at all.
public protocol AccountTransport: Sendable {
    func send(_ request: AccountRequest) async throws -> AccountResponse
}

/// Keeps the token pair. `save` writes both at once, so the new refresh token
/// is stored before the new access token is ever used.
public protocol AccountTokenStore: Sendable {
    func load() -> AccountTokens?
    func save(_ tokens: AccountTokens) throws
    func clear()
}

/// The access token (15 minutes) and the refresh token (60 days, rotates).
public struct AccountTokens: Codable, Sendable, Equatable {
    public var access: String
    public var refresh: String
    /// Seconds the access token was good for when issued.
    public var expiresIn: Int

    public init(access: String, refresh: String, expiresIn: Int) {
        self.access = access
        self.refresh = refresh
        self.expiresIn = expiresIn
    }
}

/// `/v1/me`, and `account` in the sign-in reply. `email` comes only with the
/// `account` scope, which a first-party sign-in always has.
public struct AccountProfile: Decodable, Sendable, Equatable {
    public var id: String
    public var email: String?
    public var callsign: String?
    public var displayName: String?
}

/// One signed-in device on the account (`GET /v1/auth/devices`).
public struct AccountDevice: Decodable, Sendable, Equatable, Identifiable {
    public var id: String
    public var client: String
    public var deviceName: String?
    public var platform: String?
    public var createdAt: Int
    public var lastSeenAt: Int
    /// The device making the call.
    public var current: Bool
}

public enum AccountError: Error, Sendable, Equatable {
    /// No tokens, or the refresh token was refused: sign in again.
    case signedOut
    /// No HTTP reply at all.
    case network
    /// The Worker answered with an error status (README §10).
    case server(status: Int, code: String?, retryAfter: Int?)
    /// A success whose body could not be read.
    case unreadable
    /// The token store could not save.
    case storage

    /// What the sync engine does about it.
    public var retryAction: SyncRetryAction {
        switch self {
        case .signedOut, .storage: return .drop
        case .network, .unreadable: return .backoff
        case .server(let status, _, _): return AccountSync.retryAction(status: status)
        }
    }
}

/// One `poll()` of a sign-in in progress.
public enum AccountPollResult: Sendable, Equatable {
    /// Not confirmed yet: poll again in two seconds.
    case pending
    /// Confirmed. The tokens are already stored.
    case signedIn(AccountTokens, AccountProfile)
    /// The link's 15 minutes ran out, or there is no sign-in to poll: start again.
    case expired
    case error(AccountError)
}

public actor AccountClient {
    private let transport: any AccountTransport
    private let tokenStore: any AccountTokenStore
    /// The `client` id sent on sign-in.
    private let clientId: String

    /// The sign-in being polled. Kept in memory only: the verifier never
    /// touches disk, and a relaunch simply starts over.
    private var pending: (pollToken: String, verifier: String)?
    /// The refresh in flight, which every other caller waits on.
    private var refreshTask: Task<AccountTokens, Error>?

    public init(transport: any AccountTransport, tokenStore: any AccountTokenStore,
                clientId: String = AccountSync.clientId) {
        self.transport = transport
        self.tokenStore = tokenStore
        self.clientId = clientId
    }

    public var isSignedIn: Bool { tokenStore.load() != nil }

    // MARK: - Sign-in (README §2, §5)

    /// `POST /v1/auth/verify/start`: email a sign-in link for this device. The
    /// reply is 202 whether or not the address has an account.
    public func startSignIn(email: String, deviceName: String?, platform: String?) async throws {
        struct Body: Encodable {
            var email: String
            var pkceChallenge: String
            var client: String
            var deviceName: String?
            var platform: String?
        }
        struct Reply: Decodable { var pollToken: String }
        let verifier = AccountSync.newPKCEVerifier()
        let body = Body(email: email, pkceChallenge: AccountSync.pkceChallenge(verifier: verifier),
                        client: clientId, deviceName: deviceName, platform: platform)
        let response = try await send(AccountRequest(method: "POST", path: "v1/auth/verify/start",
                                                     body: try encode(body)))
        guard (200..<300).contains(response.status) else { throw serverError(response) }
        let reply: Reply = try decode(response.body)
        pending = (reply.pollToken, verifier)
    }

    /// `POST /v1/auth/verify/poll`, once. Call every two seconds while it
    /// answers `.pending`.
    public func poll() async -> AccountPollResult {
        struct Body: Encodable { var pollToken: String; var pkceVerifier: String }
        struct Reply: Decodable {
            var access: String
            var refresh: String
            var expiresIn: Int
            var account: AccountProfile
        }
        guard let pending else { return .expired }
        do {
            let body = try encode(Body(pollToken: pending.pollToken, pkceVerifier: pending.verifier))
            let response = try await send(AccountRequest(method: "POST", path: "v1/auth/verify/poll", body: body))
            switch response.status {
            case 201:
                let reply: Reply = try decode(response.body)
                let tokens = AccountTokens(access: reply.access, refresh: reply.refresh, expiresIn: reply.expiresIn)
                do { try tokenStore.save(tokens) } catch { throw AccountError.storage }
                finishSignIn(pending.pollToken)
                return .signedIn(tokens, reply.account)
            case 202, 429:
                // Not confirmed yet; or polled a little early, which the next
                // two-second poll is already slower than.
                return .pending
            case 410:
                finishSignIn(pending.pollToken)
                return .expired
            default:
                return .error(serverError(response))
            }
        } catch let error as AccountError {
            return .error(error)
        } catch {
            return .error(.unreadable)
        }
    }

    /// Forget the sign-in `pollToken` belonged to — unless a new one was
    /// started while its poll was in flight.
    private func finishSignIn(_ pollToken: String) {
        if pending?.pollToken == pollToken { pending = nil }
    }

    /// Abandon a sign-in in progress.
    public func cancelSignIn() {
        pending = nil
    }

    // MARK: - Tokens

    /// `POST /v1/auth/token/refresh`. The new pair is stored before it is
    /// returned. A refused refresh token clears the store and throws
    /// `.signedOut`; no reply, a 429 or a 5xx throws that error and keeps
    /// the tokens. Concurrent callers share one refresh: a second would
    /// present a token the first just spent, which signs the device out.
    @discardableResult
    public func refresh() async throws -> AccountTokens {
        if let running = refreshTask { return try await running.value }
        let task = Task { try await self.performRefresh() }
        refreshTask = task
        defer { refreshTask = nil }
        return try await task.value
    }

    private func performRefresh() async throws -> AccountTokens {
        struct Body: Encodable { var refresh: String }
        struct Reply: Decodable { var access: String; var refresh: String; var expiresIn: Int }
        guard let old = tokenStore.load() else { throw AccountError.signedOut }
        let response = try await send(AccountRequest(method: "POST", path: "v1/auth/token/refresh",
                                                     body: try encode(Body(refresh: old.refresh))))
        // Refused (a 4xx other than 429): the token is spent, revoked or
        // expired, so this device is signed out. No reply, a 429 or a 5xx
        // leaves the token unspent: keep it and let the caller back off.
        if (400..<500).contains(response.status), response.status != 429 {
            tokenStore.clear()
            throw AccountError.signedOut
        }
        guard (200..<300).contains(response.status) else { throw serverError(response) }
        let reply: Reply = try decode(response.body)
        let tokens = AccountTokens(access: reply.access, refresh: reply.refresh, expiresIn: reply.expiresIn)
        do {
            try tokenStore.save(tokens)
        } catch {
            // The old refresh token is spent and the new one cannot be kept:
            // this device is signed out either way.
            tokenStore.clear()
            throw AccountError.signedOut
        }
        return tokens
    }

    /// `POST /v1/auth/logout`, then forget the tokens whatever it answered.
    public func logout() async {
        _ = try? await authorized(AccountRequest(method: "POST", path: "v1/auth/logout"))
        tokenStore.clear()
        pending = nil
    }

    // MARK: - Account (README §5, §8)

    public func devices() async throws -> [AccountDevice] {
        struct Reply: Decodable { var devices: [AccountDevice] }
        let reply: Reply = try decode(try await authorized(AccountRequest(method: "GET", path: "v1/auth/devices")).body)
        return reply.devices
    }

    public func revokeDevice(id: String) async throws {
        _ = try await authorized(AccountRequest(method: "DELETE", path: "v1/auth/devices/\(id)"))
    }

    /// `PATCH /v1/me`. Both fields are sent: nil clears one.
    public func updateProfile(callsign: String?, displayName: String?) async throws -> AccountProfile {
        struct Body: Encodable {
            var callsign: String?
            var displayName: String?
            func encode(to encoder: Encoder) throws {
                var c = encoder.container(keyedBy: CodingKeys.self)
                if let callsign { try c.encode(callsign, forKey: .callsign) } else { try c.encodeNil(forKey: .callsign) }
                if let displayName { try c.encode(displayName, forKey: .displayName) } else { try c.encodeNil(forKey: .displayName) }
            }
            enum CodingKeys: String, CodingKey { case callsign, displayName }
        }
        let body = try encode(Body(callsign: callsign, displayName: displayName))
        return try decode(try await authorized(AccountRequest(method: "PATCH", path: "v1/me", body: body)).body)
    }

    /// `DELETE /v1/me`: the account and everything on it. Every token is dead
    /// afterwards, so this device's are forgotten.
    public func deleteAccount() async throws {
        _ = try await authorized(AccountRequest(method: "DELETE", path: "v1/me"))
        tokenStore.clear()
    }

    // MARK: - Sync (README §7)

    /// `POST /v1/sync/sessions`, at most 200 records.
    public func pushSessions(_ sessions: [SyncSession]) async throws -> SyncPushReply {
        struct Body: Encodable { var sessions: [SyncSession] }
        let body = try encode(Body(sessions: sessions))
        return try decode(try await authorized(AccountRequest(method: "POST", path: "v1/sync/sessions", body: body)).body)
    }

    /// `GET /v1/sync/sessions`: records after `since`, with per-character detail.
    public func pullSessions(since: Int, limit: Int = 200) async throws -> SyncPullReply {
        let request = AccountRequest(method: "GET", path: "v1/sync/sessions",
                                     query: ["since": String(since), "limit": String(limit)])
        return try decode(try await authorized(request).body)
    }

    /// `POST /v1/sync/days`: this device's own ledger.
    public func pushDays(_ batch: SyncDayBatch) async throws -> SyncDaysReply {
        let body = try encode(batch)
        return try decode(try await authorized(AccountRequest(method: "POST", path: "v1/sync/days", body: body)).body)
    }

    /// `PUT /v1/sync/state`. The reply holds the winning entry per key.
    public func putState(_ entries: [String: SyncStateEntry]) async throws -> SyncStateReply {
        struct Body: Encodable { var entries: [String: SyncStateEntry] }
        let body = try encode(Body(entries: entries))
        return try decode(try await authorized(AccountRequest(method: "PUT", path: "v1/sync/state", body: body)).body)
    }

    /// `GET /v1/sync/snapshot`. `today` is the local `yyyy-MM-dd`, for the streak.
    public func snapshot(today: String?) async throws -> SyncSnapshot {
        var query: [String: String] = [:]
        if let today { query["today"] = today }
        return try decode(try await authorized(AccountRequest(method: "GET", path: "v1/sync/snapshot", query: query)).body)
    }

    // MARK: - HTTP

    /// Send with the stored access token. On a 401, refresh once and retry
    /// once — unless another call already refreshed while this one was in
    /// flight, in which case the retry just uses the newer token. A refused
    /// refresh surfaces as `.signedOut`, with the tokens already cleared.
    private func authorized(_ request: AccountRequest) async throws -> AccountResponse {
        guard let tokens = tokenStore.load() else { throw AccountError.signedOut }
        var request = request
        request.bearer = tokens.access
        var response = try await send(request)
        if response.status == 401 {
            let fresh: AccountTokens
            if let current = tokenStore.load(), current.access != tokens.access {
                fresh = current
            } else {
                fresh = try await refresh()
            }
            request.bearer = fresh.access
            response = try await send(request)
        }
        guard (200..<300).contains(response.status) else { throw serverError(response) }
        return response
    }

    private func send(_ request: AccountRequest) async throws -> AccountResponse {
        do {
            return try await transport.send(request)
        } catch {
            throw AccountError.network
        }
    }

    private func serverError(_ response: AccountResponse) -> AccountError {
        struct Body: Decodable { var error: String }
        let code = (try? JSONDecoder().decode(Body.self, from: response.body))?.error
        return .server(status: response.status, code: code, retryAfter: response.retryAfter)
    }

    private func encode<T: Encodable>(_ value: T) throws -> Data {
        do { return try JSONEncoder().encode(value) } catch { throw AccountError.unreadable }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) } catch { throw AccountError.unreadable }
    }
}
