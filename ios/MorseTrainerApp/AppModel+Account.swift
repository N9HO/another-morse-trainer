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
// What goes up: one `AccountSessionUpload` per finished session through
// `POST /v1/sync/sessions`, and the practice ledger's figure for each day a
// session touched through `POST /v1/sync/days` — both from the outbox in
// `accountQueue`, sent at the end of a session, on the app coming to the
// foreground (at most every five minutes), from the Settings button, and
// once at sign-in with the history and ledger the device already holds
// (the README's merge rule 1: push everything local before anything else).
// A session finished offline waits in the outbox; both routes are
// idempotent, so a lost reply is sent again without harm.
//
// Not yet here (README §7, rules 2–5): pulling other devices' sessions,
// adopting the server's aggregates in place of the local lifetime counters,
// the restore snapshot, and progress state. This is the push half.
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
    /// The service took the batch but refused some sessions, with its reason
    /// for the first. They stay in local history and leave the outbox.
    case rejected(count: Int, reason: String)
    /// The last attempt failed; the sessions stay queued.
    case problem(String)
}

extension AppModel {
    /// The foreground sync runs at most this often while the queue has
    /// something in it. A session's end syncs at once regardless.
    static let accountSyncInterval: TimeInterval = 5 * 60
    private static let accountQueueKey = "MorseTrainer.accountQueue"

    /// What the service calls this build's platform (README: `ios`,
    /// `ipados`, `macos`, …).
    static var accountPlatform: String {
        #if targetEnvironment(macCatalyst)
        return "macos"
        #else
        return UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "ios"
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
        // The service's form of the address (trimmed, lowercased) when the
        // grant shows it; what the user typed otherwise.
        settings.account.email = identity.email ?? email
        accountSignIn = .idle
        accountSyncStatus = .idle
        // The account starts with what the device already has: the history
        // and the ledger (merge rule 1).
        accountQueue.enqueue(history: history)
        accountQueue.enqueue(ledger: activity)
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

    /// A session just finished: queue it, and the day it counted toward,
    /// then send what is queued. Called after the ledger has the day.
    func accountEnqueue(_ record: SessionRecord) {
        guard settings.account.signedIn else { return }
        accountQueue.enqueue(AccountSessionUpload(record))
        accountQueue.enqueue(day: ActivityLedger.dayKey(for: record.date))
        accountSyncNow()
    }

    /// Today counted as practice without a session ending — a Daily Dit
    /// guess, a passage heard — so the account's ledger should have the day
    /// too (README §7: a day with 0 seconds is still a practice day). Sent
    /// with the ledger's figure for today, or 0 if it has none; a session
    /// ending later raises it, since the server keeps the larger number.
    func accountEnqueueToday() {
        guard settings.account.signedIn else { return }
        accountQueue.enqueue(day: ActivityLedger.dayKey(for: Date()))
        accountSyncNow()
    }

    /// The app came to the foreground: send what is queued, if it has been
    /// a while since the last attempt.
    func accountSyncIfDue() {
        guard settings.account.signedIn, settings.account.syncSessions, !accountQueue.isEmpty else { return }
        if let last = accountLastAttempt, Date().timeIntervalSince(last) < Self.accountSyncInterval { return }
        accountSyncNow()
    }

    /// Send the outbox — sessions first, oldest first, a batch at a time,
    /// then the days — until it is empty or something fails. One upload at
    /// a time: the refresh token rotates, and two uploads refreshing at once
    /// would sign the device out.
    func accountSyncNow() {
        guard settings.account.signedIn, settings.account.syncSessions else { return }
        guard accountSyncTask == nil else { return }
        guard !accountQueue.isEmpty else {
            if case .uploading = accountSyncStatus { accountSyncStatus = .idle }
            return
        }
        accountLastAttempt = Date()
        accountSyncStatus = .uploading
        let client = account
        accountSyncTask = Task { [weak self] in
            defer { self?.accountSyncTask = nil }
            var sent = 0
            var rejected = 0
            var firstReason: String?
            var problem: String?
            do {
                while !Task.isCancelled {
                    guard let self else { return }
                    let batch = self.accountQueue.nextBatch()
                    if batch.isEmpty { break }
                    let reply = try await client.pushSessions(batch)
                    // Settled ids leave the outbox: accepted, already there,
                    // or refused for good (they stay in local history). An id
                    // the reply did not name is not settled; a reply that
                    // names none would loop, so that batch is dropped too.
                    let settled = reply.settled.isEmpty ? batch.map(\.id) : reply.settled
                    self.accountQueue.acknowledge(settled)
                    sent += reply.accepted.count + reply.skipped.count
                    rejected += reply.rejected.count
                    if firstReason == nil { firstReason = reply.rejected.first?.reason }
                }
                while !Task.isCancelled {
                    guard let self else { return }
                    let days = self.accountQueue.nextDays(from: self.activity)
                    if days.isEmpty { break }
                    let reply = try await client.pushDays(days)
                    // A day the service refused (malformed, or too far
                    // ahead) will not improve by resending.
                    self.accountQueue.acknowledge(days: days.map(\.day))
                    sent += days.count - reply.rejected.count
                }
            } catch let error as AccountError where error.isSignedOut {
                guard let self else { return }
                self.accountClearLocal()
                self.accountSyncStatus = .problem("Your account signed this device out. Sign in again to keep syncing.")
                return
            } catch is CancellationError {
                return
            } catch {
                problem = Self.accountMessage(for: error)
            }
            guard let self else { return }
            if sent > 0 { self.settings.account.lastSyncAt = Date() }
            if let problem {
                self.accountSyncStatus = .problem(problem)
            } else if rejected > 0 {
                self.accountSyncStatus = .rejected(count: rejected, reason: firstReason ?? "")
            } else {
                self.accountSyncStatus = .idle
            }
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
