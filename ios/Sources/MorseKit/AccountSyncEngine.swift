// AccountSyncEngine.swift
// The optional account's sync engine: what is persisted between launches
// (`AccountSyncState`), the lock that keeps it (`AccountSyncStore`), what the
// engine needs from the app (`AccountSyncHost`), and the engine itself — the
// outbox drain, the pull, the first sign-in and the backoff. The rules are
// `fixtures/sync-wire.json`'s and the accounts README's (sections 5, 7, 8);
// the wire codec and merge functions it calls are in AccountSync.swift.
//
// No UI and no UserDefaults here: the app's `SyncCoordinator` builds the
// engine with a Keychain-backed `AccountClient`, a UserDefaults-backed store
// and a host that reads and writes `AppModel`. The harness builds it with a
// fake transport and an in-memory host, so every rule below runs there.
//
// Sync never gates a local write and never shows a transient failure: the
// app records locally first, the hooks only queue, and a failed sync backs
// off and tries again.

import Foundation

// MARK: - Persisted state

/// Everything the sync keeps between launches that is not a secret (the
/// tokens are in the Keychain, behind `AccountTokenStore`).
public struct AccountSyncState: Codable, Sendable, Equatable {
    /// The signed-in account, as `/v1/me` describes it.
    public struct Account: Codable, Sendable, Equatable {
        public var id: String
        public var email: String?
        public var callsign: String?
        public var displayName: String?

        public init(id: String, email: String?, callsign: String?, displayName: String?) {
            self.id = id
            self.email = email
            self.callsign = callsign
            self.displayName = displayName
        }

        public init(_ profile: AccountProfile) {
            self.init(id: profile.id, email: profile.email, callsign: profile.callsign,
                      displayName: profile.displayName)
        }
    }

    /// Nil while signed out.
    public var account: Account?
    /// The pull cursor (`since`); nil reads as 0.
    public var cursor: Int?
    /// When a sync last finished, epoch milliseconds.
    public var lastSyncedAt: Int?
    /// Finished sessions waiting to be pushed, as wire records (history rows
    /// age out at a hundred; the outbox keeps up to a thousand).
    public var sessionOutbox: SyncOutbox<SyncSession>
    /// Days whose own figure changed since the last push, oldest first.
    public var dayOutbox: [String]
    /// State keys changed since the last push.
    public var stateOutbox: [String]
    /// This device's OWN seconds per local day (`merge.deviceDays`): the only
    /// figures ever pushed. Grows with every local session or practice-day
    /// mark, signed in or not, and survives sign-out.
    public var ownDays: [String: Int]
    /// Whether `ownDays` was seeded from the local ledger (once, at the first
    /// sign-in this device ever made). Survives sign-out.
    public var ownDaysSeeded: Bool
    /// Each synced state key's last change, epoch milliseconds.
    public var stateUpdatedAt: [String: Int]
    /// The first-sign-in sequence (push everything, then the snapshot) has
    /// not finished yet; the next sync runs it again.
    public var firstSyncPending: Bool
    /// The server signed this device out (its refresh token was refused):
    /// the Account section says so until the user signs in or dismisses it.
    public var signedOutByServer: Bool

    public init() {
        account = nil
        cursor = nil
        lastSyncedAt = nil
        sessionOutbox = SyncOutbox()
        dayOutbox = []
        stateOutbox = []
        ownDays = [:]
        ownDaysSeeded = false
        stateUpdatedAt = [:]
        firstSyncPending = false
        signedOutByServer = false
    }

    enum CodingKeys: String, CodingKey {
        case account, cursor, lastSyncedAt, sessionOutbox, dayOutbox, stateOutbox
        case ownDays, ownDaysSeeded, stateUpdatedAt, firstSyncPending, signedOutByServer
    }

