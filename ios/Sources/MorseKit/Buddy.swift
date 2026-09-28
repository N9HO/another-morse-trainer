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

/// One buddy: a pairing as the server lists it (`/v2/buddy/*`, #237). `id` is
/// the pairing's opaque id, the same on both sides and never an identity; it
/// is what `/v2/buddy/leave` takes. It is "" for a buddy learned from a
/// one-buddy (v1) answer or from a cache saved before #237, and such an entry
/// is left through the v1 route, which ends the one pairing it knows.
public struct BuddyEntry: Codable, Sendable, Equatable {
    public var id: String
    /// The buddy's leaderboard display name, never blank.
    public var displayName: String
    /// Whether the buddy had practised on the status's `today`.
    public var practisedToday: Bool
    /// Consecutive days, ending today or yesterday, on which you BOTH practised.
    public var streak: Int

    public init(id: String, displayName: String, practisedToday: Bool, streak: Int) {
        self.id = id
        self.displayName = displayName
        self.practisedToday = practisedToday
        self.streak = streak
    }

    enum CodingKeys: String, CodingKey { case id, displayName, practisedToday, streak }

    /// Tolerant: a mistyped field takes its zero value and a negative streak
    /// reads as zero, but an entry with no name is not an entry (it throws,
    /// and the list drops it), so the app never shows a nameless buddy.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let name = ((try? c.decodeIfPresent(String.self, forKey: .displayName)) ?? nil)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !name.isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: .displayName, in: c, debugDescription: "a buddy has a name")
        }
        id = ((try? c.decodeIfPresent(String.self, forKey: .id)) ?? nil) ?? ""
        displayName = name
        practisedToday = ((try? c.decodeIfPresent(Bool.self, forKey: .practisedToday)) ?? nil) ?? false
        streak = max(0, ((try? c.decodeIfPresent(Int.self, forKey: .streak)) ?? nil) ?? 0)
    }
}

/// What every buddy route that answers with a status returns, in either of
/// the server's two shapes (the leaderboard README):
///
/// - v2 (#237): `{ buddies: [{ id, displayName, practisedToday, streak }],
///   maxBuddies, myStreak, practisedToday, today, joined? }`.
/// - v1 (#219, a Worker that predates #237): `{ paired, buddy?: {
///   displayName, practisedToday }, streak, myStreak, practisedToday, today }`,
///   read as a list of at most one buddy with no id and `maxBuddies` 1.
///
/// The rules are pinned by `fixtures/buddy-list.json` ("parseCases"), which
/// the Android port reads too.
public struct BuddyListStatus: Decodable, Sendable, Equatable {
    public var buddies: [BuddyEntry]
    /// How many buddies the server allows (10 today); 1 from a v1 answer.
    public var maxBuddies: Int
    /// The user's own streak by the server's records.
    public var myStreak: Int
    public var practisedToday: Bool
    /// The `today` the app sent, echoed: the day the flags are about; "" if
    /// the answer did not carry a well-formed one.
    public var today: String
    /// On a v2 join: the id of the pairing just made; "" otherwise.
    public var joined: String

    public init(buddies: [BuddyEntry], maxBuddies: Int, myStreak: Int, practisedToday: Bool, today: String, joined: String = "") {
        self.buddies = buddies
        self.maxBuddies = max(1, maxBuddies, buddies.count)
        self.myStreak = myStreak
        self.practisedToday = practisedToday
        self.today = today
        self.joined = joined
    }

    enum CodingKeys: String, CodingKey {
        case buddies, maxBuddies, myStreak, practisedToday, today, joined
        case paired, buddy, streak
    }

    private struct LossyEntry: Decodable {
        let entry: BuddyEntry?
        init(from decoder: Decoder) throws { entry = try? BuddyEntry(from: decoder) }
    }

