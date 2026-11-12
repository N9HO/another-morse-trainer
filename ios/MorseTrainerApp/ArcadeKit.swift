import Foundation

/// What an arcade game's frame loop touches on every frame, kept in a
/// reference so writing it does not re-render the view: at 60 frames a second
/// a `@State` write rebuilt the whole screen, keyboard and all, and the games
/// stuttered. Each game view holds one in `@State` (the reference never
/// changes) and reads it from its `TimelineView` step.
class ArcadeFrame {
    private var lastFrame: Date?
    /// The one sound channel, for the games that announce (`GameAnnouncer`).
    var announcer = GameAnnouncer()
    /// When each enemy's sound finished, by id, so a hit's reaction time runs
    /// from the end of its sound.
    var toneEnd: [Int: Date] = [:]

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
        announcer.reset()
        toneEnd = [:]
    }

    /// Plays what the channel has waiting, if it is free: `send` sounds the
    /// text and returns its length, and the channel is free again `wordGap`
    /// after it ends. An arrival's end goes to `toneEnd`.
    func announce(at now: Date, wordGap: TimeInterval, send: (String) -> TimeInterval) {
        let clock = now.timeIntervalSinceReferenceDate
        guard let sound = announcer.next(at: clock) else { return }
        let duration = send(sound.text)
        if case .arrival(let id, _) = sound { toneEnd[id] = now.addingTimeInterval(duration) }
        announcer.started(at: clock, duration: duration, wordGap: wordGap)
    }
}