    /// Lenient: a field this build does not know yet, or one a later build
    /// dropped, never costs the rest. A bad outbox starts empty.
    public init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        account = try? c.decodeIfPresent(Account.self, forKey: .account)
        cursor = try? c.decodeIfPresent(Int.self, forKey: .cursor)
        lastSyncedAt = try? c.decodeIfPresent(Int.self, forKey: .lastSyncedAt)
        if let outbox = try? c.decodeIfPresent(SyncOutbox<SyncSession>.self, forKey: .sessionOutbox) {
            sessionOutbox = outbox
        }
        dayOutbox = (try? c.decodeIfPresent([String].self, forKey: .dayOutbox)) ?? []
        stateOutbox = (try? c.decodeIfPresent([String].self, forKey: .stateOutbox)) ?? []
        ownDays = (try? c.decodeIfPresent([String: Int].self, forKey: .ownDays)) ?? [:]
        ownDaysSeeded = (try? c.decodeIfPresent(Bool.self, forKey: .ownDaysSeeded)) ?? false
        stateUpdatedAt = (try? c.decodeIfPresent([String: Int].self, forKey: .stateUpdatedAt)) ?? [:]
        firstSyncPending = (try? c.decodeIfPresent(Bool.self, forKey: .firstSyncPending)) ?? false
        signedOutByServer = (try? c.decodeIfPresent(Bool.self, forKey: .signedOutByServer)) ?? false
    }

    public var isSignedIn: Bool { account != nil }

    // MARK: Hooks (each local write calls one; the network never gates it)

    /// Add `seconds` to this device's own figure for `day`, the same whole
    /// seconds the displayed ledger records. Capped like the ledger.
    public mutating func recordOwn(day: String, seconds: Int) {
        ownDays[day, default: 0] += max(0, seconds)
        if ownDays.count > ActivityLedger.capDays {
            for key in ownDays.keys.sorted().prefix(ownDays.count - ActivityLedger.capDays) {
                ownDays.removeValue(forKey: key)
            }
        }
    }

    /// Queue `day` for the next days push (once).
    public mutating func enqueueDay(_ day: String) {
        if !dayOutbox.contains(day) { dayOutbox.append(day) }
    }

    /// A finished session: its seconds join the own record on the local day
    /// the ledger keys it by, and, while signed in, the record and its day
    /// are queued.
    public mutating func noteSession(_ record: SessionRecord, calendar: Calendar = .current) {
        let day = ActivityLedger.dayKey(for: record.date, calendar: calendar)
        recordOwn(day: day, seconds: Int((record.durationSeconds ?? 0).rounded()))
        guard isSignedIn else { return }
        sessionOutbox.enqueue(SyncSession(record: record))
        enqueueDay(day)
    }

    /// A practice-day mark with no session of its own (a Daily Dit guess, an
    /// answered drill): the day joins the own record with +0 and, while
    /// signed in, is queued — only when it is new, since an unchanged figure
    /// is already on the server or already queued.
    public mutating func notePracticeDay(_ day: String) {
        guard ownDays[day] == nil else { return }
        recordOwn(day: day, seconds: 0)
        if isSignedIn { enqueueDay(day) }
    }

    /// A save changed a synced key's wire value. Only while signed in is it
    /// stamped `now` and queued (fixture `merge.state`); signed out, nothing
    /// is recorded, and the first sign-in sends the key at 0 if it was never
    /// stamped.
    public mutating func noteStateChange(_ key: SyncStateKey, now: Int) {
        guard isSignedIn else { return }
        stateUpdatedAt[key.rawValue] = now
        if !stateOutbox.contains(key.rawValue) { stateOutbox.append(key.rawValue) }
    }

    // MARK: Sign-in and sign-out

    /// Seed the own record from the local ledger, the first time this device
    /// ever signs in (before any adoption could have touched the ledger).
    public mutating func seedOwnDays(fromLedger ledger: [String: Int]) {
        guard !ownDaysSeeded else { return }
        for (day, seconds) in ledger { ownDays[day] = max(ownDays[day] ?? 0, seconds) }
        ownDaysSeeded = true
    }

    /// Signed in: queue everything this device has — every history row
    /// oldest first, every own day, and each state key that has a value on
    /// this device (`savedKeys`) — for the first sync, which pushes them and
    /// then takes the snapshot. A key whose store was never saved here (a
    /// fresh install's defaults, empty story bookmarks) is not queued: the
    /// snapshot fills it, so a new install never pushes its defaults over the
    /// account's progress. A saved key never stamped goes out at 0, so any
    /// value the account already has wins and this device only fills a key
    /// the account lacks; a key stamped earlier keeps its stamp.
    public mutating func beginFirstSync(account: Account, ledger: [String: Int], history: [SessionRecord],
                                        savedKeys: Set<SyncStateKey>) {
        self.account = account
        signedOutByServer = false
        cursor = nil
        lastSyncedAt = nil
        seedOwnDays(fromLedger: ledger)
        sessionOutbox = SyncOutbox()
        for record in history.sorted(by: { $0.date < $1.date }) {
            sessionOutbox.enqueue(SyncSession(record: record))
        }
        dayOutbox = ownDays.keys.sorted()
        stateOutbox = []
        for key in SyncStateKey.allCases where savedKeys.contains(key) {
            if stateUpdatedAt[key.rawValue] == nil { stateUpdatedAt[key.rawValue] = 0 }
            stateOutbox.append(key.rawValue)
        }
        firstSyncPending = true
    }

    /// Signed out — by the user, by deleting the account, or by the server
    /// (`byServer`). The cursor, the outbox and the last-synced time go;
    /// local history, the ledger, the own record, its seeded flag and the
    /// state stamps stay.
    public mutating func signOut(byServer: Bool) {
        account = nil
        cursor = nil
        lastSyncedAt = nil
        sessionOutbox = SyncOutbox()
        dayOutbox = []
        stateOutbox = []
        firstSyncPending = false
        signedOutByServer = byServer
    }

    /// After a days push answered (2xx, or a 4xx that resending cannot fix):
    /// the days sent leave the queue — except one whose own figure grew while
    /// the request was out, which goes again.
    public mutating func dropSentDays(_ sent: [String: Int]) {
        let own = ownDays
        dayOutbox.removeAll { day in
            guard let seconds = sent[day] else { return false }
            return seconds == (own[day] ?? 0)
        }
    }

    /// After a state push answered: the keys sent leave the queue — except
    /// one changed again (restamped) while the request was out.
    public mutating func dropSentState(_ sentStamps: [String: Int]) {
        let stamps = stateUpdatedAt
        stateOutbox.removeAll { key in
            guard let stamp = sentStamps[key] else { return false }
            return stamp == stamps[key]
        }
    }

    /// `POST /v1/sync/days`'s body for these queued days: this device's own
    /// figure for each, oldest first. Never the displayed ledger.
    public func dayBatch(for days: [String]) -> SyncDayBatch {
        SyncDayBatch(days: Set(days).sorted().map { SyncDay(day: $0, seconds: ownDays[$0] ?? 0) })
    }
}

