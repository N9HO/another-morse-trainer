import Foundation

/// How much the learner practiced on each calendar day, for the Stats
/// screen's activity grid (#181): one tile per day, shaded by that day's
/// total session time.
///
/// `SessionHistory` keeps only the newest hundred sessions, which is a few
/// weeks for a daily learner, so the grid keeps its own ledger: a map of
/// local calendar day (`yyyy-MM-dd`) to whole seconds practiced, capped at
/// `capDays` days with the oldest dropped first. A day is a calendar day in
/// the user's current time zone, the same boundary `PracticeStreak` uses.
///
/// Shaded in five levels from fixed thresholds — `fixtures/activity.json`
/// pins the thresholds, the per-day totals and the cap on both ports.
public struct ActivityLedger: Codable, Sendable, Equatable {
    /// Whole seconds practiced, keyed by local day as `yyyy-MM-dd`. The key
    /// format sorts chronologically, which is what the cap relies on.
    public private(set) var days: [String: Int]

    /// The most days the ledger keeps; the earliest fall off beyond it.
    public static let capDays = 400

    /// Seconds at which shade levels 1…4 begin: a recorded day below the
    /// first is level 1 anyway (see `level(on:)`), 300 s (5 min) is level 2,
    /// 900 s (15 min) level 3, 1800 s (30 min) level 4.
    public static let levelThresholds = [1, 300, 900, 1800]

    /// The number of shade levels, 0 (nothing) through 4 (most).
    public static let maxLevel = 4

    public init(days: [String: Int] = [:]) {
        self.days = days
    }

    /// The `yyyy-MM-dd` key for the local calendar day containing `date`.
    /// Built from calendar components, not a `DateFormatter`, so a device
    /// locale with non-Latin digits cannot change the key.
    public static func dayKey(for date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// The start of the local day a key names, or nil for a malformed key.
    public static func date(forKey key: String, calendar: Calendar = .current) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    /// Add `seconds` of practice to `day` (a `yyyy-MM-dd` key). A session with
    /// no duration records 0 and still marks the day; negative input counts
    /// as 0. Trims the earliest days once the cap is exceeded.
    public mutating func record(day: String, seconds: Int) {
        days[day, default: 0] += max(0, seconds)
        if days.count > Self.capDays {
            let excess = days.keys.sorted().prefix(days.count - Self.capDays)
            for key in excess { days.removeValue(forKey: key) }
        }
    }

    /// Add `seconds` of practice to the local day containing `date`.
    public mutating func record(date: Date, seconds: Int, calendar: Calendar = .current) {
        record(day: Self.dayKey(for: date, calendar: calendar), seconds: seconds)
    }

    /// Whether anything was recorded on `day`.
    public func isRecorded(day: String) -> Bool { days[day] != nil }

    /// Seconds practiced on `day` (0 when nothing was recorded).
    public func seconds(on day: String) -> Int { days[day] ?? 0 }

    /// Seconds practiced on the local day containing `date`.
    public func seconds(on date: Date, calendar: Calendar = .current) -> Int {
        seconds(on: Self.dayKey(for: date, calendar: calendar))
    }

    /// The shade level for a seconds total, 0…4, from `levelThresholds`
    /// alone: 0 s is level 0. A pure function so the fixture can pin it.
    public static func level(forSeconds seconds: Int) -> Int {
        levelThresholds.filter { seconds >= $0 }.count
    }

    /// The tile shade for `day`: `level(forSeconds:)` of its total, but never
    /// below 1 for a day that was recorded — showing up with a session that
    /// logged no time is still showing up. Unrecorded days are 0.
    public func level(on day: String) -> Int {
        guard let total = days[day] else { return 0 }
        return max(1, Self.level(forSeconds: total))
    }

    /// The tile shade for the local day containing `date`.
    public func level(on date: Date, calendar: Calendar = .current) -> Int {
        level(on: Self.dayKey(for: date, calendar: calendar))
    }

    /// Distinct days recorded (at most `capDays`).
    public var dayCount: Int { days.count }

    /// Every recorded day key, earliest first.
    public var recordedDays: [String] { days.keys.sorted() }
}
