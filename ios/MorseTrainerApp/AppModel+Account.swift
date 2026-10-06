// AppModel+Account.swift
// Account sync (MorseKit/AccountSync.swift): sign in to an Another Morse
// Trainer account with an emailed link, then send every finished session to
// it. The service keeps the stats that follow the learner between devices,
// and that apps the learner allows (Carrier Wave, for one) read from it.
//
// Consent: nothing leaves the device until the user signs in — the sign-in
// is the opt-in — and the "Sync sessions to my account" switch, on once
// signed in, pauses it without signing out. Sign out removes this device
// from the account (the service forgets its tokens) and empties the queue.
//
// What goes up: `AccountSessionUpload`, the record the service hands back
// to readers, one per finished session, queued in `accountQueue` and sent in
// batches — at the end of a session, on the app coming to the foreground
// (at most every five minutes), from the Settings button, and once at
// sign-in with the history the device already holds. A session finished
// offline waits in the queue; the service deduplicates by id, so a lost
// reply is sent again without harm.
//
// Nothing here blocks the UI: the poll and the upload are tasks that update
// the published state when they land.

import Foundation
import UIKit

/// Where a sign-in stands, for Settings › Leaderboard & Buddy › Account.
enum AccountSignInState: Equatable {
    /// Nothing in progress (signed in or not — `settings.account` says which).
    case idle
    /// The start request is in flight.
    case sending
    /// The email is out; polling until the user presses Confirm.
    case waiting(email: String)
    /// The last attempt failed, and why.
    case failed(String)
}

/// The last upload's outcome, for the same section.
enum AccountSyncStatus: Equatable {
    case idle
    case uploading
    /// The last attempt failed; the sessions stay queued.
    case problem(String)
}

extension AppModel {
    /// The foreground sync runs at most this often while the queue has
    /// something in it. A session's end syncs at once regardless.
    static let accountSyncInterval: TimeInterval = 5 * 60
    private static let accountQueueKey = "MorseTrainer.accountQueue"

    /// What the service calls this build's platform.
    static var accountPlatform: String {
        #if targetEnvironment(macCatalyst)
        return "macos"
        #else
        return "ios"
        #endif
    }

    // MARK: - Sign-in (Settings › Leaderboard & Buddy › Account)

    /// Ask the service to email `raw` a sign-in link, then poll until the
    /// user presses Confirm. The outcome lands in `accountSignIn`; on
    /// success the account is recorded in the settings and the history goes
    /// up.
    func accountStartSignIn(email raw: String) {
        let email = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.accountLooksLikeEmail(email) else {
            accountSignIn = .failed("Enter the email address of your account.")
            return
        }
        accountPollTask?.cancel()
        accountSignIn = .sending
        let client = account
        let deviceName = UIDevice.current.name
        let platform = Self.accountPlatform
        accountPollTask = Task { [weak self] in
            do {
                let attempt = try await client.startSignIn(email: email, deviceName: deviceName, platform: platform)
                guard let self, !Task.isCancelled else { return }
                self.accountSignIn = .waiting(email: email)
                let identity = try await client.waitForConfirmation(attempt)
                guard !Task.isCancelled else { return }
                self.accountDidSignIn(identity, email: email)
            } catch is CancellationError {
                // Cancel or Sign out: the state was set by whoever cancelled.
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.accountSignIn = .failed(Self.accountMessage(for: error))
            }
        }
    }

    /// Stop waiting for the link. The link itself stays valid until it
    /// expires; pressing Confirm after this does nothing here.
    func accountCancelSignIn() {
        accountPollTask?.cancel()
        accountPollTask = nil
        accountSignIn = .idle
    }

    /// Sign out: tell the service (best effort), forget the tokens, drop
    /// the queue and the account from the settings. The sync switch keeps
    /// its setting for the next sign-in.
    func accountSignOut() {
        accountPollTask?.cancel()
        accountPollTask = nil
        accountSyncTask?.cancel()
        accountSyncTask = nil
        let client = account
        Task { await client.signOut() }
        accountClearLocal()
    }

