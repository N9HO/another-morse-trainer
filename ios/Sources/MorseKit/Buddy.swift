// Buddy.swift
// Buddy streaks (docs/buddy-streak-design.md, issue #219): the pure rules
// and wire types the app applies before talking to the leaderboard server,
// which keeps the pairing. No networking here: the client actor lives in
// the app target. Everything in this file is data the Android port mirrors
// name for name, so the two apps label days, normalise codes and word the
// buddy line the same way.
//
// The contract is the leaderboard repository's README ("buddy" routes) and
// its `src/buddy.ts`. Where a rule below copies one of those, the comment
// says which.

import Foundation

// MARK: - Days

/// A practice day is the user's LOCAL calendar day as `yyyy-mm-dd` — the
/// same day boundary `PracticeStreak` uses — sent as a label, not a
/// timestamp, so time zones and midnight work the way the personal streak
/// already does: your day is your day (design §"How it works", 3).
public enum BuddyDay {
    /// `date`'s calendar day in `calendar`, as the server's `yyyy-mm-dd`.
    /// Always Gregorian digits (the server's `DAY_RE`), whatever the user's
    /// calendar preference.
    public static func label(for date: Date, calendar: Calendar = .current) -> String {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let c = gregorian.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Whether `s` is a well-formed label: the server's `isDay` — ten
    /// characters, digits and dashes in the right places, and a real date.
    public static func isLabel(_ s: String) -> Bool {
        let parts = s.split(separator: "-", omittingEmptySubsequences: false)
        guard s.count == 10, parts.count == 3,
              parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              parts.allSatisfy({ $0.allSatisfy(\.isASCIIDigit) }),
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]) else { return false }
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = TimeZone(identifier: "UTC") ?? .current
        let comps = DateComponents(calendar: gregorian, year: y, month: m, day: d)
        return comps.isValidDate(in: gregorian)
    }
}

private extension Character {
    var isASCIIDigit: Bool {
        guard let a = asciiValue else { return false }
        return a >= 0x30 && a <= 0x39
    }
}

// MARK: - Invite codes

/// The server's invite-code rule (`src/buddy.ts` INVITE_ALPHABET,
/// `normalizeInviteCode`), applied locally so the Join field can say what
/// is wrong before the server refuses it.
public enum BuddyInviteCode {
    /// Six characters from an alphabet without look-alikes: no 0/O, 1/I/L.
    public static let alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    public static let length = 6

    /// Trim, uppercase and drop spaces and dashes — what someone reads out or
    /// pastes with a separator — then the code, or nil if what is left is
    /// not six characters of the alphabet.
    public static func normalize(_ raw: String) -> String? {
        let code = raw.uppercased().filter { !$0.isWhitespace && $0 != "-" }
        guard code.count == length, code.allSatisfy({ alphabet.contains($0) }) else { return nil }
        return code
    }

    /// What the Join field keeps as the user types: uppercase, separators
    /// dropped, cut at six characters. Characters outside the alphabet are
    /// kept so the field does not silently eat a typo; `normalize` refuses
    /// them when the button is pressed.
    public static func typed(_ raw: String) -> String {
        String(raw.uppercased().filter { !$0.isWhitespace && $0 != "-" }.prefix(length))
    }
}

// MARK: - Wire shapes

/// The server's `status` answer, returned by join, day and status alike
/// (README: `{ paired, buddy?, streak, myStreak, practisedToday, today }`).
public struct BuddyStatus: Codable, Sendable, Equatable {
    public struct Peer: Codable, Sendable, Equatable {
        public var displayName: String
        public var practisedToday: Bool

        public init(displayName: String, practisedToday: Bool) {
            self.displayName = displayName
            self.practisedToday = practisedToday
        }
    }

    public var paired: Bool
    public var buddy: Peer?
    /// Consecutive days, ending today or yesterday, on which BOTH practised.
    public var streak: Int
    /// The user's own streak by the same rule, from the server's records.
    public var myStreak: Int
    public var practisedToday: Bool
    /// The `today` the app sent, echoed: the day the flags are about.
    public var today: String

    public init(paired: Bool, buddy: Peer?, streak: Int, myStreak: Int, practisedToday: Bool, today: String) {
        self.paired = paired
        self.buddy = buddy
        self.streak = streak
        self.myStreak = myStreak
        self.practisedToday = practisedToday
        self.today = today
    }
}

