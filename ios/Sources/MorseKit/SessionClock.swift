import Foundation

/// The arithmetic behind the session timer menu (`AppModel.addSessionTime` /
/// `reduceSessionTime`, issue #41), kept pure so it is pinned by
/// `fixtures/gap-timers-session-clock.json`.
///
/// `remaining` is the seconds left on the countdown, or nil for an open-ended
/// session. Reaching 0 ends the session; the model's own countdown does that.
public enum SessionClock {
    /// "Add N minutes": on a timed session the seconds are added to what is
    /// left; on an open-ended one they start a countdown of exactly `seconds`.
    /// Zero or less changes nothing.
    public static func added(_ remaining: TimeInterval?, by seconds: TimeInterval) -> TimeInterval? {
        guard seconds > 0 else { return remaining }
        return (remaining ?? 0) + seconds
    }

    /// "Subtract 1 minute" (offered only while timed): what is left drops by
    /// `seconds`, never below 0. An open-ended session has nothing to subtract
    /// from and stays open-ended. Zero or less changes nothing.
    public static func reduced(_ remaining: TimeInterval?, by seconds: TimeInterval) -> TimeInterval? {
        guard let remaining, seconds > 0 else { return remaining }
        return max(0, remaining - seconds)
    }
}
