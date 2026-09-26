// AppModel+Buddy.swift
// The buddy streak (docs/buddy-streak-design.md, #219, #237): pair with up
// to ten people and keep a streak with each of the days you *both*
// practised. Everything here is
// a thin layer over the leaderboard client — the pairing is keyed by the
// leaderboard identity, and needs the same App Attest — and the cache in
// `settings.buddy`, which is what the home line, Settings and the daily
// reminder read.
//
// Consent: pairing is its own opt-in (the invite or join action). It is not
// gated by the "share scores" switch, and nothing here attests until the
// user has invited or joined: `refreshBuddyStatus` only talks to the server
// while paired or while an invite this device issued is still open, so a
// user who never touched the section never generates an identity.
//
// Days: `today` and `day` are the device's LOCAL calendar day as
// yyyy-mm-dd (`BuddyDay.label`), the same boundary `PracticeStreak` uses.
//
// Nothing here blocks the UI: the report and the refresh are fire-and-forget
// tasks that update the cache when they land; the three actions are async
// for the Settings buttons to await and show a result.

import Foundation

/// A refusal or failure to show inline in Settings › Leaderboard & Buddy › Buddy streak.
struct BuddyProblem: Error {
    let message: String
}

extension AppModel {
    /// A status is refreshed at most this often, silently, from the app
    /// coming to the foreground and from Settings › Leaderboard & Buddy › Buddy streak appearing.
    static let buddyRefreshInterval: TimeInterval = 15 * 60

    /// Today's local day label, for the views and the requests alike.
    var buddyToday: String { BuddyDay.label(for: Date()) }

    /// Why the buddy actions are unavailable right now, or nil. The name is
    /// the leaderboard's; a device that cannot attest (the simulator) can
    /// never pair, and says so in the leaderboard's own words.
    var buddyUnavailableReason: String? {
        guard LeaderboardDisplayName.isValid(settings.leaderboard.displayName) else {
            return "Pick a display name in Settings › Leaderboard & Buddy"
        }
        guard leaderboard.canAttest else { return LeaderboardError.unsupported.message }
        return nil
    }

    private var buddyDisplayName: String {
        LeaderboardDisplayName.normalize(settings.leaderboard.displayName)
    }

    // MARK: - Actions (Settings › Leaderboard & Buddy › Buddy streak)

    /// Invite a buddy: a six-character code, single use, valid 24 hours.
    /// Kept in the cache until it expires or the pairing lands, so leaving
    /// Settings does not spend another of the day's five invites.
    func buddyInvite() async -> Result<BuddyInviteResponse, BuddyProblem> {
        if let reason = buddyUnavailableReason { return .failure(BuddyProblem(message: reason)) }
        do {
            let r = try await leaderboard.buddyInvite(displayName: buddyDisplayName)
            settings.buddy.pendingInviteCode = r.code
            settings.buddy.pendingInviteExpiresAt = r.expiryDate
            return .success(r)
        } catch {
            return .failure(BuddyProblem(message: Self.buddyMessage(for: error)))
        }
    }

    /// Join with a code someone sent. nil on success (the cache now lists
    /// the new buddy); otherwise the reason to show, the server's own where
    /// it refused. `today` goes along so the answer's flags are about this
    /// device's day. An invite of ours stays up: this buddy came through
    /// their code, not ours.
    func buddyJoin(code raw: String) async -> String? {
        if let reason = buddyUnavailableReason { return reason }
        guard let code = BuddyInviteCode.normalize(raw) else {
            return "Invite codes are six letters or digits"
        }
        do {
            let status = try await leaderboard.buddyJoin(displayName: buddyDisplayName, code: code, today: buddyToday)
            applyBuddyStatus(status, fetchedAt: Date(), joinedByMe: true)
            // Joined after today's practice: the day counts from now.
            reportBuddyPracticeDay()
            return nil
        } catch {
            return Self.buddyMessage(for: error)
        }
    }

