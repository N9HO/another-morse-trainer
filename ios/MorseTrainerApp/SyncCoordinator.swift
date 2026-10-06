// SyncCoordinator.swift
// The optional account, as the app sees it: sign-in by emailed link, the
// Account & Sync screen's state, and the hooks every local write calls. The
// rules — outbox, drain order, pull, first sign-in, backoff, merges — are
// MorseKit's (AccountSyncEngine.swift), where the harness checks them; the
// network and Keychain halves are AccountServices.swift. This file is the
// glue: UserDefaults for the non-secret state, `AppModel` as the host, and
// the small amount of state the screen draws, on the main actor.
//
// Sync is invisible. Every hook records locally first and only queues; the
// engine runs off the main actor, never blocks a screen and never shows a
// transient failure. A session finished offline is pushed at the next sync:
// on launch and on a return to the foreground (at most once every five
// minutes), a few seconds after a local change, and on Sync now.

import Foundation
import UIKit

@MainActor
final class SyncCoordinator: ObservableObject {
    static let shared = SyncCoordinator()

    /// Posted after a newer First Four or Operating Procedure copy from the
    /// account is saved, so a screen holding the older one reloads.
    nonisolated static let stateApplied = Notification.Name("MorseTrainer.accountStateApplied")

    /// Where the sign-in stands, for the signed-out screen.
    enum SignInStep: Equatable {
        case idle
        /// `verify/start` is out.
        case sending
        /// The link is sent; polling every two seconds until it is confirmed.
        case waiting(email: String)
        /// The link's 15 minutes ran out.
        case expired
        /// One plain line: a refused address, a network failure, a 4xx.
        case problem(String)
    }

    @Published private(set) var signInStep: SignInStep = .idle
    /// The persisted state as last read: account, last synced, the banner.
    @Published private(set) var state: AccountSyncState
    @Published private(set) var devices: [AccountDevice] = []
    @Published private(set) var loadingDevices = false
    @Published private(set) var syncing = false

    var isSignedIn: Bool { state.isSignedIn }
    var lastSynced: Date? { state.lastSyncedAt.map(AccountSync.date(epochMilliseconds:)) }

    nonisolated private static let storageKey = "MorseTrainer.account"

    let store: AccountSyncStore
    private let host: AppSyncHost
    private let engine: AccountSyncEngine
    private var attached = false
    private var pollTask: Task<Void, Never>?
    private var soonTask: Task<Void, Never>?
    /// Holds foreground syncs at least five minutes apart.
    private var throttle = SyncThrottle()
    /// The last value seen for each synced key, so a save that leaves the
    /// synced part unchanged (a per-character stat) stamps nothing.
    private var noted: [SyncStateKey: JSONValue] = [:]
    /// While a reset runs, saves are remembered but not stamped.
    private var stampingSuspended = false

    private init() {
        let loaded = SyncCoordinator.loadState()
        let store = AccountSyncStore(state: loaded) { SyncCoordinator.persist($0) }
        let host = AppSyncHost()
        self.store = store
        self.host = host
        self.state = loaded
        self.engine = AccountSyncEngine(client: .shared, store: store, host: host, didChange: {
            Task { @MainActor in SyncCoordinator.shared.reload() }
        })
    }

    nonisolated private static func loadState() -> AccountSyncState {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let state = try? JSONDecoder().decode(AccountSyncState.self, from: data) else { return AccountSyncState() }
        return state
    }

    nonisolated private static func persist(_ state: AccountSyncState) {
        if let data = try? JSONEncoder().encode(state) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }

    private func reload() {
        let fresh = store.state
        if fresh != state { state = fresh }
    }

    // MARK: - Lifecycle

    /// Called once at the end of `AppModel.init`: the host can read the
    /// model from now on, and a signed-in device pulls. Launch is the first
    /// foreground, so the `.active` that follows it is held back.
    func attach(_ model: AppModel) {
        host.model = model
        for key in SyncStateKey.allCases { noted[key] = model.syncStateValue(key) }
        attached = true
        appBecameActive()
    }

    /// The scene came to the foreground: back off from scratch and pull — at
    /// most once every five minutes (`SyncThrottle`, fixture
    /// `merge.foregroundThrottle`). A held-back return does nothing, not even
    /// reset the backoff, which would cancel a scheduled retry.
    func appBecameActive() {
        guard isSignedIn,
              throttle.admit(.foreground, nowMs: AccountSync.epochMilliseconds(Date())) else { return }
        let engine = engine
        Task {
            await engine.resetBackoff()
            await engine.sync()
        }
    }