// MARK: - Store

/// The persisted state behind a lock, written through on every change. The
/// hooks run on the main actor and the engine on its own; both go through
/// here, synchronously, so the order of local writes is the order queued.
public final class AccountSyncStore: @unchecked Sendable {
    private let lock = NSLock()
    private var current: AccountSyncState
    private let persist: @Sendable (AccountSyncState) -> Void

    public init(state: AccountSyncState, persist: @escaping @Sendable (AccountSyncState) -> Void) {
        current = state
        self.persist = persist
    }

    public var state: AccountSyncState { lock.withLock { current } }

    /// Change the state; it is persisted (under the lock, so two writers are
    /// stored in order) only when something changed.
    @discardableResult
    public func update<T>(_ body: (inout AccountSyncState) -> T) -> T {
        lock.withLock {
            var next = current
            let result = body(&next)
            if next != current {
                current = next
                persist(next)
            }
            return result
        }
    }
}

// MARK: - Host

/// What the engine reads from and writes to the app. Each call is the app's
/// own idiom (on iOS, a hop to the main actor and `AppModel`).
public protocol AccountSyncHost: Sendable {
    /// Every local session row, any order.
    func localSessions() async -> [SessionRecord]
    /// The displayed activity ledger.
    func localLedger() async -> [String: Int]
    /// A synced key's current local value, in its wire shape; nil when its
    /// store was never saved on this device (or holds nothing, like empty
    /// story bookmarks), so there is nothing to push.
    func stateValue(_ key: SyncStateKey) async -> JSONValue?
    /// Merge pulled or snapshot rows into local history and save it. False
    /// when saving failed, so the pull cursor does not move past them.
    func mergeSessions(_ records: [SessionRecord]) async -> Bool
    /// Replace the lifetime counters with the server's.
    func adoptTotals(_ totals: SyncLifetimeTotals) async
    /// Adopt the server's summed figures into the DISPLAYED ledger.
    func adoptLedger(_ days: [String: Int]) async
    /// Adopt the account's practice streak (`AccountSync.adoptStreak`).
    func adoptStreak(_ streak: SyncServerStats.Streak) async
    /// Apply a winning server value to the live store, without stamping it as
    /// a local change. False when the value is not a shape this app reads,
    /// so the local value (and its stamp) stand.
    func applyState(_ key: SyncStateKey, value: JSONValue) async -> Bool
}

