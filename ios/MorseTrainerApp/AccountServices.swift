// AccountServices.swift
// The two platform halves of the optional account's client
// (MorseKit/AccountClient.swift): the URLSession transport and the Keychain
// token store, plus the one shared client built from them. The rules — wire
// format, merges, retries, token rotation — are all in MorseKit, where the
// harness checks them; this file is only the network and the Keychain.

import Foundation
import Security

extension AccountClient {
    /// The app's one client: one URLSession, tokens in the Keychain.
    static let shared = AccountClient(transport: URLSessionAccountTransport(),
                                      tokenStore: KeychainAccountTokenStore())
}

/// `AccountTransport` over URLSession, configured like LeaderboardClient's.
struct URLSessionAccountTransport: AccountTransport {
    /// The accounts Worker (its README is the API contract).
    static let baseURL = URL(string: "https://amt-accounts.n9ho-amt.workers.dev")!
    /// Short: sync runs in the background and simply tries again later.
    static let timeout: TimeInterval = 10

    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = Self.timeout
        config.timeoutIntervalForResource = Self.timeout * 2
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
    }

    func send(_ request: AccountRequest) async throws -> AccountResponse {
        var url = Self.baseURL.appendingPathComponent(request.path)
        if !request.query.isEmpty, var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            parts.queryItems = request.query.keys.sorted().map { URLQueryItem(name: $0, value: request.query[$0]) }
            url = parts.url ?? url
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        urlRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = request.body {
            urlRequest.httpBody = body
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let bearer = request.bearer {
            urlRequest.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: urlRequest)
        let http = response as? HTTPURLResponse
        return AccountResponse(status: http?.statusCode ?? 0, body: data,
                               retryAfter: http?.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0) })
    }
}

/// `AccountTokenStore` in the Keychain: one generic-password item holding the
/// pair as JSON, so the access and refresh tokens are always written
/// together. This-device-only, so a backup restored elsewhere carries no
/// tokens; available after first unlock, so a background sync can read it.
struct KeychainAccountTokenStore: AccountTokenStore {
    private static let service = "com.justinrogers.MorseTrainer.account"
    private static let account = "tokens"

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: Self.service,
         kSecAttrAccount as String: Self.account,
         // The iOS-style keychain on the Mac too (Catalyst).
         kSecUseDataProtectionKeychain as String: true]
    }

    func load() -> AccountTokens? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(AccountTokens.self, from: data)
    }

    func save(_ tokens: AccountTokens) throws {
        let data = try JSONEncoder().encode(tokens)
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(baseQuery as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            let add = baseQuery.merging(update) { _, new in new }
            status = SecItemAdd(add as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw AccountError.storage }
    }

    func clear() {
        _ = SecItemDelete(baseQuery as CFDictionary)
    }
}
