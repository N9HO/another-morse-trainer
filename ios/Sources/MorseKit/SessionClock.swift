import Foundation

/// The arithmetic behind the session timer menu (`AppModel.addSessionTime` /
/// `reduceSessionTime`, issue #41), kept pure so it is pinned by
/// `fixtures/gap-timers-session-clock.json`.
///
/// `remaining` is the seconds left on the countdown, or nil for an open-ended
/// session. Reaching 0 ends the session, or holds it for a drill still waiting
/// for its answer (`endsAfterTimeUp`); the model's own countdown does that.
public enum SessionClock {
    /// The longest a run-out countdown holds the session for the drill that
    /// was waiting (`timeUp.graceSeconds` in the fixture).
    public static let timeUpGraceSeconds: TimeInterval = 15

    /// Once a session's screen has arrived, how long its first sound waits
    /// more, so it does not land the instant the screen settles
    /// (`firstSoundBeat` in the fixture).
    public static let firstSoundBeat: TimeInterval = 0.4

    /// Whether a session whose countdown ran out `overdue` seconds ago ends
    /// now. `held` is whether the run-out found a drill sent and waiting for
    /// its answer: that drill may still be answered, and answering it ends the
    /// session where the next drill would have started, so the last item
    /// heard is never one the learner had no time to answer. The hold lasts
    /// at most `timeUpGraceSeconds`, so an abandoned session still ends.
    /// Unheld, it ends at once.
    public static func endsAfterTimeUp(held: Bool, overdue: TimeInterval) -> Bool {
        !held || overdue >= timeUpGraceSeconds
    }

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