    /// A local change was queued: push it shortly, folding a burst of
    /// changes (a session end saves several things) into one sync.
    ///
    /// Only the three-second wait is cancellable. The sync runs in a task of
    /// its own, so a change landing mid-sync never cancels the requests in
    /// flight (which would count a failure and schedule a needless retry);
    /// the engine leaves a running sync alone and goes round once more,
    /// as Android's and desktop's `requestSync` do with their `again` flag.
    private func syncSoon() {
        guard isSignedIn else { return }
        soonTask?.cancel()
        let engine = engine
        soonTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            Task { await engine.sync(refreshState: false) }
        }
    }

    // MARK: - Hooks (local first; these only record and queue)

    /// A session was added to the history.
    func noteSession(_ record: SessionRecord) {
        store.update { $0.noteSession(record) }
        syncSoon()
    }

    /// A day counted as practice (`markPracticedToday`).
    func notePracticeDay(_ date: Date = Date()) {
        let day = ActivityLedger.dayKey(for: date)
        let queued = store.update { state -> Bool in
            let before = state.dayOutbox.count
            state.notePracticeDay(day)
            return state.dayOutbox.count != before
        }
        if queued { syncSoon() }
    }

    /// A synced store was saved with `value` as its synced part. Stamped and
    /// queued only when that part changed.
    func noteState(_ key: SyncStateKey, value: JSONValue) {
        guard attached, noted[key] != value else { return }
        noted[key] = value
        guard !stampingSuspended else { return }
        let stamp = AccountSync.epochMilliseconds(Date())
        store.update { $0.noteStateChange(key, now: stamp) }
        syncSoon()
    }

    /// A reset is local: it never stamps or pushes. `AppModel.resetProgress`
    /// runs between these two; the next ordinary save stamps as usual.
    func suspendStamping() { stampingSuspended = true }

    func resumeStamping() {
        stampingSuspended = false
        guard let model = host.model else { return }
        for key in SyncStateKey.allCases { noted[key] = model.syncStateValue(key) }
    }

    /// First Four's or Operating Procedure's "Start over": remember the
    /// fresh value as seen, so the save that follows does not stamp it.
    func noteReset(_ key: SyncStateKey, value: JSONValue) {
        noted[key] = value
    }

    /// After the account's value was applied: remember it as seen, so the
    /// save it caused is not taken for a change of ours.
    fileprivate func noteApplied(_ key: SyncStateKey, value: JSONValue) {
        noted[key] = value
        if key == .firstFour || key == .operatingProcedure {
            NotificationCenter.default.post(name: Self.stateApplied, object: nil)
        }
    }

    // MARK: - Sign-in

    /// Email a sign-in link, then poll every two seconds until the link is
    /// confirmed on any device, it expires, or the user cancels.
    func sendLink(email raw: String) {
        let email = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard AccountSync.isPlausibleEmail(email) else {
            signInStep = .problem("Enter a valid email address.")
            return
        }
        pollTask?.cancel()
        signInStep = .sending
        let deviceName = Self.deviceName
        let platform = Self.platform
        pollTask = Task {
            do {
                try await AccountClient.shared.startSignIn(email: email, deviceName: deviceName, platform: platform)
            } catch {
                if !Task.isCancelled { signInStep = .problem(Self.signInProblem(error)) }
                return
            }
            guard !Task.isCancelled else { return }
            signInStep = .waiting(email: email)
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard !Task.isCancelled else { return }
                switch await AccountClient.shared.poll() {
                case .pending:
                    continue
                case .signedIn(_, let profile):
                    // Confirmed: the tokens are stored, so finish even if
                    // Cancel was tapped while this poll was out.
                    signInStep = .idle
                    await finishSignIn(profile)
                    return
                case .expired:
                    signInStep = .expired
                    return
                case .error(let error):
                    // No reply or a 5xx: keep polling. Anything else is final.
                    if error.retryAction == .backoff { continue }
                    signInStep = .problem(Self.signInProblem(error))
                    return
                }
            }
        }
    }

    /// Stop polling (Cancel). The link, if confirmed later, signs nothing in.
    func cancelSignIn() {
        pollTask?.cancel()
        pollTask = nil
        signInStep = .idle
        Task { await AccountClient.shared.cancelSignIn() }
    }

    private func finishSignIn(_ profile: AccountProfile) async {
        syncing = true
        await engine.signedIn(profile)
        syncing = false
        reload()
        await refreshDevices()
    }

    nonisolated private static func signInProblem(_ error: Error) -> String {
        switch (error as? AccountError) ?? .network {
        case .network:
            return "Couldn't reach the server. Check your connection and try again."
        case .server(429, _, _, _):
            return "Too many sign-in emails for now. Try again later."
        case .server(400, _, _, _):
            return "That email address wasn't accepted."
        default:
            return "Signing in didn't work. Try again."
        }
    }

    // MARK: - Signed in

    func syncNow() async {
        guard isSignedIn else { return }
        syncing = true
        await engine.resetBackoff()
        await engine.sync()
        syncing = false
        reload()
    }

    /// Sign out: the server forgets this device, this device forgets the
    /// tokens, the cursor and the queue. Every local record stays.
    func signOut() async {
        cancelSignIn()
        soonTask?.cancel()
        await engine.signOut()
        devices = []
        reload()
    }

    /// Delete the account and its synced data on the server; this device's
    /// local data stays. Nil on success, else one line to show.
    func deleteAccount() async -> String? {
        soonTask?.cancel()
        let error = await engine.deleteAccount()
        reload()
        if error == nil { devices = [] }
        return error.map(Self.accountProblem)
    }

    /// `PATCH /v1/me` with the fields as typed. Nil on success, else the
    /// local rule's line or the server's own message.
    func updateProfile(callsign rawCallsign: String, displayName rawName: String) async -> String? {
        let callsign: String?
        switch AccountSync.profileCallsign(rawCallsign) {
        case .valid(let value): callsign = value
        case .invalid(let problem): return problem
        }
        let name: String?
        switch AccountSync.profileDisplayName(rawName) {
        case .valid(let value): name = value
        case .invalid(let problem): return problem
        }
        let result = await engine.updateProfile(callsign: callsign, displayName: name)
        reload()
        switch result {
        case .success: return nil
        case .failure(let error): return Self.accountProblem(error)
        }
    }

    func refreshDevices() async {
        guard isSignedIn else { devices = []; return }
        loadingDevices = true
        if case .success(let list) = await engine.devices() { devices = list }
        loadingDevices = false
        reload()
    }

    /// Sign one device out. This device's own row signs out here entirely.
    func signOut(device: AccountDevice) async -> String? {
        if device.current {
            await signOut()
            return nil
        }
        if let error = await engine.revokeDevice(id: device.id) {
            reload()
            return Self.accountProblem(error)
        }
        devices.removeAll { $0.id == device.id }
        return nil
    }

    func dismissSignedOutBanner() {
        store.update { $0.signedOutByServer = false }
        reload()
    }

    nonisolated private static func accountProblem(_ error: AccountError) -> String {
        switch error {
        case .network:
            return "Couldn't reach the server. Check your connection and try again."
        case .signedOut:
            return "You were signed out. Sign in again to keep syncing."
        case .server(_, _, _, let message?) where !message.isEmpty:
            return message
        default:
            return "That didn't work. Try again later."
        }
    }

    // MARK: - This device

    /// `ios`, `ipados` or `macos` (Mac Catalyst, or the iPad app on a Mac).
    static var platform: String {
        #if targetEnvironment(macCatalyst)
        return "macos"
        #else
        if ProcessInfo.processInfo.isiOSAppOnMac { return "macos" }
        return UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "ios"
        #endif
    }

    /// The device's own name, as the sign-in email and the Devices list show it.
    static var deviceName: String {
        String(UIDevice.current.name.prefix(64))
    }
}

