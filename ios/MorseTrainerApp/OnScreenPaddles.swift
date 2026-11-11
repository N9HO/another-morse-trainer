import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

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

// MARK: - Touch

/// Hold-to-key touch input for every on-screen key, straight or paddle.
///
/// It replaces `DragGesture(minimumDistance: 0)`, which failed a Morse key two
/// ways. Inside a scroll view it heard a touch only once the scroll view gave
/// it up, so presses keyed late and quick taps vanished (#363; a dit at 33 WPM
/// is a 36 ms tap). And SwiftUI never ends a cancelled gesture, so Control
/// Center or an alert mid-press left the key down. A UIKit recognizer gets the
/// touch as it lands, beats the scroll view's pan and sees every cancellation.
/// Edges carry the touch's own timestamp, so timing measures the press, not
/// the main thread.
private final class KeyPressRecognizer: UIGestureRecognizer {
    /// Fingers on this key now; the key is down while any is.
    private var fingers = Set<UITouch>()
    /// When the last state change happened, on the touch clock
    /// (`ProcessInfo.systemUptime`).
    private(set) var changedAtUptime: TimeInterval = 0

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        cancelsTouchesInView = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        let wasUp = fingers.isEmpty
        fingers.formUnion(touches)
        guard wasUp, state == .possible else { return }
        changedAtUptime = touches.map(\.timestamp).min() ?? event.timestamp
        state = .began
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        fingers.subtract(touches)
        guard fingers.isEmpty, state == .began || state == .changed else { return }
        changedAtUptime = touches.map(\.timestamp).max() ?? event.timestamp
        state = .ended
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        fingers.subtract(touches)
        guard fingers.isEmpty, state == .began || state == .changed else { return }
        changedAtUptime = ProcessInfo.processInfo.systemUptime
        state = .cancelled
    }

    override func reset() {
        super.reset()
        fingers.removeAll()
    }

    // Two keys may be held together (a squeeze on the paddles); a key press
    // prevents anything else, such as the enclosing scroll view's pan, and
    // nothing prevents it.
    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool {
        !(preventedGestureRecognizer is KeyPressRecognizer)
    }

    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool {
        false
    }
}

private struct KeyPressSurface: UIViewRepresentable {
    let onChange: (Bool, Int64) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isMultipleTouchEnabled = true
        // The SwiftUI key above carries the label and hint; this view only
        // takes the touches (VoiceOver's direct interaction included).
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        let recognizer = KeyPressRecognizer(target: context.coordinator,
                                            action: #selector(Coordinator.changed(_:)))
        recognizer.delegate = context.coordinator
        view.addGestureRecognizer(recognizer)
        context.coordinator.onChange = onChange
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onChange = onChange
    }

    /// A key torn out of the hierarchy mid-press gets no touch callback.
    /// Release it after this update rather than during it, so the closure
    /// may set the screen's state.
    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        guard coordinator.isDown else { return }
        let onChange = coordinator.onChange
        let atMs = wallClockMs(uptime: ProcessInfo.processInfo.systemUptime)
        DispatchQueue.main.async { onChange(false, atMs) }
    }

    /// A touch timestamp (seconds of system uptime) as wall-clock ms, the
    /// clock every key edge in the app is stamped with.
    static func wallClockMs(uptime: TimeInterval) -> Int64 {
        let age = max(0, ProcessInfo.processInfo.systemUptime - uptime)
        return Int64(((Date().timeIntervalSince1970 - age) * 1000).rounded())
    }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onChange: (Bool, Int64) -> Void = { _, _ in }
        private(set) var isDown = false

        @objc func changed(_ recognizer: UIGestureRecognizer) {
            guard let recognizer = recognizer as? KeyPressRecognizer else { return }
            switch recognizer.state {
            case .began:
                set(true, uptime: recognizer.changedAtUptime)
            case .ended, .cancelled, .failed:
                set(false, uptime: recognizer.changedAtUptime)
            default:
                break
            }
        }

        private func set(_ down: Bool, uptime: TimeInterval) {
            guard down != isDown else { return }
            isDown = down
            onChange(down, KeyPressSurface.wallClockMs(uptime: uptime))
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            other is KeyPressRecognizer
        }
    }
}

extension View {
    /// Make this view a hold-to-key Morse key: `onChange` gets each press
    /// (`true`) and release (`false`) the moment it happens, with the touch's
    /// own time in wall-clock ms. Releases on a cancelled touch too. See
    /// `KeyPressRecognizer` for why this is not a `DragGesture`.
    func keyPress(_ onChange: @escaping (_ isDown: Bool, _ atMs: Int64) -> Void) -> some View {
        overlay(KeyPressSurface(onChange: onChange))
    }
}

/// Two side-by-side touch paddles, dit on the left unless swapped. Each is its
/// own press-and-hold target (`keyPress`, like the straight key), so two
/// fingers can hold both at once for a squeeze.
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
        .keyPress { down, _ in
            driver.paddle(element, isDown: down)
        }
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

// MARK: - Shared key views

/// The hold-to-key pad every keying screen draws for the straight key: one
/// rounded pad that lights while held, keyed through `keyPress`. Each screen
/// keeps its own look (`Style`), size and VoiceOver hint. The Vail repeater's
/// key, which also shows break-in, is its own view (`RepeaterTouchKeyView`).
struct StraightKeyPad: View {
    struct Style {
        var label = "HOLD TO KEY"
        var labelSize: CGFloat = 12
        /// The label's colour while the pad is up.
        var idleLabelColor: Color = Theme.textSecondary
        /// An icon above the label (Sending Practice, the Sending Analyzer).
        var systemImage: String?
        /// Shrinks a little while held (Sending Practice's key).
        var squeezes = false

