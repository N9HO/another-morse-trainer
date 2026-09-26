import SwiftUI

/// What the on-screen key is (#233): the single hold-to-key pad every keying
/// screen has always had, or a pair of iambic paddles timed by `PaddleKeyer`.
enum OnScreenKeyType: String, Codable, CaseIterable, Identifiable {
    case straight, paddles
    var id: String { rawValue }
    var label: String {
        switch self {
        case .straight: return "Straight key"
        case .paddles: return "Paddles"
        }
    }
}

extension PaddleKeyer.Mode: Identifiable {
    public var id: String { rawValue }
    var label: String {
        switch self {
        case .iambicA: return "Iambic A"
        case .iambicB: return "Iambic B"
        case .ultimatic: return "Ultimatic"
        }
    }
}

/// Runs a `PaddleKeyer` against the wall clock and hands each key edge to
/// `onKey` — the same `(isDown, atMs)` a straight key's press and release
/// would produce, so whatever the straight key feeds (sidetone, the sending
/// decoder, the Vail repeater) is fed the keyer's elements instead.
///
/// Main-actor throughout: paddle presses arrive from gestures, and the sleeps
/// between deadlines resume here. Each edge carries the time the keyer
/// scheduled it for, not the moment the sleep woke, so a late wake-up shifts
/// when the tone is heard but never the element lengths a consumer measures.
@MainActor
final class PaddleKeyerDriver: ObservableObject {
    @Published private(set) var ditHeld = false
    @Published private(set) var dahHeld = false

    var onKey: ((Bool, Int64) -> Void)?

    private var keyer: PaddleKeyer
    /// A mode or speed change made while keying, applied once the keyer is idle
    /// so an element in flight is never re-timed.
    private var pending: (mode: PaddleKeyer.Mode, wpm: Double)?
    private var tick: Task<Void, Never>?

    init(mode: PaddleKeyer.Mode, wpm: Double) {
        keyer = PaddleKeyer(mode: mode, wpm: wpm)
    }

    func configure(mode: PaddleKeyer.Mode, wpm: Double) {
        pending = (mode, wpm)
        applyPendingIfIdle()
    }

    func isHeld(_ element: PaddleKeyer.Element) -> Bool {
        element == .dit ? ditHeld : dahHeld
    }

    func paddle(_ element: PaddleKeyer.Element, isDown: Bool) {
        guard isHeld(element) != isDown else { return }
        if element == .dit { ditHeld = isDown } else { dahHeld = isDown }
        emit(keyer.paddle(element, isDown: isDown, atMs: Self.nowMs()))
        schedule()
    }

    /// The screen is going away: let go of both paddles and cut any tone.
    func stop() {
        tick?.cancel()
        tick = nil
        ditHeld = false
        dahHeld = false
        emit(keyer.releaseAll(atMs: Self.nowMs()))
        applyPendingIfIdle()
    }

    private func schedule() {
        tick?.cancel()
        tick = nil
        guard let deadline = keyer.nextDeadlineMs else {
            applyPendingIfIdle()
            return
        }
        let delayMs = max(0, deadline - Self.nowMs())
        tick = Task { [weak self] in
            // Zero tolerance: the default lets the system coalesce the wake-up,
            // which at 30+ WPM is a noticeable fraction of a dit.
            try? await Task.sleep(until: .now + .microseconds(Int64(delayMs * 1000)),
                                  tolerance: .zero, clock: .continuous)
            guard !Task.isCancelled, let self else { return }
            self.emit(self.keyer.advance(toMs: max(deadline, Self.nowMs())))
            self.schedule()
        }
    }

    private func applyPendingIfIdle() {
        guard let p = pending, !keyer.isBusy, !ditHeld, !dahHeld else { return }
        pending = nil
        if p.mode != keyer.mode || abs(1200 / max(1, p.wpm) - keyer.unitMs) > 1e-9 {
            keyer = PaddleKeyer(mode: p.mode, wpm: p.wpm)
        }
    }

    private func emit(_ edges: [PaddleKeyer.Edge]) {
        for edge in edges {
            onKey?(edge.isDown, Int64(edge.atMs.rounded()))
        }
    }

    private static func nowMs() -> Double {
        Date().timeIntervalSince1970 * 1000
    }
}

/// Two side-by-side touch paddles, dit on the left unless swapped. Each is its
/// own press-and-hold target (a `DragGesture` with `minimumDistance: 0`, like
/// the straight key), so two fingers can hold both at once for a squeeze.
struct OnScreenPaddlesView: View {
    let mode: PaddleKeyer.Mode
    let wpm: Double
    let swapped: Bool
    let onKey: (Bool, Int64) -> Void
    @StateObject private var driver: PaddleKeyerDriver

    init(mode: PaddleKeyer.Mode, wpm: Double, swapped: Bool,
         onKey: @escaping (Bool, Int64) -> Void) {
        self.mode = mode
        self.wpm = wpm
        self.swapped = swapped
        self.onKey = onKey
        _driver = StateObject(wrappedValue: PaddleKeyerDriver(mode: mode, wpm: wpm))
    }

    var body: some View {
        HStack(spacing: 10) {
            paddle(swapped ? .dah : .dit)
            paddle(swapped ? .dit : .dah)
        }
        .onAppear {
            driver.onKey = onKey
            driver.configure(mode: mode, wpm: wpm)
        }
        .onDisappear { driver.stop() }
        .onChange(of: mode) { driver.configure(mode: $0, wpm: wpm) }
        .onChange(of: wpm) { driver.configure(mode: mode, wpm: $0) }
    }

    private func paddle(_ element: PaddleKeyer.Element) -> some View {
        let pressed = driver.isHeld(element)
        return ZStack {
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .fill(pressed ? Theme.teal : Theme.navyRaised)
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .strokeBorder(pressed ? Theme.tealBright : Theme.hairline, lineWidth: pressed ? 2 : 1)
            VStack(spacing: 4) {
                Text(element == .dit ? "•" : "—")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(pressed ? Theme.navy : Theme.teal)
                Text(element == .dit ? "DIT" : "DAH")
                    .font(.system(size: 12, weight: .bold)).tracking(1.5)
                    .foregroundStyle(pressed ? Theme.navy : Theme.textSecondary)
                Text(mode.label)
                    .font(.caption2)
                    .foregroundStyle(pressed ? Theme.navy.opacity(0.8) : Theme.textSecondary.opacity(0.8))
            }
        }
        .scaleEffect(pressed ? 0.98 : 1)
        .animation(.easeOut(duration: 0.06), value: pressed)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in driver.paddle(element, isDown: true) }
                .onEnded { _ in driver.paddle(element, isDown: false) }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(element == .dit ? "Dit paddle" : "Dah paddle")
        .accessibilityHint("Hold to send \(element == .dit ? "dits" : "dahs"); hold both paddles to alternate")
        .accessibilityAddTraits(.allowsDirectInteraction)
    }
}

/// The on-screen key the operator chose in Settings: the screen's own straight
/// key, or the paddles in its place. `onKey` receives each key edge and when
/// it happened; the straight key keeps calling its screen directly.
struct OnScreenKeySwitch<Straight: View>: View {
    @EnvironmentObject var model: AppModel
    let onKey: (Bool, Int64) -> Void
    @ViewBuilder let straight: () -> Straight

    var body: some View {
        if model.settings.onScreenKey == .paddles {
            OnScreenPaddlesView(mode: model.settings.paddleMode,
                                wpm: model.settings.wpm,
                                swapped: model.settings.paddleSwap,
                                onKey: onKey)
        } else {
            straight()
        }
    }
}