    private struct V1Peer: Decodable {
        let displayName: String?
        let practisedToday: Bool?
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func opt<T: Decodable>(_ type: T.Type, _ key: CodingKeys) -> T? {
            (try? c.decodeIfPresent(type, forKey: key)) ?? nil
        }
        let list: [BuddyEntry]
        let max: Int
        if c.contains(.buddies) {
            list = (opt([LossyEntry].self, .buddies) ?? []).compactMap(\.entry)
            max = opt(Int.self, .maxBuddies) ?? 1
        } else if c.contains(.paired) {
            let peer = opt(V1Peer.self, .buddy)
            let name = peer?.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if opt(Bool.self, .paired) == true, !name.isEmpty {
                list = [BuddyEntry(id: "", displayName: name, practisedToday: peer?.practisedToday ?? false,
                                   streak: Swift.max(0, opt(Int.self, .streak) ?? 0))]
            } else {
                list = []
            }
            max = 1
        } else {
            throw DecodingError.dataCorruptedError(forKey: .buddies, in: c, debugDescription: "not a buddy status")
        }
        let today = opt(String.self, .today) ?? ""
        self.init(buddies: list, maxBuddies: max,
                  myStreak: Swift.max(0, opt(Int.self, .myStreak) ?? 0),
                  practisedToday: opt(Bool.self, .practisedToday) ?? false,
                  today: BuddyDay.isLabel(today) ? today : "",
                  joined: opt(String.self, .joined) ?? "")
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

/// `200` from `/v1/buddy/invite` and `/v2/buddy/invite`. `expiresAt` is
/// milliseconds since the epoch, like every server timestamp.
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

/// `/v1/buddy/leave`: ends every pairing (what a one-buddy app means by leave).
public struct BuddyLeaveRequest: Codable, Sendable {
    public var challenge: String
    public var attestation: LeaderboardAttestation

    public init(challenge: String, attestation: LeaderboardAttestation) {
        self.challenge = challenge
        self.attestation = attestation
    }
}

/// `/v2/buddy/leave`: ends the one pairing `buddyId` names.
public struct BuddyLeaveOneRequest: Codable, Sendable {
    public var challenge: String
    public var attestation: LeaderboardAttestation
    public var buddyId: String
    public var today: String

    public init(challenge: String, attestation: LeaderboardAttestation, buddyId: String, today: String) {
        self.challenge = challenge
        self.attestation = attestation
        self.buddyId = buddyId
        self.today = today
    }
}

// MARK: - The digest (what the home line and the reminder say)

/// The buddy list boiled down to what the one-line surfaces say: who has not
/// practised yet today, and the best streak. Pinned by
/// `fixtures/buddy-list.json` ("digestCases"), which the Android port reads.
public struct BuddyDigest: Sendable, Equatable {
    /// At most this many waiting buddies are named; the rest are "N more".
    public static let namesShown = 2

    public var count: Int
    public var allPractised: Bool
    /// The first `namesShown` buddies who have not practised today, in list order.
    public var waitingNames: [String]
    /// How many more are waiting beyond `waitingNames`.
    public var waitingMore: Int
    public var bestStreak: Int

    /// A buddy counts as practised only when the status is for `today`
    /// (`statusDay == today`) and says so: an older status cannot vouch.
    public init(buddies: [BuddyEntry], statusDay: String, today: String) {
        let current = !statusDay.isEmpty && statusDay == today
        let waiting = buddies.filter { !(current && $0.practisedToday) }.map(\.displayName)
        count = buddies.count
        allPractised = !buddies.isEmpty && waiting.isEmpty
        waitingNames = Array(waiting.prefix(Self.namesShown))
        waitingMore = waiting.count - waitingNames.count
        bestStreak = buddies.map(\.streak).max() ?? 0
    }

    /// "W1AW hasn't practiced yet today", "W1AW and K1ABC haven't …",
    /// "W1AW, K1ABC and 2 more haven't …"; nil when nobody is waiting.
    public var waitingPhrase: String? {
        switch (waitingNames.count, waitingMore) {
        case (0, _): return nil
        case (1, _): return "\(waitingNames[0]) hasn't practiced yet today"
        case (_, 0): return "\(waitingNames[0]) and \(waitingNames[1]) haven't practiced yet today"
        default: return "\(waitingNames[0]), \(waitingNames[1]) and \(waitingMore) more haven't practiced yet today"
        }
    }
}

// MARK: - The cached status

/// The last status the server gave, kept on the device so the home screen,
/// Settings and the daily reminder can say something without a network
/// round trip (design §"The nudge": the reminder is built from the last
/// fetch and says how old it is). Every field has a default and decoding is
/// tolerant, so an older or newer app's saved settings never fail to load,
/// and a cache saved by the one-buddy app (#219) is read as a list of one.
public struct BuddyStatusCache: Codable, Sendable, Equatable {
    /// Every buddy, oldest pairing first, as of `fetchedAt`.
    public var buddies: [BuddyEntry] = []
    /// The server's cap as last seen; 1 until a v2 answer says otherwise.
    public var maxBuddies: Int = 1
    /// The user's own streak by the server's records.
    public var myStreak: Int = 0
    /// The local day label the flags are about; "" before the first fetch.
    public var today: String = ""
    /// When the server answered; nil before the first fetch.
    public var fetchedAt: Date?
    /// The last local day this device reported as practised (`/buddy/day`),
    /// so a day is reported once and a failed report is retried.
    public var lastReportedDay: String?
    /// An invite this device issued and has not seen used: shown again in
    /// Settings until it expires, so leaving the screen does not cost one of
    /// the day's five invites.
    public var pendingInviteCode: String?
    public var pendingInviteExpiresAt: Date?