    private func accountDidSignIn(_ identity: AccountIdentity, email: String) {
        settings.account.accountId = identity.id
        settings.account.callsign = identity.callsign ?? ""
        settings.account.displayName = identity.displayName ?? ""
        settings.account.email = email
        accountSignIn = .idle
        accountSyncStatus = .idle
        // The account starts with what the device already has.
        accountQueue.enqueue(history: history)
        accountSyncNow()
    }

    /// The device is no longer signed in, whoever decided: forget the
    /// account locally. Does not touch the service.
    private func accountClearLocal() {
        let keepSyncing = settings.account.syncSessions
        settings.account = AccountSettings()
        settings.account.syncSessions = keepSyncing
        accountQueue.removeAll()
        accountSignIn = .idle
        accountSyncStatus = .idle
    }

    // MARK: - Sessions

    /// A session just finished: queue it and send what is queued.
    func accountEnqueue(_ record: SessionRecord) {
        guard settings.account.signedIn else { return }
        accountQueue.enqueue(AccountSessionUpload(record))
        accountSyncNow()
    }

    /// The app came to the foreground: send what is queued, if it has been
    /// a while since the last attempt.
    func accountSyncIfDue() {
        guard settings.account.signedIn, settings.account.syncSessions, !accountQueue.isEmpty else { return }
        if let last = accountLastAttempt, Date().timeIntervalSince(last) < Self.accountSyncInterval { return }
        accountSyncNow()
    }

    /// Send the queue, oldest first, one batch at a time, until it is empty
    /// or something fails. One upload at a time: the refresh token rotates,
    /// and two uploads refreshing at once would sign the device out.
    func accountSyncNow() {
        guard settings.account.signedIn, settings.account.syncSessions else { return }
        guard accountSyncTask == nil else { return }
        guard !accountQueue.isEmpty else {
            if case .problem = accountSyncStatus {} else { accountSyncStatus = .idle }
            return
        }
        accountLastAttempt = Date()
        accountSyncStatus = .uploading
        let client = account
        accountSyncTask = Task { [weak self] in
            defer { self?.accountSyncTask = nil }
            var uploaded = 0
            var problem: String?
            while !Task.isCancelled {
                guard let self else { return }
                let batch = self.accountQueue.nextBatch()
                if batch.isEmpty { break }
                do {
                    try await client.uploadSessions(batch)
                    self.accountQueue.acknowledge(batch.map(\.id))
                    uploaded += batch.count
                } catch let error as AccountError where error.isSignedOut {
                    self.accountClearLocal()
                    self.accountSyncStatus = .problem("Your account signed this device out. Sign in again to keep syncing.")
                    return
                } catch is CancellationError {
                    return
                } catch {
                    problem = Self.accountMessage(for: error)
                    break
                }
            }
            guard let self else { return }
            if uploaded > 0 { self.settings.account.lastSyncAt = Date() }
            self.accountSyncStatus = problem.map { .problem($0) } ?? .idle
        }
    }

    // MARK: - Persistence (upload queue)

    func saveAccountQueue() {
        if let data = try? JSONEncoder().encode(accountQueue) {
            UserDefaults.standard.set(data, forKey: Self.accountQueueKey)
        }
    }

    static func loadAccountQueue() -> AccountSyncQueue {
        guard let data = UserDefaults.standard.data(forKey: accountQueueKey),
              let queue = try? JSONDecoder().decode(AccountSyncQueue.self, from: data) else {
            return AccountSyncQueue()
        }
        return queue
    }

    // MARK: - Helpers

    /// Enough of a check to catch a callsign typed in the email field; the
    /// service decides whether the address has an account, and says nothing
    /// either way.
    static func accountLooksLikeEmail(_ s: String) -> Bool {
        guard let at = s.firstIndex(of: "@"), at != s.startIndex, s.index(after: at) != s.endIndex else { return false }
        return !s.contains(" ") && s[s.index(after: at)...].contains(".")
    }

    static func accountMessage(for error: Error) -> String {
        if let e = error as? AccountError { return e.message }
        return error.localizedDescription
    }
}