// MARK: - Engine

/// How one sync ended.
public enum AccountSyncOutcome: Sendable, Equatable {
    /// Everything queued went out and the pull (or snapshot) landed.
    case done
    /// Nobody is signed in, or the account changed under the sync.
    case notSignedIn
    /// Another sync was running; it will go round once more.
    case busy
    /// A transient failure: the engine backs off and tries again.
    case backoff
    /// The server refused a request that resending cannot fix.
    case dropped
    /// The server signed this device out.
    case signedOut
}

public actor AccountSyncEngine {
    private let client: AccountClient
    private let store: AccountSyncStore
    private let host: any AccountSyncHost
    private let now: @Sendable () -> Date
    private let calendar: Calendar
    private let autoRetry: Bool
    private let didChange: @Sendable () -> Void

    /// Consecutive failed syncs: the next retry waits
    /// `AccountSync.backoffSeconds(failures)`. Reset on success and on
    /// foreground (`resetBackoff`).
    public private(set) var failures = 0
    private var running = false
    private var rerun = false
    private var rerunRefreshesState = false
    private var retryTask: Task<Void, Never>?

    /// The server takes at most 400 days per push.
    public static let dayBatchSize = 400

    /// `autoRetry` false leaves retries to the caller (the harness counts
    /// `failures` instead of waiting them out). `didChange` is told whenever
    /// the stored state may have changed, from the engine's executor.
    public init(client: AccountClient, store: AccountSyncStore, host: any AccountSyncHost,
                autoRetry: Bool = true, calendar: Calendar = .current,
                now: @escaping @Sendable () -> Date = { Date() },
                didChange: @escaping @Sendable () -> Void = {}) {
        self.client = client
        self.store = store
        self.host = host
        self.autoRetry = autoRetry
        self.calendar = calendar
        self.now = now
        self.didChange = didChange
    }

    /// The delay before the next retry, while backing off.
    public var nextRetrySeconds: Int? { failures == 0 ? nil : AccountSync.backoffSeconds(failures) }

    /// Back to the first retry interval, as on a return to the foreground.
    public func resetBackoff() {
        failures = 0
        retryTask?.cancel()
        retryTask = nil
    }

    private var nowMs: Int { AccountSync.epochMilliseconds(now()) }
    private var today: String { ActivityLedger.dayKey(for: now(), calendar: calendar) }

    // MARK: Sign-in, sign-out, account

    /// The sign-in poll answered 201 and the tokens are stored: queue this
    /// device's whole record and run the first sync (push, then snapshot).
    @discardableResult
    public func signedIn(_ profile: AccountProfile) async -> AccountSyncOutcome {
        let ledger = await host.localLedger()
        let history = await host.localSessions()
        var saved: Set<SyncStateKey> = []
        for key in SyncStateKey.allCases {
            if await host.stateValue(key) != nil { saved.insert(key) }
        }
        store.update {
            $0.beginFirstSync(account: .init(profile), ledger: ledger, history: history, savedKeys: saved)
        }
        resetBackoff()
        didChange()
        return await sync()
    }

    /// `POST /v1/auth/logout`, then forget the tokens, the cursor and the
    /// outbox. Every local record stays.
    public func signOut() async {
        await client.logout()
        store.update { $0.signOut(byServer: false) }
        resetBackoff()
        didChange()
    }

    /// `DELETE /v1/me`. On success this device is signed out as for
    /// `signOut`; local data stays. Returns the failure, if any.
    public func deleteAccount() async -> AccountError? {
        do {
            try await client.deleteAccount()
            store.update { $0.signOut(byServer: false) }
            resetBackoff()
            didChange()
            return nil
        } catch {
            return failed(error)
        }
    }

    /// `PATCH /v1/me`, keeping the stored account in step.
    public func updateProfile(callsign: String?, displayName: String?) async -> Result<AccountProfile, AccountError> {
        do {
            let profile = try await client.updateProfile(callsign: callsign, displayName: displayName)
            store.update {
                guard $0.account?.id == profile.id else { return }
                $0.account?.callsign = profile.callsign
                $0.account?.displayName = profile.displayName
                if let email = profile.email { $0.account?.email = email }
            }
            didChange()
            return .success(profile)
        } catch {
            return .failure(failed(error))
        }
    }

    public func devices() async -> Result<[AccountDevice], AccountError> {
        do { return .success(try await client.devices()) } catch { return .failure(failed(error)) }
    }

    public func revokeDevice(id: String) async -> AccountError? {
        do {
            try await client.revokeDevice(id: id)
            return nil
        } catch {
            return failed(error)
        }
    }

    /// An account call's error, with a refused refresh (the device was
    /// signed out by the server) handled the one way it always is.
    private func failed(_ error: Error) -> AccountError {
        let error = (error as? AccountError) ?? .network
        if error == .signedOut { forcedSignOut() }
        return error
    }

    private func forcedSignOut() {
        guard store.state.isSignedIn else { return }
        store.update { $0.signOut(byServer: true) }
        resetBackoff()
        didChange()
    }

    // MARK: Sync

    /// Push what is queued, then pull (or, after a sign-in, take the
    /// snapshot). A call while one runs makes that one go round again.
    ///
    /// `refreshState` also sends all five state keys, not only the queued
    /// ones: state comes back only in a state reply (or the snapshot), so
    /// this is how a device that changed nothing learns that another device
    /// moved on. Idempotent — the server keeps the newer of each. The app
    /// passes false for the sync that follows a local change.
    @discardableResult
    public func sync(refreshState: Bool = true) async -> AccountSyncOutcome {
        guard store.state.isSignedIn else { return .notSignedIn }
        if running {
            rerun = true
            rerunRefreshesState = rerunRefreshesState || refreshState
            return .busy
        }
        running = true
        var outcome: AccountSyncOutcome
        var refresh = refreshState
        repeat {
            rerun = false
            outcome = await runOnce(refreshState: refresh)
            refresh = rerunRefreshesState
            rerunRefreshesState = false
            // `.notSignedIn` here means the account changed under this run
            // (signed out, maybe into another account): a sync asked for in
            // the meantime — the new account's first sync, which `signedIn`
            // was told is `.busy` — goes round now rather than waiting for
            // the next trigger. Signed out, that round returns at once.
        } while rerun && (outcome == .done || outcome == .notSignedIn)
        running = false
        switch outcome {
        case .done:
            resetBackoff()
        case .backoff:
            failures += 1
            scheduleRetry()
        case .signedOut:
            forcedSignOut()
        case .notSignedIn, .busy, .dropped:
            break
        }
        didChange()
        return outcome
    }

    private func scheduleRetry() {
        retryTask?.cancel()
        retryTask = nil
        guard autoRetry else { return }
        let delay = UInt64(AccountSync.backoffSeconds(failures))
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            guard !Task.isCancelled else { return }
            _ = await self?.sync(refreshState: true)
        }
    }

    /// Why a sync stopped that is not the server's doing.
    private enum LocalStop: Error {
        /// Signed out (or into another account) while a request was out.
        case accountChanged
        /// Local history could not be saved: the cursor must not move.
        case notSaved
    }

    private func runOnce(refreshState: Bool) async -> AccountSyncOutcome {
        guard let accountId = store.state.account?.id else { return .notSignedIn }
        do {
            try await drain(accountId, refreshState: refreshState)
            if store.state.firstSyncPending {
                try await takeSnapshot(accountId)
            } else {
                try await pull(accountId)
            }
            let stamp = nowMs
            store.update { if $0.account?.id == accountId { $0.lastSyncedAt = stamp } }
            return .done
        } catch let stop as LocalStop {
            return stop == .accountChanged ? .notSignedIn : .backoff
        } catch let error as AccountError {
            if error == .signedOut { return .signedOut }
            switch error.retryAction {
            case .done: return .done
            case .drop: return .dropped
            case .backoff, .refresh: return .backoff
            }
        } catch {
            return .backoff
        }
    }

    private func ensureAccount(_ id: String) throws {
        guard store.state.account?.id == id else { throw LocalStop.accountChanged }
    }

    /// Whether an error means "this request can never succeed": the batch is
    /// dropped from the outbox rather than resent forever. A refused refresh
    /// is not that — it signs the device out.
    private static func isPermanent(_ error: Error) -> Bool {
        guard let error = error as? AccountError, error != .signedOut else { return false }
        return error.retryAction == .drop
    }

    /// Sessions (oldest first, 200 at a time), then days, then state.
    private func drain(_ accountId: String, refreshState: Bool) async throws {
        // Sessions.
        while true {
            let batch = store.state.sessionOutbox.nextBatch()
            guard !batch.isEmpty else { break }
            let reply: SyncPushReply
            do {
                reply = try await client.pushSessions(batch)
            } catch let error where Self.isPermanent(error) {
                try ensureAccount(accountId)
                let ids = Set(batch.map(\.id))
                store.update { state in state.sessionOutbox.remove(ids: ids) }
                continue
            }
            try ensureAccount(accountId)
            let before = store.state.sessionOutbox.entries.count
            store.update { $0.sessionOutbox.apply(reply) }
            if let stats = reply.stats { await host.adoptTotals(SyncLifetimeTotals(stats: stats)) }
            // A reply that names none of the batch would send it forever.
            if store.state.sessionOutbox.entries.count == before { break }
        }

        // Days: this device's own figures for the days queued.
        let queuedDays = store.state.dayOutbox
        var start = 0
        while start < queuedDays.count {
            let chunk = Array(queuedDays[start..<min(start + Self.dayBatchSize, queuedDays.count)])
            start += Self.dayBatchSize
            let batch = store.state.dayBatch(for: chunk)
            let sent = Dictionary(batch.days.map { ($0.day, $0.seconds) }, uniquingKeysWith: { a, _ in a })
            let reply: SyncDaysReply
            do {
                reply = try await client.pushDays(batch)
            } catch let error where Self.isPermanent(error) {
                try ensureAccount(accountId)
                store.update { $0.dropSentDays(sent) }
                continue
            }
            try ensureAccount(accountId)
            store.update { $0.dropSentDays(sent) }
            await host.adoptLedger(reply.days)
        }

        // State: each queued key's current value and stamp (all five on a
        // refresh). A key with no value on this device is never sent; one
        // with a value but no stamp yet goes out at 0, so it never outranks
        // a value another device synced.
        let queuedKeys = store.state.stateOutbox.compactMap(SyncStateKey.init(rawValue:))
        let candidates = refreshState ? SyncStateKey.allCases : queuedKeys
        var values: [SyncStateKey: JSONValue] = [:]
        for key in candidates {
            if let value = await host.stateValue(key) { values[key] = value }
        }
        let unsendable = Set(candidates.filter { values[$0] == nil }.map(\.rawValue))
        if !unsendable.isEmpty {
            store.update { state in state.stateOutbox.removeAll { key in unsendable.contains(key) } }
        }
        let keys = candidates.filter { values[$0] != nil }
        if !keys.isEmpty {
            let stamps = store.state.stateUpdatedAt
            var entries: [String: SyncStateEntry] = [:]
            for key in keys {
                if let value = values[key] {
                    entries[key.rawValue] = SyncStateEntry(value: value, updatedAt: stamps[key.rawValue] ?? 0)
                }
            }
            let sentStamps = entries.mapValues(\.updatedAt)
            let reply: SyncStateReply
            do {
                reply = try await client.putState(entries)
            } catch let error where Self.isPermanent(error) {
                try ensureAccount(accountId)
                store.update { $0.dropSentState(sentStamps) }
                return
            }
            try ensureAccount(accountId)
            store.update { $0.dropSentState(sentStamps) }
            await adoptState(reply.entries)
        }
    }

    /// Apply each entry strictly newer than the local stamp
    /// (`AccountSync.mergeState`'s rule) and take its stamp.
    private func adoptState(_ entries: [String: SyncStateEntry]) async {
        for key in SyncStateKey.allCases {
            guard let theirs = entries[key.rawValue] else { continue }
            let ours = store.state.stateUpdatedAt[key.rawValue]
            guard theirs.updatedAt > (ours ?? Int.min) else { continue }
            if await host.applyState(key, value: theirs.value) {
                store.update { state in
                    // Only if no local change landed while it was applied.
                    if state.stateUpdatedAt[key.rawValue] == ours {
                        state.stateUpdatedAt[key.rawValue] = theirs.updatedAt
                    }
                }
            }
        }
    }

    /// `GET /v1/sync/sessions` from the cursor until `hasMore` is false. Each
    /// page is merged and saved, THEN the cursor moves.
    private func pull(_ accountId: String) async throws {
        var cursor = store.state.cursor ?? 0
        while true {
            let page = try await client.pullSessions(since: cursor)
            try ensureAccount(accountId)
            let records = page.sessions.compactMap(\.record)
            if !records.isEmpty {
                guard await host.mergeSessions(records) else { throw LocalStop.notSaved }
            }
            let next = max(cursor, page.nextSince)
            store.update { if $0.account?.id == accountId { $0.cursor = next } }
            if let stats = page.stats { await host.adoptTotals(SyncLifetimeTotals(stats: stats)) }
            // Stop on the last page, or on one that does not move the cursor.
            guard page.hasMore, next > cursor else { break }
            cursor = next
        }
        // The pull carries no days or stats: another device's practice days,
        // totals and streak arrive with the stats body, read after every pull.
        let stats = try await client.stats(today: today)
        try ensureAccount(accountId)
        await adoptFullStats(stats)
    }

    /// Totals and bests, the summed day ledger, then the streak.
    private func adoptFullStats(_ stats: SyncServerStats) async {
        await host.adoptTotals(SyncLifetimeTotals(stats: stats))
        if let days = stats.activityDays { await host.adoptLedger(days) }
        if let streak = stats.streak { await host.adoptStreak(streak) }
    }

    /// The end of the first sync: everything the account holds, adopted.
    private func takeSnapshot(_ accountId: String) async throws {
        let snapshot = try await client.snapshot(today: today)
        try ensureAccount(accountId)
        let records = snapshot.sessions.compactMap(\.record)
        if !records.isEmpty {
            guard await host.mergeSessions(records) else { throw LocalStop.notSaved }
        }
        await host.adoptTotals(SyncLifetimeTotals(stats: snapshot.stats))
        await host.adoptLedger(snapshot.days)
        if let streak = snapshot.stats.streak { await host.adoptStreak(streak) }
        await adoptState(snapshot.state)
        store.update {
            guard $0.account?.id == accountId else { return }
            $0.cursor = snapshot.seq
            $0.firstSyncPending = false
        }
    }
}

