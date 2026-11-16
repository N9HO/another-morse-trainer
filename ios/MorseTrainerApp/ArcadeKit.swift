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
    /// When each enemy appeared, by id: a hit while its sound is still
    /// waiting its turn is timed from there, not posted as 0 ms.
    var appeared: [Int: Date] = [:]

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
        appeared = [:]
    }

    /// A new enemy to announce, which appeared at `now`. A Galaga diver is
    /// announced again; it keeps the time it first appeared.
    func arrived(id: Int, text: String, at now: Date) {
        if appeared[id] == nil { appeared[id] = now }
        announcer.arrived(id: id, text: text)
    }

    /// An enemy that is gone (shot, landed, destroyed): nothing more to
    /// announce or time for it.
    func forget(id: Int) {
        toneEnd[id] = nil
        appeared[id] = nil
        announcer.forget(id: id)
    }

    /// The reaction time of a hit on `id` at `now`, then forgets it: from the
    /// end of its sound (0 while it is still playing, as before), or, if its
    /// sound hasn't played yet (it was waiting its turn), from when it
    /// appeared.
    func reactionTime(hit id: Int, at now: Date) -> TimeInterval {
        defer { forget(id: id) }
        guard let from = toneEnd[id] ?? appeared[id] else { return 0 }
        return max(0, now.timeIntervalSince(from))
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