/// The engine's view of the app: `AppModel`, on the main actor.
final class AppSyncHost: AccountSyncHost, @unchecked Sendable {
    /// Set once, on the main actor, by `SyncCoordinator.attach`; read only
    /// on the main actor. That confinement is what the unchecked Sendable
    /// rests on.
    @MainActor weak var model: AppModel?

    func localSessions() async -> [SessionRecord] {
        await MainActor.run { self.model?.syncLocalSessions ?? [] }
    }

    func localLedger() async -> [String: Int] {
        await MainActor.run { self.model?.syncLocalLedger ?? [:] }
    }

    func stateValue(_ key: SyncStateKey) async -> JSONValue? {
        await MainActor.run { self.model?.syncSavedStateValue(key) }
    }

    func mergeSessions(_ records: [SessionRecord]) async -> Bool {
        await MainActor.run { self.model?.mergeSyncedSessions(records) ?? false }
    }

    func adoptTotals(_ totals: SyncLifetimeTotals) async {
        await MainActor.run { () -> Void in self.model?.adoptSyncedTotals(totals) }
    }

    func adoptLedger(_ days: [String: Int]) async {
        await MainActor.run { () -> Void in self.model?.adoptSyncedDays(days) }
    }

    func adoptStreak(_ streak: SyncServerStats.Streak) async {
        await MainActor.run { () -> Void in self.model?.adoptSyncedStreak(streak) }
    }

    func applyState(_ key: SyncStateKey, value: JSONValue) async -> Bool {
        await MainActor.run { () -> Bool in
            guard let model = self.model, model.applySyncedState(key, value: value) else { return false }
            SyncCoordinator.shared.noteApplied(key, value: model.syncStateValue(key))
            return true
        }
    }
}