    /// Leave one buddy. nil on success; they learn of it on their next
    /// refresh, and the other pairings are untouched. An entry with no
    /// pairing id (a one-buddy Worker, or a cache from before #237 not yet
    /// refreshed) goes through the one-buddy route, which ends the only
    /// pairing there is, so the whole cache is cleared.
    func buddyLeave(_ buddy: BuddyEntry) async -> String? {
        if let reason = buddyUnavailableReason { return reason }
        do {
            if let status = try await leaderboard.buddyLeave(buddyId: buddy.id, today: buddyToday) {
                applyBuddyStatus(status, fetchedAt: Date())
            } else {
                clearBuddyCache()
            }
            return nil
        } catch {
            return Self.buddyMessage(for: error)
        }
    }

    // MARK: - Refresh and the practice-day report

    /// Fetch the status in the background, at most every 15 minutes, silently
    /// on failure. Called as the app becomes active and as the Settings
    /// section appears. Nothing happens unless there is something to learn
    /// (`wantsRefresh`): paired, or an invite of ours is open.
    func refreshBuddyStatus(force: Bool = false) {
        guard buddyUnavailableReason == nil else { return }
        let now = Date()
        guard settings.buddy.wantsRefresh(at: now) else { return }
        if !force, let last = settings.buddy.fetchedAt, now.timeIntervalSince(last) < Self.buddyRefreshInterval { return }
        guard buddyRefresh == nil else { return }
        let client = leaderboard
        let today = BuddyDay.label(for: now)
        buddyRefresh = Task { [weak self] in
            let status = try? await client.buddyStatus(today: today)
            guard let self, !Task.isCancelled else { return }   // cancelled by clearBuddyCache: its answer is moot
            self.buddyRefresh = nil
            if let status {
                self.applyBuddyStatus(status, fetchedAt: Date())
                // Paired since the last look, with today's practice already
                // in the personal streak: report it now rather than at the
                // next drill.
                self.reportBuddyPracticeDay()
            }
        }
    }

    /// Tell the server this device practised today, once per local day,
    /// when paired. Called from `markPracticedToday` on every practice
    /// event, so a report the network dropped is retried by the next one;
    /// a day already reported, or no pairing, costs nothing and sends
    /// nothing. Never blocks: the cache updates when the answer lands.
    func reportBuddyPracticeDay() {
        guard settings.buddy.paired, buddyUnavailableReason == nil else { return }
        let now = Date()
        let today = BuddyDay.label(for: now)
        guard settings.buddy.lastReportedDay != today else { return }
        // Only a day the personal streak has actually marked.
        guard let last = streak.lastPracticeDay, Calendar.current.isDate(last, inSameDayAs: now) else { return }
        guard buddyDayReport == nil else { return }
        let client = leaderboard
        buddyDayReport = Task { [weak self] in
            let status = try? await client.buddyReportDay(today, today: today)
            guard let self, !Task.isCancelled else { return }
            self.buddyDayReport = nil
            if let status { self.applyBuddyStatus(status, fetchedAt: Date()) }
        }
    }

    /// Take a server answer into the cache and re-arm the reminder, whose
    /// buddy sentence names the fetch time. A day the server already shows
    /// as practised needs no report, whoever sent it. `joinedByMe` marks
    /// this device's own join, so an invite it has out stays up.
    func applyBuddyStatus(_ status: BuddyListStatus, fetchedAt: Date, joinedByMe: Bool = false) {
        var cache = BuddyStatusCache(status: status, fetchedAt: fetchedAt, previous: settings.buddy, joinedByMe: joinedByMe)
        if status.practisedToday, status.today == BuddyDay.label(for: fetchedAt) {
            cache.lastReportedDay = status.today
        }
        settings.buddy = cache
        refreshReminderIfStreakChanged()
    }

    /// Forget everything cached: after a one-buddy leave, and after "Delete
    /// my scores" (the server dropped every pairing with the rest). The reminder
    /// loses its buddy sentence with it.
    func clearBuddyCache() {
        buddyRefresh?.cancel()
        buddyRefresh = nil
        buddyDayReport?.cancel()
        buddyDayReport = nil
        settings.buddy = BuddyStatusCache()
        refreshReminderIfStreakChanged()
    }

    private static func buddyMessage(for error: Error) -> String {
        if let e = error as? LeaderboardError { return e.message }
        return error.localizedDescription
    }
}