    public init() {}

    /// Whether there is at least one buddy.
    public var paired: Bool { !buddies.isEmpty }
    /// Whether the list is at the server's cap: no more invites or joins.
    public var isFull: Bool { buddies.count >= maxBuddies }

    /// The cache after a server answer at `fetchedAt`. The reported day is
    /// kept; the pending invite is kept unless the answer makes it moot
    /// (`keepsInvite`). `joinedByMe` is true for this device's own join,
    /// whose new buddy did not come through our invite.
    public init(status: BuddyListStatus, fetchedAt: Date, previous: BuddyStatusCache = BuddyStatusCache(), joinedByMe: Bool = false) {
        buddies = status.buddies
        maxBuddies = status.maxBuddies
        myStreak = status.myStreak
        today = status.today
        self.fetchedAt = fetchedAt
        lastReportedDay = previous.lastReportedDay
        if Self.keepsInvite(previous: previous.buddies, current: status.buddies, maxBuddies: status.maxBuddies,
                            joinedByMe: joinedByMe, joinedId: status.joined) {
            pendingInviteCode = previous.pendingInviteCode
            pendingInviteExpiresAt = previous.pendingInviteExpiresAt
        }
    }

    /// Whether an invite this device issued should stay shown after the list
    /// went from `previous` to `current` (fixtures/buddy-list.json,
    /// "inviteCases"): not once the list is full, and not once a buddy has
    /// appeared that this device did not join itself — that one came through
    /// the invite, which is single use.
    public static func keepsInvite(previous: [BuddyEntry], current: [BuddyEntry], maxBuddies: Int,
                                   joinedByMe: Bool, joinedId: String) -> Bool {
        if current.count >= maxBuddies { return false }
        var newcomers = current.filter { e in
            !previous.contains { p in
                (!e.id.isEmpty && p.id == e.id) || ((p.id.isEmpty || e.id.isEmpty) && p.displayName == e.displayName)
            }
        }
        if joinedByMe, !newcomers.isEmpty {
            if !joinedId.isEmpty, let i = newcomers.firstIndex(where: { $0.id == joinedId }) {
                newcomers.remove(at: i)
            } else if joinedId.isEmpty {
                newcomers.removeFirst()
            }
        }
        return newcomers.isEmpty
    }

    enum CodingKeys: String, CodingKey {
        case buddies, maxBuddies, myStreak, today, fetchedAt
        case lastReportedDay, pendingInviteCode, pendingInviteExpiresAt
        // The one-buddy cache (#219), read once on upgrade and not written again.
        case paired, buddyName, buddyPractisedToday, streak
    }

    private struct LossyEntry: Decodable {
        let entry: BuddyEntry?
        init(from decoder: Decoder) throws { entry = try? BuddyEntry(from: decoder) }
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        var s = BuddyStatusCache()
        if c.contains(.buddies) {
            s.buddies = ((try? c.decode([LossyEntry].self, forKey: .buddies)) ?? []).compactMap(\.entry)
        } else if (try c.decodeIfPresent(Bool.self, forKey: .paired)) == true {
            // A cache from the one-buddy app: its buddy becomes the list's one
            // entry, with no pairing id until the next answer brings one.
            let name = (try c.decodeIfPresent(String.self, forKey: .buddyName) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !name.isEmpty {
                s.buddies = [BuddyEntry(id: "", displayName: name,
                                        practisedToday: try c.decodeIfPresent(Bool.self, forKey: .buddyPractisedToday) ?? false,
                                        streak: max(0, try c.decodeIfPresent(Int.self, forKey: .streak) ?? 0))]
            }
        }
        s.maxBuddies = max(1, s.buddies.count, try c.decodeIfPresent(Int.self, forKey: .maxBuddies) ?? 1)
        s.myStreak = try c.decodeIfPresent(Int.self, forKey: .myStreak) ?? s.myStreak
        s.today = try c.decodeIfPresent(String.self, forKey: .today) ?? s.today
        s.fetchedAt = try c.decodeIfPresent(Date.self, forKey: .fetchedAt)
        s.lastReportedDay = try c.decodeIfPresent(String.self, forKey: .lastReportedDay)
        s.pendingInviteCode = try c.decodeIfPresent(String.self, forKey: .pendingInviteCode)
        s.pendingInviteExpiresAt = try c.decodeIfPresent(Date.self, forKey: .pendingInviteExpiresAt)
        self = s
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(buddies, forKey: .buddies)
        try c.encode(maxBuddies, forKey: .maxBuddies)
        try c.encode(myStreak, forKey: .myStreak)
        try c.encode(today, forKey: .today)
        try c.encodeIfPresent(fetchedAt, forKey: .fetchedAt)
        try c.encodeIfPresent(lastReportedDay, forKey: .lastReportedDay)
        try c.encodeIfPresent(pendingInviteCode, forKey: .pendingInviteCode)
        try c.encodeIfPresent(pendingInviteExpiresAt, forKey: .pendingInviteExpiresAt)
    }

