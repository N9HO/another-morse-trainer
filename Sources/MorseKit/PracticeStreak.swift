import Foundation

/// Tracks how many consecutive calendar days the learner has practiced, to
/// motivate daily engagement (issue #20).
///
/// A "day" is a calendar day in the user's current time zone, and *any* recorded
/// drill answer counts as having practiced — the goal is to reward showing up,
/// not to gate on session length. The streak stays alive as long as you practice
/// at least once every day: practice today and it holds, practice the next day
/// and it grows, miss a whole day and it resets.
public struct PracticeStreak: Codable, Sendable, Equatable {
    /// Consecutive-day count as of `lastPracticeDay`. Use `display(on:)` to read
    /// the streak as it stands *today* — `current` does not self-expire.
    public private(set) var current: Int
    /// Best streak ever reached, kept as a personal record even after a lapse.
    public private(set) var longest: Int
    /// Start-of-day of the most recent day the learner practiced (nil = never).
    public private(set) var lastPracticeDay: Date?

    public init(current: Int = 0, longest: Int = 0, lastPracticeDay: Date? = nil) {
        self.current = current
        self.longest = longest
        self.lastPracticeDay = lastPracticeDay
    }

    /// Streak lengths worth celebrating with a milestone badge.
    public static let milestones = [3, 7, 14, 30, 60, 100, 365]

    /// Whether `day` is exactly a celebrated milestone (use to fire a one-time
    /// celebration the day it's reached).
    public static func isMilestone(_ day: Int) -> Bool { milestones.contains(day) }

    /// The highest milestone reached at `day`, or nil if none yet — gives the
    /// streak badge its tier.
    public static func milestone(forDay day: Int) -> Int? {
        milestones.last { $0 <= day }
    }

    /// Record that the learner practiced at `date`. Idempotent within a day:
    /// the first practice of a day advances the streak, later ones are no-ops.
    ///
    /// - Returns: `true` if this was the day's first practice (the streak
    ///   counter changed), so callers can fire day-one-only UI (a toast, a
    ///   haptic) without repeating it on every drill.
    @discardableResult
    public mutating func record(on date: Date, calendar: Calendar = .current) -> Bool {
        let today = calendar.startOfDay(for: date)
        guard let last = lastPracticeDay else {
            current = 1
            longest = max(longest, current)
            lastPracticeDay = today
            return true
        }
        let lastDay = calendar.startOfDay(for: last)
        let gap = calendar.dateComponents([.day], from: lastDay, to: today).day ?? 0
        switch gap {
        case ...0:
            return false            // same day (or clock skew) — already counted
        case 1:
            current += 1            // consecutive day — extend the streak
        default:
            current = 1             // a full day was missed — start over
        }
        longest = max(longest, current)
        lastPracticeDay = today
        return true
    }

    /// The streak as it should read *today*. A streak the learner already let
    /// lapse must show 0, not a stale count — so this returns `current` only
    /// while the streak is still alive (practiced today or yesterday) and 0
    /// once a full day has been missed.
    public func display(on date: Date, calendar: Calendar = .current) -> Int {
        guard let last = lastPracticeDay else { return 0 }
        let today = calendar.startOfDay(for: date)
        let lastDay = calendar.startOfDay(for: last)
        let gap = calendar.dateComponents([.day], from: lastDay, to: today).day ?? 0
        return gap <= 1 ? current : 0
    }
}