public struct BuddyInviteRequest: Codable, Sendable {
    public var challenge: String
    public var attestation: LeaderboardAttestation
    public var displayName: String

    public init(challenge: String, attestation: LeaderboardAttestation, displayName: String) {
        self.challenge = challenge
        self.attestation = attestation
        self.displayName = displayName
    }
}

/// `200` from `/v1/buddy/invite`. `expiresAt` is milliseconds since the
/// epoch, like every server timestamp.
public struct BuddyInviteResponse: Codable, Sendable, Equatable {
    public var code: String
    public var expiresAt: Double

    public var expiryDate: Date { Date(timeIntervalSince1970: expiresAt / 1000) }
}

public struct BuddyJoinRequest: Codable, Sendable {
    public var challenge: String
    public var attestation: LeaderboardAttestation
    public var displayName: String
    public var code: String
    public var today: String

    public init(challenge: String, attestation: LeaderboardAttestation, displayName: String, code: String, today: String) {
        self.challenge = challenge
        self.attestation = attestation
        self.displayName = displayName
        self.code = code
        self.today = today
    }
}

public struct BuddyDayRequest: Codable, Sendable {
    public var challenge: String
    public var attestation: LeaderboardAttestation
    public var day: String
    public var today: String

    public init(challenge: String, attestation: LeaderboardAttestation, day: String, today: String) {
        self.challenge = challenge
        self.attestation = attestation
        self.day = day
        self.today = today
    }
}

public struct BuddyStatusRequest: Codable, Sendable {
    public var challenge: String
    public var attestation: LeaderboardAttestation
    public var today: String

    public init(challenge: String, attestation: LeaderboardAttestation, today: String) {
        self.challenge = challenge
        self.attestation = attestation
        self.today = today
    }
}

public struct BuddyLeaveRequest: Codable, Sendable {
    public var challenge: String
    public var attestation: LeaderboardAttestation

    public init(challenge: String, attestation: LeaderboardAttestation) {
        self.challenge = challenge
        self.attestation = attestation
    }
}

// MARK: - The cached status

/// The last status the server gave, kept on the device so the home screen,
/// Settings and the daily reminder can say something without a network
/// round trip (design §"The nudge": the reminder is built from the last
/// fetch and says how old it is). Every field has a default and decoding is
/// tolerant, so an older or newer app's saved settings never fail to load.
public struct BuddyStatusCache: Codable, Sendable, Equatable {
    public var paired: Bool = false
    /// The buddy's leaderboard display name; "" when not paired.
    public var buddyName: String = ""
    /// Whether the buddy had practised on `today`, as of `fetchedAt`.
    public var buddyPractisedToday: Bool = false
    /// The shared streak, as of `fetchedAt`.
    public var streak: Int = 0
    /// The user's own streak by the server's records.
    public var myStreak: Int = 0
    /// The local day label the flags are about; "" before the first fetch.
    public var today: String = ""
    /// When the server answered; nil before the first fetch.
    public var fetchedAt: Date?
    /// The last local day this device reported as practised (`/v1/buddy/day`),
    /// so a day is reported once and a failed report is retried.
    public var lastReportedDay: String?
    /// An invite this device issued and has not seen used: shown again in
    /// Settings until it expires, so leaving the screen does not cost one of
    /// the day's five invites.
    public var pendingInviteCode: String?
    public var pendingInviteExpiresAt: Date?

    public init() {}

    /// The cache after a server answer at `fetchedAt`. The pending invite
    /// and the reported day are kept unless the answer makes them moot.
    public init(status: BuddyStatus, fetchedAt: Date, previous: BuddyStatusCache = BuddyStatusCache()) {
        paired = status.paired
        buddyName = status.buddy?.displayName ?? ""
        buddyPractisedToday = status.buddy?.practisedToday ?? false
        streak = status.streak
        myStreak = status.myStreak
        today = status.today
        self.fetchedAt = fetchedAt
        lastReportedDay = previous.lastReportedDay
        if !status.paired {
            pendingInviteCode = previous.pendingInviteCode
            pendingInviteExpiresAt = previous.pendingInviteExpiresAt
        }
    }

