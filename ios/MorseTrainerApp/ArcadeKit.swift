import Foundation

/// What an arcade game's frame loop touches on every frame, kept in a
/// reference so writing it does not re-render the view: at 60 frames a second
/// a `@State` write rebuilt the whole screen, keyboard and all, and the games
/// stuttered. Each game view holds one in `@State` (the reference never
/// changes) and reads it from its `TimelineView` step.
class ArcadeFrame {
    private var lastFrame: Date?

    /// Seconds since the last frame, capped at 0.1 so a long gap (the app was
    /// backgrounded) does not move the game on in one step; nil on a run's
    /// first frame.
    func step(to now: Date) -> TimeInterval? {
        defer { lastFrame = now }
        guard let last = lastFrame else { return nil }
        return min(0.1, now.timeIntervalSince(last))
    }

    /// A new run, or the end of one.
    func reset() {
        lastFrame = nil
    }
}
