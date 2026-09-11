// LeaderboardClient.swift
// The shared leaderboard's HTTP client — an actor, like VailClient, so every
// request and the App Attest key it carries are serialised. Wire types and
// the pure rules live in MorseKit/Leaderboard.swift; this file is only the
// network and the attestation.
//
// The server is documented in the leaderboard repository's README and
// summarised in docs/high-scores-design.md §3–§4. The parts that matter here:
//
//   * Every write is attested. The app generates one App Attest key per
//     install, attests it once against a server challenge, and signs an
//     assertion over the challenge with every request. The key id is the
//     identity; the server ties one best score per mode to it.
//   * A run is a server-issued token: `/v1/run/start` at session start,
//     `/v1/run/submit` with the transcript at session end. The server grades
//     the transcript itself and computes the ranked metric.
//   * Reads (`/v1/board/{mode}`) are public and unattested.
//
// Nothing here blocks the UI: AppModel fires these as Tasks and shows the
// result on the summary when it arrives. Timeouts are short on purpose.

import CryptoKit
import DeviceCheck
import Foundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "leaderboard")

enum LeaderboardError: Error {
    /// This device cannot attest: the simulator, or a device without App
    /// Attest. Nothing will ever be posted from it.
    case unsupported
    /// DeviceCheck refused to make a key, attestation or assertion.
    case attestation(String)
    /// The server answered with an error body (`{ accepted: false, reason }`).
    case server(status: Int, reason: String)
    /// No answer, or one we could not read.
    case transport(String)

    /// One line for the session summary.
    var message: String {
        switch self {
        case .unsupported: return "Only a real device can post; the simulator cannot attest."
        case .attestation(let s): return "Attestation failed: \(s)"
        case .server(_, let reason): return reason
        case .transport(let s): return s
        }
    }

    /// A refusal is the server's verdict on the run (grading, timing, name,
    /// identity); anything else is the device or the network, and the run
    /// might have been fine.
    var isRefusal: Bool {
        if case .server = self { return true }
        return false
    }
}

