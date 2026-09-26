import Foundation

/// A software iambic keyer for the on-screen paddles (#233): turns dit and
/// dah paddle presses into timed key-down / key-up edges, the way a hardware
/// keyer turns a paddle into a keyed tone.
///
/// Pure and clock-free — the caller supplies every timestamp — so the same
/// script of paddle events always yields the same edges. The app drives it
/// from a timer (`advance(toMs:)` at `nextDeadlineMs`); the harness drives it
/// from `fixtures/paddle-keyer.json`, whose `derivation` block is the spec
/// this implements:
///
/// - One unit is 1200 / wpm ms. A dit keys 1 unit, a dah 3; each element is
///   followed by 1 unit of silence, and the element plus that silence is its
///   *slot*. What to send next is decided at the slot's end.
/// - A press while idle starts its element at once. A press while busy is
///   latched (dit / dah memory); starting an element clears its own latch.
/// - Iambic A / B: at a slot's end send the opposite element if its paddle is
///   held or latched, else the same one if held or latched, else stop.
///   B also latches the opposite paddle if it is already held when an element
///   starts, which is what sends one more element after a squeeze is let go.
/// - Ultimatic: if both are wanted, the most recently pressed paddle wins.
/// - A deadline at the same instant as a paddle event is processed first.
///
/// This is not the Vail Adapter's keyer (`MIDIOutput.KeyerMode`), which times
/// a *hardware* paddle inside the adapter; this one times the touch paddles.
public struct PaddleKeyer: Sendable {
    public enum Mode: String, CaseIterable, Sendable, Codable {
        case iambicA, iambicB, ultimatic
    }

    public enum Element: String, Sendable, Equatable {
        case dit, dah
        var opposite: Element { self == .dit ? .dah : .dit }
    }

    /// One key transition: the tone starts (`isDown`) or stops, at `atMs`.
    public struct Edge: Sendable, Equatable {
        public let isDown: Bool
        public let atMs: Double
        public let element: Element
        public init(isDown: Bool, atMs: Double, element: Element) {
            self.isDown = isDown
            self.atMs = atMs
            self.element = element
        }
    }

    public let mode: Mode
    public let unitMs: Double

    private var held: [Element: Bool] = [.dit: false, .dah: false]
    private var latched: [Element: Bool] = [.dit: false, .dah: false]
    private var pressedAt: [Element: Double] = [.dit: -.infinity, .dah: -.infinity]
    /// The element in progress (tone or trailing silence); nil when idle.
    private var current: Element?
    private var toneOn = false
    private var toneEndMs = 0.0
    private var slotEndMs = 0.0

    public init(mode: Mode, wpm: Double) {
        self.mode = mode
        self.unitMs = 1200.0 / max(1, wpm)
    }

    /// True while an element or its trailing silence is in progress.
    public var isBusy: Bool { current != nil }

    /// When the keyer next needs `advance(toMs:)`: the end of the tone that is
    /// sounding, or the end of the slot. nil when idle.
    public var nextDeadlineMs: Double? {
        guard current != nil else { return nil }
        return toneOn ? toneEndMs : slotEndMs
    }

    /// A paddle went down or up at `atMs`. Returns every edge due up to and
    /// including that instant — those the clock owed first, then any this
    /// event starts.
    public mutating func paddle(_ element: Element, isDown: Bool, atMs t: Double) -> [Edge] {
        var edges = advance(toMs: t)
        if isDown {
            guard held[element] != true else { return edges }
            held[element] = true
            pressedAt[element] = t
            if current == nil {
                edges.append(start(element, at: t))
            } else {
                latched[element] = true
            }
        } else {
            held[element] = false
        }
        return edges
    }

    /// Run the clock on to `t`, returning every edge due by then.
    public mutating func advance(toMs t: Double) -> [Edge] {
        var edges: [Edge] = []
        while let deadline = nextDeadlineMs, deadline <= t {
            if toneOn {
                toneOn = false
                edges.append(Edge(isDown: false, atMs: toneEndMs, element: current!))
            } else {
                let last = current!
                current = nil
                if let next = decide(after: last) {
                    edges.append(start(next, at: deadline))
                }
            }
        }
        return edges
    }

    /// Let go of both paddles and cut any tone at `atMs`: the screen is going
    /// away. Returns the key-up if a tone was sounding.
    public mutating func releaseAll(atMs t: Double) -> [Edge] {
        held = [.dit: false, .dah: false]
        latched = [.dit: false, .dah: false]
        defer { current = nil; toneOn = false }
        guard toneOn, let element = current else { return [] }
        return [Edge(isDown: false, atMs: t, element: element)]
    }

    private func wanted(_ e: Element) -> Bool {
        held[e] == true || latched[e] == true
    }

    private func decide(after last: Element) -> Element? {
        switch mode {
        case .iambicA, .iambicB:
            if wanted(last.opposite) { return last.opposite }
            if wanted(last) { return last }
            return nil
        case .ultimatic:
            switch (wanted(.dit), wanted(.dah)) {
            case (true, true):
                return (pressedAt[.dah] ?? 0) > (pressedAt[.dit] ?? 0) ? .dah : .dit
            case (true, false): return .dit
            case (false, true): return .dah
            case (false, false): return nil
            }
        }
    }

    private mutating func start(_ e: Element, at t: Double) -> Edge {
        current = e
        toneOn = true
        toneEndMs = t + (e == .dit ? 1 : 3) * unitMs
        slotEndMs = toneEndMs + unitMs
        latched[e] = false
        if mode == .iambicB, held[e.opposite] == true {
            latched[e.opposite] = true
        }
        return Edge(isDown: true, atMs: t, element: e)
    }
}