    enum CodingKeys: String, CodingKey {
        case paired, buddyName, buddyPractisedToday, streak, myStreak, today, fetchedAt
        case lastReportedDay, pendingInviteCode, pendingInviteExpiresAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        var s = BuddyStatusCache()
        s.paired = try c.decodeIfPresent(Bool.self, forKey: .paired) ?? s.paired
        s.buddyName = try c.decodeIfPresent(String.self, forKey: .buddyName) ?? s.buddyName
        s.buddyPractisedToday = try c.decodeIfPresent(Bool.self, forKey: .buddyPractisedToday) ?? s.buddyPractisedToday
        s.streak = try c.decodeIfPresent(Int.self, forKey: .streak) ?? s.streak
        s.myStreak = try c.decodeIfPresent(Int.self, forKey: .myStreak) ?? s.myStreak
        s.today = try c.decodeIfPresent(String.self, forKey: .today) ?? s.today
        s.fetchedAt = try c.decodeIfPresent(Date.self, forKey: .fetchedAt)
        s.lastReportedDay = try c.decodeIfPresent(String.self, forKey: .lastReportedDay)
        s.pendingInviteCode = try c.decodeIfPresent(String.self, forKey: .pendingInviteCode)
        s.pendingInviteExpiresAt = try c.decodeIfPresent(Date.self, forKey: .pendingInviteExpiresAt)
        self = s
    }

    /// Whether the buddy is known to have practised on `today` (the current
    /// local day label). A cache from an earlier day cannot vouch for today,
    /// so it reads as "not yet" until the next refresh.
    public func buddyPractised(on today: String) -> Bool {
        paired && self.today == today && buddyPractisedToday
    }

    /// The invite to show, if one is pending and still valid at `now`.
    public func pendingInvite(at now: Date) -> (code: String, expiresAt: Date)? {
        guard !paired, let code = pendingInviteCode, let expiresAt = pendingInviteExpiresAt,
              expiresAt > now else { return nil }
        return (code, expiresAt)
    }

    /// Whether a status refresh has anything to learn: paired (the buddy's
    /// day and the streak move), or an invite is out (the pairing lands
    /// when they join). Otherwise a refresh would only attest for nothing.
    public func wantsRefresh(at now: Date) -> Bool {
        paired || pendingInvite(at: now) != nil
    }

    /// "12-day buddy streak", "1-day buddy streak", or "no buddy streak yet".
    public var streakLabel: String {
        streak > 0 ? "\(streak)-day buddy streak" : "no buddy streak yet"
    }

    /// The home screen's line under the streak badge, or nil when not
    /// paired: "W1AW practised today · 12-day buddy streak" /
    /// "W1AW hasn't practised yet today · 12-day buddy streak".
    public func homeLine(today: String) -> String? {
        guard paired else { return nil }
        let did = buddyPractised(on: today) ? "practised today" : "hasn't practised yet today"
        return "\(buddyName) \(did) · \(streakLabel)"
    }

    /// Settings › Buddy streak's status line: "Paired with W1AW · 12-day
    /// buddy streak · W1AW has practised today" / "… hasn't practised yet
    /// today", or the not-paired prompt.
    public func settingsLine(today: String) -> String {
        guard paired else { return Self.notPairedLine }
        let did = buddyPractised(on: today) ? "has practised today" : "hasn't practised yet today"
        return "Paired with \(buddyName) · \(streakLabel) · \(buddyName) \(did)"
    }

    public static let notPairedLine = "Invite a buddy, or join with a code they send you"

    /// The sentence the daily reminder gains when the buddy had not practised
    /// at the last fetch — "W1AW hasn't practised yet today (as of 6:10 pm)" —
    /// or nil when there is nothing to nudge about. `asOf` is the fetch time
    /// already formatted for the user's locale; the sentence says it because
    /// the notification's text is baked in when it is scheduled and can be
    /// hours stale by the time it fires (design §"The nudge"). A cache from
    /// an earlier day says nothing: "as of 6:10 pm" would be yesterday's.
    public func reminderSentence(today: String, asOf: String) -> String? {
        guard paired, fetchedAt != nil, self.today == today, !buddyPractisedToday else { return nil }
        return "\(buddyName) hasn't practised yet today (as of \(asOf))"
    }
}