actor LeaderboardClient {
    /// The Worker (leaderboard repository README).
    static let baseURL = URL(string: "https://amt-leaderboard.n9ho.workers.dev")!
    /// Short: a run's result is shown on the summary when it arrives, and a
    /// slow answer is not worth holding a task open for.
    static let timeout: TimeInterval = 10

    private let session: URLSession
    private let attestor = AppAttestor()

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = Self.timeout
        config.timeoutIntervalForResource = Self.timeout * 2
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
    }

    /// Whether this device can ever post. False on the simulator and on
    /// devices without App Attest; the UI says so rather than trying.
    nonisolated var canAttest: Bool { DCAppAttestService.shared.isSupported }

    // MARK: - Public API

    /// `GET /v1/board/{mode}`: the top rows, no attestation needed.
    func board(_ mode: LeaderboardMode, limit: Int = 50) async throws -> [LeaderboardBoardRow] {
        var url = Self.baseURL.appendingPathComponent("v1/board/\(mode.rawValue)")
        if var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            parts.queryItems = [URLQueryItem(name: "limit", value: String(limit))]
            url = parts.url ?? url
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        let response: LeaderboardBoardResponse = try await send(request)
        return response.rows
    }

    /// `/v1/attest/challenge` then `/v1/run/start`: a run token for `mode`
    /// at `speeds`, bound to this device's key. Called at session start.
    func startRun(mode: LeaderboardMode, speeds: LeaderboardRunSpeeds) async throws -> String {
        let response: LeaderboardStartResponse = try await attestedWithChallenge(path: "v1/run/start") { challenge, attestation in
            LeaderboardStartRequest(mode: mode, speeds: speeds, challenge: challenge, attestation: attestation)
        }
        log.info("run started: \(mode.rawValue, privacy: .public) at \(speeds.characterWpm)/\(speeds.effectiveWpm) wpm")
        return response.runToken
    }

    /// `/v1/run/submit`: the transcript for `runToken`. The assertion is
    /// bound to the token itself (that is the "challenge" on submit), so a
    /// captured assertion cannot be replayed against another run.
    func submit(runToken: String, displayName: String,
                transcript: [LeaderboardTranscriptItem]) async throws -> LeaderboardSubmitResponse {
        let response: LeaderboardSubmitResponse = try await attested(clientData: runToken, path: "v1/run/submit") { attestation in
            LeaderboardSubmitRequest(runToken: runToken, displayName: displayName,
                                     transcript: transcript, attestation: attestation)
        }
        log.info("run submitted: metric \(response.metric) rank \(response.rank) (\(response.correct)/\(response.total))")
        return response
    }

    /// `/v1/me/delete`: remove everything the server holds for this device's
    /// key — scores, submissions, tokens and the key itself. The next write
    /// therefore starts over with a fresh key.
    func deleteMyScores() async throws {
        struct Deleted: Decodable { var deleted: Bool }
        let _: Deleted = try await attestedWithChallenge(path: "v1/me/delete") { challenge, attestation in
            LeaderboardDeleteRequest(challenge: challenge, attestation: attestation)
        }
        await attestor.forgetKey()
        log.info("scores deleted")
    }

    // MARK: - Attested requests

    /// A write whose client data is a fresh server challenge (`/run/start`,
    /// `/me/delete`). The challenge is consumed by the server before it
    /// checks the attestation, so the one "unknown key" retry needs a new
    /// challenge as well as a fresh attestation object.
    private func attestedWithChallenge<Body: Encodable, Result: Decodable>(
        path: String,
        _ makeBody: @Sendable (String, LeaderboardAttestation) -> Body
    ) async throws -> Result {
        do {
            let challenge = try await fetchChallenge()
            let attestation = try await attestor.attestation(clientData: challenge, forceAttestKey: false)
            return try await post(path, body: makeBody(challenge, attestation), confirmKey: true)
        } catch LeaderboardError.server(status: 401, reason: let reason) where LeaderboardServerHints.isUnknownKey(reason) {
            log.notice("server does not know our key; re-attesting once")
            let challenge = try await fetchChallenge()
            let attestation = try await attestor.attestation(clientData: challenge, forceAttestKey: true)
            return try await post(path, body: makeBody(challenge, attestation), confirmKey: true)
        }
    }

    /// A write whose client data is given (`/run/submit`, where it is the run
    /// token). The token is not consumed on a 401, so the retry reuses it.
    private func attested<Body: Encodable, Result: Decodable>(
        clientData: String, path: String,
        _ makeBody: @Sendable (LeaderboardAttestation) -> Body
    ) async throws -> Result {
        do {
            let attestation = try await attestor.attestation(clientData: clientData, forceAttestKey: false)
            return try await post(path, body: makeBody(attestation), confirmKey: true)
        } catch LeaderboardError.server(status: 401, reason: let reason) where LeaderboardServerHints.isUnknownKey(reason) {
            log.notice("server does not know our key; re-attesting once")
            let attestation = try await attestor.attestation(clientData: clientData, forceAttestKey: true)
            return try await post(path, body: makeBody(attestation), confirmKey: true)
        }
    }

    private func fetchChallenge() async throws -> String {
        struct Empty: Encodable {}
        let r: LeaderboardChallengeResponse = try await post("v1/attest/challenge", body: Empty(), confirmKey: false)
        return r.challenge
    }

    // MARK: - HTTP

    private func post<Body: Encodable, Result: Decodable>(_ path: String, body: Body, confirmKey: Bool) async throws -> Result {
        var request = URLRequest(url: Self.baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        let result: Result = try await send(request)
        // The server accepted an assertion from this key, so it holds the
        // key's public half: no need to carry the attestation object again.
        if confirmKey { await attestor.keyConfirmed() }
        return result
    }

    private func send<Result: Decodable>(_ request: URLRequest) async throws -> Result {
        var request = request
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            log.error("\(request.url?.path ?? "?", privacy: .public): \(error.localizedDescription, privacy: .public)")
            throw LeaderboardError.transport("The leaderboard could not be reached.")
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let reason = (try? JSONDecoder().decode(LeaderboardErrorResponse.self, from: data))?.reason
                ?? "server answered \(status)"
            log.notice("\(request.url?.path ?? "?", privacy: .public): \(status) \(reason, privacy: .public)")
            throw LeaderboardError.server(status: status, reason: reason)
        }
        do {
            return try JSONDecoder().decode(Result.self, from: data)
        } catch {
            log.error("\(request.url?.path ?? "?", privacy: .public): unreadable answer: \(error.localizedDescription, privacy: .public)")
            throw LeaderboardError.transport("The leaderboard's answer could not be read.")
        }
    }
}

// MARK: - App Attest