        /// The keying games' pad: the label alone.
        static var plain: Style { Style() }
        /// The radio-waves icon over the label.
        static var radio: Style { Style(systemImage: "dot.radiowaves.left.and.right") }
    }

    var style: Style = .plain
    let height: CGFloat
    let hint: String
    /// Lit whatever the touch is doing: the Sending Analyzer lights its pad
    /// for the merged key, hardware key included.
    var lit = false
    let onKey: (_ isDown: Bool, _ atMs: Int64) -> Void
    @State private var pressed = false

    var body: some View {
        let on = pressed || lit
        ZStack {
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .fill(on ? Theme.teal : Theme.navyRaised)
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .strokeBorder(pressed ? Theme.tealBright : Theme.hairline, lineWidth: pressed ? 2 : 1)
            VStack(spacing: 4) {
                if let systemImage = style.systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(on ? Theme.navy : Theme.teal)
                }
                Text(style.label)
                    .font(.system(size: style.labelSize, weight: .bold)).tracking(1.5)
                    .foregroundStyle(on ? Theme.navy : style.idleLabelColor)
            }
        }
        .frame(height: height)
        .scaleEffect(style.squeezes && pressed ? 0.98 : 1)
        .animation(style.squeezes ? .easeOut(duration: 0.06) : nil, value: pressed)
        .contentShape(Rectangle())
        .keyPress { down, ms in
            guard pressed != down else { return }
            pressed = down
            onKey(down, ms)
        }
        .accessibilityLabel("Morse key")
        .accessibilityHint(hint)
    }
}

/// The on-screen key the operator chose in Settings, keying `sender`: the
/// paddles, or a `StraightKeyPad`. What nine screens used to spell out by hand.
struct OnScreenKey: View {
    let sender: SendingKeyer
    var style: StraightKeyPad.Style = .plain
    let height: CGFloat
    let hint: String

    var body: some View {
        // Straight key or paddles, as chosen in Settings (#233); the paddles'
        // keyer feeds the same sidetone and decoder.
        OnScreenKeySwitch(onKey: { down, ms in sender.touchKey(isDown: down, atMs: ms) }) {
            StraightKeyPad(style: style, height: height, hint: hint) { down, ms in
                sender.touchKey(isDown: down, atMs: ms)
            }
        }
        .frame(height: height)
    }
}

/// A keying game's prompt line: what to key, what has been keyed so far, and
/// a mark when a hardware key is connected.
struct KeyedTextHeader: View {
    let prompt: String
    let text: String
    let slashedZero: Bool
    let hardwareKeyConnected: Bool

    var body: some View {
        HStack {
            Text(prompt)
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
            Spacer()
            Text(text.isEmpty ? "—" : text)
                .font(Theme.copyFont(size: 22, weight: .semibold, monospaced: true, slashedZero: slashedZero))
                .foregroundStyle(.white)
            if hardwareKeyConnected {
                Image(systemName: "pianokeys").foregroundStyle(Theme.teal)
                    .accessibilityLabel("Hardware key connected")
            }
        }
    }
}

/// The input of a game that is answered one keyed character at a time
/// (Invaders, Galaga, Asteroids): the on-screen key, with a hardware key
/// feeding the same decoder. Owns its `SendingKeyer`, built from Settings and
/// kept in step with them, and hands each finished character to `onCharacter`.
struct CharacterKeyPanel: View {
    let prompt: String
    let hint: String
    let keyer: KeyerSettings
    let slashedZero: Bool
    /// What the readout shows in place of the decoded text (Asteroids' label
    /// so far); nil shows the decoded text.
    var display: String?
    let onCharacter: (Character) -> Void
    @StateObject private var sender: SendingKeyer

    init(prompt: String, hint: String, keyer: KeyerSettings, slashedZero: Bool,
         display: String? = nil, onCharacter: @escaping (Character) -> Void) {
        self.prompt = prompt
        self.hint = hint
        self.keyer = keyer
        self.slashedZero = slashedZero
        self.display = display
        self.onCharacter = onCharacter
        _sender = StateObject(wrappedValue: SendingKeyer(keyer))
    }

    var body: some View {
        VStack(spacing: 10) {
            KeyedTextHeader(prompt: prompt,
                            text: display ?? (sender.decodedText.isEmpty ? "—" : sender.decodedText),
                            slashedZero: slashedZero,
                            hardwareKeyConnected: !sender.midiDeviceNames.isEmpty)
            OnScreenKey(sender: sender, height: 90, hint: hint)
        }
        .onAppear { sender.start() }
        .onDisappear { sender.stop() }
        .onChange(of: keyer) { sender.applyConfig($0) }
        // A finished character is sent at once; the decoder finalises it on
        // the letter gap, so nothing is fired mid-character.
        .onChange(of: sender.decodedText) { _ in keyedCharacter() }
        .onChange(of: sender.isKeying) { _ in keyedCharacter() }
    }

    private func keyedCharacter() {
        guard !sender.isKeying else { return }
        let text = sender.decodedText.trimmingCharacters(in: .whitespaces)
        guard let ch = text.first else { return }
        sender.clear()
        onCharacter(ch)
    }
}
