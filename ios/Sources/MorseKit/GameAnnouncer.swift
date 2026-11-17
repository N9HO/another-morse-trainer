import Foundation

/// The hear-it arcade games' one sound channel: Morse Invaders, CW Galaga and
/// Morse Defender announce each new enemy by its character (or callsign), and
/// a tap replays the nearest. Both used to replace whatever was sounding, so a
/// tap cut into an announcement (or one announcement into the next)
/// mid-character and the two ran together.
///
/// Now a sound asked for while another plays waits until that one has ended
/// and the caller's gap has passed (a character space at the game's speed:
/// each enemy stays a separate letter, and the channel keeps up at the fastest
/// waves); on a free channel it starts at once. New
/// enemies are announced in the order they arrived, and one already waiting
/// is not queued twice. A tap queues one replay, heard after any announcement
/// already waiting, and a later tap replaces it. An enemy shot, destroyed or
/// landed before its turn is never announced; one already sounding plays out.
/// Pinned by `fixtures/game-announcer.json`; the Kotlin twin is
/// `GameAnnouncer.kt`.
///
/// The announcer only decides; the screen plays. Each frame (and after a tap)
/// it asks `next(at:)` for a sound to start, plays it, and reports how long it
/// lasts with `started(at:duration:gap:)`. Times are seconds on any one
/// clock the caller keeps to.
public struct GameAnnouncer: Sendable {
    public enum Sound: Equatable, Sendable {
        /// A new enemy's character or callsign.
        case arrival(id: Int, text: String)
        /// What a tap asked to hear again.
        case replay(String)

        /// What to send.
        public var text: String {
            switch self {
            case .arrival(_, let text), .replay(let text): return text
            }
        }
    }

    private var arrivals: [(id: Int, text: String)] = []
    private var waitingReplay: String?
    /// When the channel is free: the last sound's end plus the gap.
    private var freeAt = -Double.infinity

    public init() {}

    /// A new enemy is on the field: announce it in its turn, unless it is
    /// already waiting.
    public mutating func arrived(id: Int, text: String) {
        guard !arrivals.contains(where: { $0.id == id }) else { return }
        arrivals.append((id, text))
    }

    /// A tap asked to hear `text` again; replaces a replay still waiting.
    public mutating func replay(_ text: String) {
        waitingReplay = text
    }

    /// The enemy was shot, destroyed or landed: drop its announcement if it
    /// is still waiting.
    public mutating func forget(id: Int) {
        arrivals.removeAll { $0.id == id }
    }

    /// A new game, or the end of one: nothing waits, and the channel is free.
    public mutating func reset() {
        arrivals.removeAll()
        waitingReplay = nil
        freeAt = -Double.infinity
    }

    /// Whether the channel could start a new enemy's sound at `now`: nothing
    /// is sounding, the gap after the last sound has passed, and no arrival
    /// is waiting its turn. A waiting replay does not count, since an arrival
    /// goes ahead of it. CW Galaga holds a due entry until this is true
    /// (`GalagaGame.advance(by:channelFree:)`), so every enemy is heard the
    /// moment it enters. Pinned by `fixtures/galaga-pacing.json`.
    public func isFree(at now: Double) -> Bool {
        now >= freeAt && arrivals.isEmpty
    }

    /// The sound to start at `now`, if the channel is free and one is waiting.
    /// The caller plays it and reports its length with `started`.
    public mutating func next(at now: Double) -> Sound? {
        guard now >= freeAt else { return nil }
        if !arrivals.isEmpty {
            let a = arrivals.removeFirst()
            return .arrival(id: a.id, text: a.text)
        }
        if let text = waitingReplay {
            waitingReplay = nil
            return .replay(text)
        }
        return nil
    }

    /// The sound `next` returned started at `now` and lasts `duration`; the
    /// channel is free again a `gap` after it ends.
    public mutating func started(at now: Double, duration: Double, gap: Double) {
        freeAt = now + duration + gap
    }
}