/// One App Attest key per install, and the attestation/assertion pair the
/// server wants with every write. Follows Apple's "Establishing your app's
/// integrity": generate a key once, attest it once against a server
/// challenge, then assert with it forever after.
///
/// The key id is kept in UserDefaults. It is not a secret — it is a hash of
/// a public key, and the private half never leaves the Secure Enclave — and
/// it does not survive to another device (a restored backup carries the id
/// but not the key), which is what the server's "unknown key" retry and the
/// `invalidKey` handling below are for.
private actor AppAttestor {
    private static let keyIdKey = "leaderboard.appAttest.keyId"
    /// Set once the server has accepted a request signed by the key, so the
    /// attestation object — heavy, and one per key — is not sent again.
    private static let confirmedKey = "leaderboard.appAttest.keyConfirmed"

    private var keyId: String? = UserDefaults.standard.string(forKey: AppAttestor.keyIdKey)
    private var confirmed = UserDefaults.standard.bool(forKey: AppAttestor.confirmedKey)

    /// The payload for a request whose client data is `clientData` (the
    /// server's challenge string, exactly). Carries the attestation object
    /// when the server has not yet confirmed this key, or when asked to.
    func attestation(clientData: String, forceAttestKey: Bool) async throws -> LeaderboardAttestation {
        let service = DCAppAttestService.shared
        guard service.isSupported else { throw LeaderboardError.unsupported }
        // Per Apple: the client data hash is SHA-256 of the client data; the
        // server recomputes it from the `clientData` string we send.
        let hash = Data(SHA256.hash(data: Data(clientData.utf8)))

        var id = try await currentKeyId(service)
        var attestationObject: Data?
        if forceAttestKey || !confirmed {
            do {
                attestationObject = try await service.attestKey(id, clientDataHash: hash)
            } catch let error as DCError where error.code == .invalidKey {
                // The stored id names a key this device does not hold (a
                // restore from another device) or one already attested that
                // Apple will not attest again: start over with a new key.
                log.notice("attestKey: invalid key; generating a new one")
                id = try await newKey(service)
                attestationObject = try await wrap { try await service.attestKey(id, clientDataHash: hash) }
            } catch {
                throw LeaderboardError.attestation(Self.describe(error))
            }
        }
        let assertion: Data
        do {
            assertion = try await service.generateAssertion(id, clientDataHash: hash)
        } catch let error as DCError where error.code == .invalidKey {
            // Same recovery: a fresh key needs its attestation object along.
            log.notice("generateAssertion: invalid key; generating a new one")
            id = try await newKey(service)
            attestationObject = try await wrap { try await service.attestKey(id, clientDataHash: hash) }
            assertion = try await wrap { try await service.generateAssertion(id, clientDataHash: hash) }
        } catch {
            throw LeaderboardError.attestation(Self.describe(error))
        }
        return LeaderboardAttestation(payload: .init(
            keyId: id,
            attestation: attestationObject?.base64EncodedString(),
            assertion: assertion.base64EncodedString(),
            clientData: clientData))
    }

    /// The server accepted a request signed by the current key.
    func keyConfirmed() {
        guard !confirmed else { return }
        confirmed = true
        UserDefaults.standard.set(true, forKey: Self.confirmedKey)
    }

    /// After `/v1/me/delete` the server no longer holds the key; a fresh one
    /// on the next write keeps "delete my scores" meaning a clean slate.
    func forgetKey() {
        keyId = nil
        confirmed = false
        UserDefaults.standard.removeObject(forKey: Self.keyIdKey)
        UserDefaults.standard.set(false, forKey: Self.confirmedKey)
    }

    private func currentKeyId(_ service: DCAppAttestService) async throws -> String {
        if let keyId { return keyId }
        return try await newKey(service)
    }

    private func newKey(_ service: DCAppAttestService) async throws -> String {
        let id: String
        do {
            id = try await service.generateKey()
        } catch {
            throw LeaderboardError.attestation(Self.describe(error))
        }
        keyId = id
        confirmed = false
        UserDefaults.standard.set(id, forKey: Self.keyIdKey)
        UserDefaults.standard.set(false, forKey: Self.confirmedKey)
        return id
    }

    private func wrap<T>(_ body: () async throws -> T) async throws -> T {
        do { return try await body() } catch { throw LeaderboardError.attestation(Self.describe(error)) }
    }

    private static func describe(_ error: Error) -> String {
        if let dc = error as? DCError {
            switch dc.code {
            case .featureUnsupported: return "App Attest is not available on this device."
            case .invalidInput: return "invalid input"
            case .invalidKey: return "invalid key"
            case .serverUnavailable: return "Apple's attestation service is unavailable; try again later."
            case .unknownSystemFailure: return "a system error"
            @unknown default: return dc.localizedDescription
            }
        }
        return error.localizedDescription
    }
}
