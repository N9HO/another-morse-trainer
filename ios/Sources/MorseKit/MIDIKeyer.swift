// MIDIKeyer.swift
// A MIDI key's contacts → the one logical key. The straight-key note keys as
// it comes (a straight key, or a Vail Adapter's own keyer). Dit and dah from a
// paddle with no keyer of its own (the N6ARA TinyMIDI, a Morserino-32 Pocket,
// a Vail Adapter in Passthrough) are timed by `PaddleKeyer`; they used to key
// as straight-key contacts, so holding dit sounded one long tone. A Vail
// Adapter's own keyer also sends on notes 1 and 2 (firmware 4.4 and later),
// already timed, so those key as they come unless the adapter is in
// Passthrough (`timesNote`).
//
// Pure and clock-free: the caller supplies every timestamp. Pinned by
// `fixtures/midi-keyer.json`, which the Kotlin ports read too.

import Foundation

public struct MIDIKeyer: Sendable {
    /// One change of the logical key.
    public struct Edge: Sendable, Equatable {
        public let isDown: Bool
        public let atMs: Double
        /// True when the app's keyer timed this edge (from a dit or dah
        /// contact); false when a contact keyed it as it came.
        public let timedByApp: Bool

        public init(isDown: Bool, atMs: Double, timedByApp: Bool) {
            self.isDown = isDown
            self.atMs = atMs
            self.timedByApp = timedByApp
        }
    }

    /// The keyer's tone, and each contact keyed as it comes.
    private enum Half: Hashable, Sendable { case keyer, held(MIDIKeyPaddle) }

    private var keyer: PaddleKeyer
    private var merge = KeyMerge<Half>()
    /// Contacts keyed as they come, held now: the straight contact, and a
    /// dit or dah the keyer doesn't time.
    private var held = Set<MIDIKeyPaddle>()
    /// A mode and speed `configure` asked for while the keyer was busy.
    private var pending: (mode: PaddleKeyer.Mode, wpm: Double)?

    public init(mode: PaddleKeyer.Mode, wpm: Double) {
        keyer = PaddleKeyer(mode: mode, wpm: wpm)
    }

    public var mode: PaddleKeyer.Mode { keyer.mode }
    public var unitMs: Double { keyer.unitMs }

    /// True while an element is in progress or a contact keyed as it comes is
    /// held.
    public var isBusy: Bool { keyer.isBusy || !held.isEmpty }

    /// Whether the keyer times a dit or dah contact that came on `note`. A Vail
    /// Adapter sends its raw paddle contacts on notes 1 and 2 only in
    /// Passthrough. In its keyer modes, firmware 4.4 and later sends its own
    /// keyer-timed dits and dahs on those notes (earlier firmware sent every
    /// element on note 0), and timing them again would add elements: a note-off
    /// a moment after the element's end reads as a held paddle. The app sets
    /// the adapter's keyer mode, so `adapterPassthrough` is that setting.
    /// The other paddle notes come from devices with no keyer mode to set
    /// (20/21 the TinyMIDI and the Pocket, 61/62): always timed.
    public static func timesNote(_ note: UInt8, adapterPassthrough: Bool) -> Bool {
        note == 1 || note == 2 ? adapterPassthrough : true
    }

    /// When `advance(toMs:)` is next needed; nil when the keyer is idle.
    public var nextDeadlineMs: Double? { keyer.nextDeadlineMs }

    /// Time the paddles at `mode` and `wpm` from `atMs`. A change made while
    /// the keyer is busy waits until it is idle, so nothing in flight is
    /// re-timed; of several waiting changes the last wins. Returns the edges
    /// the clock owed by `atMs`.
    public mutating func configure(mode: PaddleKeyer.Mode, wpm: Double, atMs t: Double) -> [Edge] {
        let edges = route(keyer.advance(toMs: t))
        pending = (mode, wpm)
        takePendingIfIdle()
        return edges
    }

    /// One contact of the key went down or up at `atMs`. A dit or dah goes to
    /// the keyer when `timed` (`timesNote`), and keys as it comes otherwise; a
    /// release goes wherever its press went. Returns every edge due by then:
    /// those the keyer's clock owed first, then this one's.
    public mutating func contact(_ paddle: MIDIKeyPaddle, isDown: Bool, atMs t: Double,
                                 timed: Bool = true) -> [Edge] {
        // Run the clock on first, so a change that was waiting for the
        // keyer to go idle is in place before this contact starts anything.
        var edges = route(keyer.advance(toMs: t))
        takePendingIfIdle()
        let asItComes = paddle == .straight || (isDown ? !timed : held.contains(paddle))
        if asItComes {
            guard held.contains(paddle) != isDown else { return edges }
            if isDown { held.insert(paddle) } else { held.remove(paddle) }
            if let now = merge.update(.held(paddle), isDown: isDown) {
                edges.append(Edge(isDown: now, atMs: t, timedByApp: false))
            }
            return edges
        }
        switch paddle {
        case .straight:
            return edges
        case .dit:
            return edges + route(keyer.paddle(.dit, isDown: isDown, atMs: t))
        case .dah:
            return edges + route(keyer.paddle(.dah, isDown: isDown, atMs: t))
        }
    }

    /// Run the keyer's clock on to `t`.
    public mutating func advance(toMs t: Double) -> [Edge] {
        let edges = route(keyer.advance(toMs: t))
        takePendingIfIdle()
        return edges
    }

    /// The key went away (unplugged, or the screen is closing): let go of
    /// every contact and cut the keyer's tone at `t`.
    public mutating func releaseAll(atMs t: Double) -> [Edge] {
        var edges = route(keyer.advance(toMs: t))
        edges += route(keyer.releaseAll(atMs: t))
        for paddle in MIDIKeyPaddle.allCases where held.contains(paddle) {
            held.remove(paddle)
            if let now = merge.update(.held(paddle), isDown: false) {
                edges.append(Edge(isDown: now, atMs: t, timedByApp: false))
            }
        }
        takePendingIfIdle()
        return edges
    }

    /// Swap in a keyer with the waiting mode and speed, if the keyer is idle.
    /// Only the paddle keyer is replaced: held contacts and the merge
    /// carry on, and an idle keyer holds nothing a fresh one would lose.
    private mutating func takePendingIfIdle() {
        guard let p = pending, !keyer.isBusy else { return }
        pending = nil
        if p.mode != keyer.mode || abs(1200 / max(1, p.wpm) - keyer.unitMs) > 1e-9 {
            keyer = PaddleKeyer(mode: p.mode, wpm: p.wpm)
        }
    }

    /// The keyer's tone edges, as changes of the logical key.
    private mutating func route(_ keyed: [PaddleKeyer.Edge]) -> [Edge] {
        var edges: [Edge] = []
        for edge in keyed {
            if let now = merge.update(.keyer, isDown: edge.isDown) {
                edges.append(Edge(isDown: now, atMs: edge.atMs, timedByApp: true))
            }
        }
        return edges
    }
}
