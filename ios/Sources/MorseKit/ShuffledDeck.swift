import Foundation

/// Draws from a fixed pool without replacement: every element comes out once,
/// in random order, before any comes out a second time. A spent pass is
/// reshuffled on the next draw, and the new pass never opens with the element
/// that closed the last one, so two draws in a row are never the same.
///
/// Listen & Learn used to pick each item with `randomElement()`, which repeats
/// early and often — with a few hundred words the odds of hearing one twice
/// pass even money inside thirty items (issue #158). A deck spaces every item
/// a whole pass apart.
public struct ShuffledDeck<Element: Equatable> {
    public let pool: [Element]
    /// The current pass, drawn from the end.
    private var remaining: [Element] = []
    private var last: Element?
    private var rng: any RandomNumberGenerator

    public init(_ pool: [Element],
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.pool = pool
        self.rng = rng
    }

    /// Elements still to come in the current pass — 0 once it is spent, until
    /// the next draw reshuffles.
    public var remainingInPass: Int { remaining.count }

    /// The next element, or nil for an empty pool.
    public mutating func draw() -> Element? {
        guard !pool.isEmpty else { return nil }
        if remaining.isEmpty {
            remaining = pool.shuffled(using: &rng)
            // Draws come off the end, so the end is the head of the new pass.
            // If it repeats the last draw, swap it somewhere else in the pass.
            if pool.count > 1, let last, remaining[remaining.count - 1] == last {
                let other = Int.random(in: 0..<(remaining.count - 1), using: &rng)
                remaining.swapAt(remaining.count - 1, other)
            }
        }
        let next = remaining.removeLast()
        last = next
        return next
    }
}