// MARK: - Rules the engine and the Account screen share

extension AccountSync {
    /// A callsign as `PATCH /v1/me` takes it: trimmed and uppercased, 3–16
    /// of A–Z, 0–9 and /. Empty clears it (nil).
    public static func profileCallsign(_ raw: String) -> ProfileField {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if value.isEmpty { return .valid(nil) }
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/")
        guard (3...16).contains(value.count), value.allSatisfy({ allowed.contains($0) }) else {
            return .invalid("A callsign is 3–16 letters, digits or /.")
        }
        return .valid(value)
    }

    /// A display name as `PATCH /v1/me` takes it: trimmed, 2–24 printable
    /// characters. Empty clears it (nil).
    public static func profileDisplayName(_ raw: String) -> ProfileField {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty { return .valid(nil) }
        let printable = value.unicodeScalars.allSatisfy {
            !CharacterSet.controlCharacters.contains($0) && !CharacterSet.illegalCharacters.contains($0)
        }
        guard (2...24).contains(value.count), printable else {
            return .invalid("A name is 2–24 printable characters.")
        }
        return .valid(value)
    }

    /// One profile field, checked: the value to send (nil clears it), or
    /// the one line that says what is wrong.
    public enum ProfileField: Sendable, Equatable {
        case valid(String?)
        case invalid(String)
    }

    /// Enough of an email address to be worth sending a link to: one @ with
    /// something either side, a dot in the domain, no spaces, at most 254
    /// characters. The server is the judge; this only catches typos.
    public static func isPlausibleEmail(_ raw: String) -> Bool {
        let email = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = email.split(separator: "@", omittingEmptySubsequences: false)
        guard email.count <= 254, parts.count == 2, !parts[0].isEmpty,
              !email.contains(where: { $0.isWhitespace }) else { return false }
        let domain = parts[1]
        return domain.contains(".") && !domain.hasPrefix(".") && !domain.hasSuffix(".")
    }
}