    /// Whether `buddy` is known to have practised on `today` (the current
    /// local day label). A cache from an earlier day cannot vouch for today,
    /// so it reads as "not yet" until the next refresh.
    public func practised(_ buddy: BuddyEntry, on today: String) -> Bool {
        !self.today.isEmpty && self.today == today && buddy.practisedToday
    }

    /// The invite to show, if one is pending, still valid at `now`, and the
    /// list has room for whoever uses it.
    public func pendingInvite(at now: Date) -> (code: String, expiresAt: Date)? {
        guard !isFull, let code = pendingInviteCode, let expiresAt = pendingInviteExpiresAt,
              expiresAt > now else { return nil }
        return (code, expiresAt)
    }

    /// Whether a status refresh has anything to learn: paired (the buddies'
    /// days and the streaks move), or an invite is out (the pairing lands
    /// when they join). Otherwise a refresh would only attest for nothing.
    public func wantsRefresh(at now: Date) -> Bool {
        paired || pendingInvite(at: now) != nil
    }

    /// "12-day buddy streak", "1-day buddy streak", or "no buddy streak yet".
    public static func streakLabel(_ streak: Int) -> String {
        streak > 0 ? "\(streak)-day buddy streak" : "no buddy streak yet"
    }

    public func digest(today: String) -> BuddyDigest {
        BuddyDigest(buddies: buddies, statusDay: self.today, today: today)
    }

    /// The home screen's line under the streak badge, or nil with no
    /// buddies. One buddy: "W1AW practiced today · 12-day buddy streak" /
    /// "W1AW hasn't practiced yet today · 12-day buddy streak" (the #219
    /// wording). Several: "All 3 buddies practiced today · best: 40-day
    /// buddy streak", or who is still waiting, then the best streak.
    public func homeLine(today: String) -> String? {
        let d = digest(today: today)
        guard let first = buddies.first else { return nil }
        if d.count == 1 {
            let did = d.allPractised ? "practiced today" : "hasn't practiced yet today"
            return "\(first.displayName) \(did) · \(Self.streakLabel(first.streak))"
        }
        let summary = d.allPractised ? "All \(d.count) buddies practiced today" : (d.waitingPhrase ?? "")
        let streak = d.bestStreak > 0 ? "best: \(Self.streakLabel(d.bestStreak))" : Self.streakLabel(0)
        return "\(summary) · \(streak)"
    }

    /// One buddy's row in Settings › Leaderboard & Buddy › Buddy streak, under the name:
    /// "12-day buddy streak · practiced today" / "… · hasn't practiced yet today".
    public func rowLine(for buddy: BuddyEntry, today: String) -> String {
        let did = practised(buddy, on: today) ? "practiced today" : "hasn't practiced yet today"
        return "\(Self.streakLabel(buddy.streak)) · \(did)"
    }

    /// Settings › Leaderboard & Buddy › Buddy streak's first line with no buddies.
    public static let notPairedLine = "Invite a buddy, or join with a code they send you"

    /// "2 of 10 buddies" (the list's header line, so the cap is visible).
    public var countLine: String {
        "\(buddies.count) of \(maxBuddies) \(maxBuddies == 1 ? "buddy" : "buddies")"
    }

    /// The sentence the daily reminder gains when, at the last fetch, some
    /// buddy had not practiced — "W1AW hasn't practiced yet today (as of
    /// 6:10 pm)", or "W1AW, K1ABC and 2 more haven't …" with several: one
    /// sentence however many buddies, never one each (#237: no spam) — or
    /// nil when there is nothing to nudge about. `asOf` is the fetch time
    /// already formatted for the user's locale; the sentence says it because
    /// the notification's text is baked in when it is scheduled and can be
    /// hours stale by the time it fires (design §"The nudge"). A cache from
    /// an earlier day says nothing: "as of 6:10 pm" would be yesterday's.
    public func reminderSentence(today: String, asOf: String) -> String? {
        guard paired, fetchedAt != nil, self.today == today, let phrase = digest(today: today).waitingPhrase else { return nil }
        return "\(phrase) (as of \(asOf))"
    }
}
