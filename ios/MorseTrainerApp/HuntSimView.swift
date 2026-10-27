import SwiftUI

// The hunt simulator (#313, docs/operating-procedure-design.md): a hunter's
// side of a POTA pileup against a simulated activator. Setup (difficulty), the
// run (the activator sends, the pileup calls, you key — on the on-screen key
// or a Vail / BLE-MIDI key — with an XIT control for your offset), and a
// debrief of what you heard against what the activator heard, each graded
// point linked to its lesson. The rules are MorseKit's `HuntSession`; this file
// only sounds, shows and records them. Twin of the Kotlin `HuntSimScreen` on
// Android and desktop.
//
// Not ranked, no leaderboard entry and no session record (maintainer,
// 2026-10-03). A graded window counts as practice for the day.

// MARK: - Sound

extension AppModel {
    /// The activator's transmission at your tone. Returns its length in seconds.
    @discardableResult
    func playHuntActivator(_ text: String, wpm: Double) -> TimeInterval {
        operatingGeneration += 1
        operatingClipGeneration += 1
        let tone = settings.toneFrequency
        let t = MorseTiming(wpm: wpm)
        let synth = MorseSynth(playable: .text(text), timing: t, sampleRate: 44_100, frequency: tone)
        player.replaySound(playable: .text(text), frequency: tone, timing: t)
        return Double(synth.totalSamples) / 44_100
    }

    /// The hunters you can hear, at their offsets, levels and delays, through
    /// the pileup mixer (which this does not change), with the band noise.
    func playHuntPileup(_ turn: HuntTurn, qrn: Double) {
        let tone = settings.toneFrequency
        let voices = turn.voices.filter { $0.levelYou > 0 }.map { v in
            MorsePlayer.PileupVoice(text: v.text, frequency: OperatingProcedure.audible(tone + v.offsetHz),
                                    timing: MorseTiming(wpm: v.wpm), gain: Float(v.levelYou),
                                    startDelay: v.delay, qsbRate: v.qsb ? 0.25 : nil)
        }
        player.playPileup(voices, qrn: Float(qrn * 0.5)) {}
    }
}

// MARK: - The words

enum HuntCopy {
    static let points: [String: String] = [
        "timing.late": "You started after the activator had moved on.",
        "timing.ok": "You called when the activator asked for calls.",
        "timing.over": "You called over a contact in progress; the activator may have to ask the other station for it again (AGN?).",
        "call.once": "You sent your full call once.",
        "call.repeated": "You sent your call more than once. Once is enough.",
        "call.extra": "You sent more than your call. Just your call, once.",
        "call.missing": "You did not send your call.",
        "offset.ok": "Your transmit offset was in the right range.",
        "offset.zeroBeat": "You called on the activator’s frequency in a pileup. Use XIT to call 20 to 100 Hz off.",
        "offset.far": "You called too far from the activator. Stay within 100 Hz.",
        "partial.answered": "The partial fit your call and you answered it with your call.",
        "near.answered": "The call with a question mark was one letter from yours, and you sent your call once.",
        "partial.notMine": "The partial did not fit your call. Stay silent.",
        "partial.silent": "The activator was asking for you, and you did not answer.",
        "near.noQuestion": "The activator was working a call near yours, without a question mark. That is someone else: stay silent.",
        "exchange.ok": "Your exchange was one of the accepted forms.",
        "exchange.form": "Send just the report, your state and 73 (or BK), nothing more.",
        "exchange.silent": "The activator was working you, and you did not send your exchange.",
        "signoff.ok": "You signed off with the dits.",
        "signoff.form": "Sign off with E E (or 73 E E) after TU 73 E E.",
        "signoff.missing": "The activator signed off to you, and you did not send the dits back.",
        "as.ignored": "The activator sent AS, please wait. You called anyway.",
    ]

    static func point(_ code: String) -> String { points[code] ?? code }

    static func kind(_ k: HuntKind) -> String {
        switch k {
        case .calling: return "calling"
        case .query: return "partial or question"
        case .exchange: return "exchange"
        case .close: return "sign-off"
        case .as: return "please wait"
        }
    }

    static let noiseLevels: [Double] = [0, 0.08, 0.15, 0.25]
    static let noiseNames = ["off", "light", "medium", "heavy"]
    static let bustLevels: [Double] = [0, 0.1, 0.25, 0.4]
    static let bustNames = ["never", "rarely", "sometimes", "often"]

    static func nearest(_ levels: [Double], _ v: Double) -> Int {
        var best = 0
        for i in 0..<levels.count where abs(levels[i] - v) < abs(levels[best] - v) { best = i }
        return best
    }

    static func signed(_ v: Double) -> String {
        let i = Int(v.rounded())
        return i >= 0 ? "+\(i)" : "\(i)"
    }
}

// MARK: - The page

struct HuntSimView: View {
    let call: String
    let state: String
    let onOpenLesson: (OpLesson) -> Void

    private enum Stage {
        case setup, run(Int), debrief(HuntSession)
    }

    @State private var config = HuntConfig.medium
    @State private var stage: Stage = .setup
    @State private var runs = 0

    var body: some View {
        switch stage {
        case .setup:
            HuntSetup(config: $config) {
                runs += 1
                stage = .run(runs)
            }
        case .run(let n):
            HuntRunView(config: config, call: call, state: state) { stage = .debrief($0) }
                .id(n)
        case .debrief(let session):
            HuntDebrief(session: session, onAgain: { runs += 1; stage = .run(runs) },
                        onSettings: { stage = .setup }, onOpenLesson: onOpenLesson)
        }
    }
}

// MARK: - Setup

private struct HuntSetup: View {
    @Binding var config: HuntConfig
    let onStart: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text("You are a hunter. A simulated activator works a pileup; you call, answer and sign off by keying. It is not ranked and nothing here is timed for speed: it is graded on procedure, and every point links to its lesson.")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 12) {
                Text("DIFFICULTY").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
                HStack(spacing: 8) {
                    presetButton("Easy", .easy)
                    presetButton("Medium", .medium)
                    presetButton("Hard", .hard)
                }
                if ![HuntConfig.easy, .medium, .hard].contains(config) {
                    Text("Custom").font(.caption).foregroundStyle(Theme.textSecondary)
                }
                Stepper("Pileup size: \(config.pileupSize)", value: $config.pileupSize,
                        in: HuntConfig.minPileup...HuntConfig.maxPileup)
                Stepper("Hunter speed: \(Int(config.wpmMin))–\(Int(config.wpmMax)) WPM", value: speed, in: 10...36, step: 2)
                Toggle("QSB (signals fade)", isOn: $config.qsb).tint(Theme.teal)
                Stepper("Band noise: \(HuntCopy.noiseNames[HuntCopy.nearest(HuntCopy.noiseLevels, config.qrn)])",
                        value: noiseIndex, in: 0...(HuntCopy.noiseLevels.count - 1))
                Stepper("Hunters you cannot hear: \(Int(config.inaudiblePercent))%", value: $config.inaudiblePercent,
                        in: 0...80, step: 10)
                Stepper("Activator mis-copies: \(HuntCopy.bustNames[HuntCopy.nearest(HuntCopy.bustLevels, config.bustRate)])",
                        value: bustIndex, in: 0...(HuntCopy.bustLevels.count - 1))
            }
            .padding(14)
            .brandCard()
            Button {
                Haptics.tap()
                onStart()
            } label: {
                Text("Start")
                    .font(.headline)
                    .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
        }
    }

    private func presetButton(_ name: String, _ preset: HuntConfig) -> some View {
        let on = config == preset
        return Button {
            Haptics.selection()
            config = preset
        } label: {
            Text(name)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(on ? Theme.navy : .white)
                .frame(maxWidth: .infinity, minHeight: 40)
                .background(on ? Theme.teal : Theme.navyRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    /// Both ends of the hunter speed range move together; the activator sends at the middle.
    private var speed: Binding<Double> {
        Binding(
            get: { config.wpmMin },
            set: { lo in
                let spread = config.wpmMax - config.wpmMin
                config.wpmMin = lo
                config.wpmMax = lo + spread
                config.activatorWpm = ((lo + lo + spread) / 2).rounded()
            }
        )
    }

    private var noiseIndex: Binding<Int> {
        Binding(
            get: { HuntCopy.nearest(HuntCopy.noiseLevels, config.qrn) },
            set: { config.qrn = HuntCopy.noiseLevels[$0] }
        )
    }

    private var bustIndex: Binding<Int> {
        Binding(
            get: { HuntCopy.nearest(HuntCopy.bustLevels, config.bustRate) },
            set: { config.bustRate = HuntCopy.bustLevels[$0] }
        )
    }
}

// MARK: - The run

/// Plays a session out in real time: the activator's turn, the pileup's
/// window, your answer; round after round. Your first key-down in a window is
/// your start time, and your XIT then is your offset.
@MainActor
final class HuntRunner: ObservableObject {
    enum Phase { case activator, listen, wait }

    @Published private(set) var phase: Phase = .activator
    @Published private(set) var window = 1
    @Published private(set) var log: [String] = []
    @Published var xit: Double = 0
    @Published private(set) var finished: HuntSession?

    let session: HuntSession
    let keyer: SendingKeyer
    private let config: HuntConfig
    private weak var model: AppModel?
    private var loop: Task<Void, Never>?
    private var deadline: Task<Void, Never>?
    private var idle: Task<Void, Never>?
    private var waiter: CheckedContinuation<HuntYouSend?, Never>?
    private var token = 0
    private var openedAt = Date()
    private var firstKeyAt: Date?
    private var edgeAtOpen = 0
    private var xitAtKey = 0.0
    private var lastText = ""
    private var lastKeying = false

    init(config: HuntConfig, call: String, state: String, wpm: Double, toneHz: Double) {
        self.config = config
        session = HuntSession(config: config, seed: UInt64.random(in: 0..<UInt64(1) << 53), myCall: call, myState: state)
        keyer = SendingKeyer(wpm: wpm, toneHz: toneHz)
    }

    func start(model: AppModel) {
        guard loop == nil else { return }
        self.model = model
        keyer.start()
        loop = Task { [weak self] in await self?.run() }
    }

    func stop() {
        loop?.cancel()
        deadline?.cancel()
        idle?.cancel()
        keyer.stop()
        model?.stopOperating()
        deliver(nil)
    }

    /// End the run now and go to the debrief.
    func quit() {
        session.quit()
        deliver(nil)
    }

    func staySilent() {
        if phase == .listen { deliver(nil) }
    }

    func sendNow() {
        guard phase == .listen else { return }
        let text = keyer.submit()
        guard !text.isEmpty, let model else { return }
        let start = firstKeyAt ?? Date()
        let delay = max(0, start.timeIntervalSince(openedAt))
        deliver(HuntYouSend(text: text, wpm: model.settings.wpm, delay: delay, offsetHz: xitAtKey))
    }

    private func deliver(_ you: HuntYouSend?) {
        guard let w = waiter else { return }
        waiter = nil
        deadline?.cancel()
        idle?.cancel()
        w.resume(returning: you)
    }

    /// The panel tells the runner about each change of the key.
    func keyEdge(_ n: Int) {
        if phase == .listen, firstKeyAt == nil, n != edgeAtOpen {
            firstKeyAt = Date()
            xitAtKey = xit
        }
        keyChanged(text: nil, keying: nil)
    }

    func keyChanged(text: String?, keying: Bool?) {
        if let text { lastText = text }
        if let keying { lastKeying = keying }
        idle?.cancel()
        guard phase == .listen, !lastKeying, !lastText.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let wait = max(1.5, keyer.wordGapMs * 2.5 / 1000)
        idle = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.sendNow()
        }
    }

    private func run() async {
        guard let model else { return }
        while !session.ended && !Task.isCancelled {
            let turn = session.turn
            model.stopOperating()
            phase = .activator
            window = session.round + 1
            keyer.clear()
            firstKeyAt = nil
            log.append(turn.text)
            let seconds = model.playHuntActivator(turn.text, wpm: turn.wpm)
            try? await Task.sleep(nanoseconds: UInt64((seconds + 0.25) * 1_000_000_000))
            if Task.isCancelled || session.ended { break }
            if !keyer.isKeying { keyer.clear() }
            openedAt = Date()
            firstKeyAt = nil
            edgeAtOpen = keyer.edgeCount
            token += 1
            let mine = token
            phase = .listen
            deadline = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(HuntModel.listenSeconds * 1_000_000_000))
                guard !Task.isCancelled, let self, self.token == mine, self.phase == .listen, self.firstKeyAt == nil else { return }
                self.deliver(nil)
            }
            model.playHuntPileup(turn, qrn: config.qrn)
            let you: HuntYouSend? = await withCheckedContinuation { (c: CheckedContinuation<HuntYouSend?, Never>) in
                waiter = c
            }
            if Task.isCancelled { break }
            phase = .wait
            keyer.clear()
            session.respond(you)
            model.noteOperatingPractice()
            try? await Task.sleep(nanoseconds: 350_000_000)
        }
        model.stopOperating()
        if !Task.isCancelled { finished = session }
    }
}

private struct HuntRunView: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var runner: HuntRunner
    let onDone: (HuntSession) -> Void

    init(config: HuntConfig, call: String, state: String, onDone: @escaping (HuntSession) -> Void) {
        self.onDone = onDone
        // The model's speed and tone are read in onAppear's start; the keyer
        // only needs them to decode, so use the saved settings directly.
        let s = AppSettings.load()
        _runner = StateObject(wrappedValue: HuntRunner(config: config, call: call, state: state,
                                                      wpm: s.wpm, toneHz: s.toneFrequency))
    }

    var body: some View {
        VStack(spacing: 14) {
            statusCard
            xitCard
            HuntKeyPanel(runner: runner, sender: runner.keyer)
            Button("Stop and see the debrief") {
                runner.quit()
            }
            .font(.subheadline)
            .foregroundStyle(Theme.textSecondary)
        }
        .onAppear { runner.start(model: model) }
        .onDisappear { runner.stop() }
        .onChange(of: runner.finished?.round) { _ in
            if let s = runner.finished { onDone(s) }
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("WINDOW \(runner.window)").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
            Text(statusText)
                .font(.headline)
                .foregroundStyle(runner.phase == .listen ? Theme.tealBright : .white)
            Text("THE ACTIVATOR").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
            ForEach(Array(runner.log.suffix(5).enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(Theme.copyFont(size: 15, weight: .regular, monospaced: true, slashedZero: model.settings.slashedZero))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .brandCard()
    }

    private var statusText: String {
        switch runner.phase {
        case .activator: return "The activator is sending…"
        case .listen: return "Your turn: key now, or stay silent"
        case .wait: return "One moment…"
        }
    }

    private var xitCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("YOUR TRANSMIT OFFSET (XIT)").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
            HStack(spacing: 6) {
                ForEach(OperatingProcedure.knobSteps, id: \.self) { step in
                    Button {
                        Haptics.selection()
                        runner.xit = min(OperatingProcedure.ritRangeHz, max(-OperatingProcedure.ritRangeHz, runner.xit + step))
                    } label: {
                        Text(HuntCopy.signed(step))
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 36)
                    }
                    .buttonStyle(.bordered)
                    .tint(Theme.teal)
                }
            }
            HStack {
                Text("XIT \(HuntCopy.signed(runner.xit)) Hz")
                    .font(.system(.body, design: .monospaced).weight(.semibold))
                Spacer()
                Button("Zero") { runner.xit = 0 }.font(.subheadline)
            }
            Text("In a pileup, call 20 to 100 Hz off the activator, set before you key.")
                .font(.caption).foregroundStyle(Theme.textSecondary)
        }
        .padding(14)
        .brandCard()
    }
}

/// The on-screen key (straight or paddles, per Settings) and any hardware key,
/// decoded by the runner's `SendingKeyer`. Send, Clear and Stay silent are here
/// too; the run sends by itself once the key has been idle a moment.
private struct HuntKeyPanel: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var runner: HuntRunner
    @ObservedObject var sender: SendingKeyer
    @State private var keyPressed = false
    @Environment(\.wideLayout) private var wide
    private var keyHeight: CGFloat { wide ? 150 : 100 }

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text("YOU SENT").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
                Spacer()
                if !sender.midiDeviceNames.isEmpty {
                    Label(sender.midiDeviceNames.joined(separator: ", "), systemImage: "pianokeys")
                        .font(.caption).foregroundStyle(Theme.teal).lineLimit(1)
                }
            }
            Text(sender.decodedText.isEmpty ? "—" : sender.decodedText)
                .font(Theme.copyFont(size: 26, weight: .semibold, monospaced: true, slashedZero: model.settings.slashedZero))
                .foregroundStyle(sender.decodedText.isEmpty ? Theme.textSecondary : .white)
                .lineLimit(1).minimumScaleFactor(0.5)
                .frame(maxWidth: .infinity, minHeight: 36)
            OnScreenKeySwitch(onKey: { down, ms in sender.touchKey(isDown: down, atMs: ms) }) {
                ZStack {
                    RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                        .fill(keyPressed ? Theme.teal : Theme.navyRaised)
                    RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                        .strokeBorder(keyPressed ? Theme.tealBright : Theme.hairline, lineWidth: keyPressed ? 2 : 1)
                    Text("HOLD TO KEY")
                        .font(.system(size: 12, weight: .bold)).tracking(1.5)
                        .foregroundStyle(keyPressed ? Theme.navy : Theme.textSecondary)
                }
                .frame(height: keyHeight)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { _ in
                            if !keyPressed { keyPressed = true; sender.touchKey(isDown: true) }
                        }
                        .onEnded { _ in
                            if keyPressed { keyPressed = false; sender.touchKey(isDown: false) }
                        }
                )
                .accessibilityLabel("Morse key")
                .accessibilityHint("Press and hold to send each dit and dah")
            }
            .frame(height: keyHeight)
            HStack(spacing: 12) {
                Button { sender.clear() } label: {
                    Label("Clear", systemImage: "delete.left").frame(maxWidth: .infinity, minHeight: 40)
                }
                .buttonStyle(.bordered)
                .tint(Theme.textSecondary)
                Button { runner.sendNow() } label: {
                    Label("Send", systemImage: "checkmark")
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 40)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .disabled(runner.phase != .listen || sender.decodedText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Button { runner.staySilent() } label: {
                Text("Stay silent").frame(maxWidth: .infinity, minHeight: 40)
            }
            .buttonStyle(.bordered)
            .tint(Theme.teal)
            .disabled(runner.phase != .listen)
        }
        .padding(14)
        .brandCard()
        .onChange(of: sender.edgeCount) { runner.keyEdge($0) }
        .onChange(of: sender.decodedText) { runner.keyChanged(text: $0, keying: nil) }
        .onChange(of: sender.isKeying) { runner.keyChanged(text: nil, keying: $0) }
    }
}

// MARK: - The debrief

private struct HuntDebrief: View {
    @EnvironmentObject var model: AppModel
    let session: HuntSession
    let onAgain: () -> Void
    let onSettings: () -> Void
    let onOpenLesson: (OpLesson) -> Void

    private var points: [HuntPoint] { session.records.flatMap { $0.grade } }

    var body: some View {
        VStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                Text(headline)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(session.outcome == .logged ? Theme.tealBright : .white)
                Text("\(points.filter { $0.good }.count) right, \(points.filter { !$0.good }.count) to work on")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .brandCard()
            HStack(spacing: 12) {
                Button {
                    Haptics.tap()
                    onAgain()
                } label: {
                    Text("Go again").font(.headline).foregroundStyle(Theme.navy).frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                Button { onSettings() } label: {
                    Text("Change difficulty").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .tint(Theme.teal)
            }
            Text("DEBRIEF").font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            ForEach(session.records, id: \.round) { windowCard($0) }
        }
    }

    private var headline: String {
        switch session.outcome {
        case .logged: return "You are in the log"
        case .timeout: return "The pileup went on without you"
        default: return "Stopped early"
        }
    }

    private func mono(_ text: String, weight: Font.Weight = .regular, secondary: Bool = false) -> some View {
        Text(text)
            .font(Theme.copyFont(size: 13, weight: weight, monospaced: true, slashedZero: model.settings.slashedZero))
            .foregroundStyle(secondary ? Theme.textSecondary : .white)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func windowCard(_ r: HuntRecord) -> some View {
        let calls = Dictionary(uniqueKeysWithValues: r.turn.voices.map { (String($0.id), $0.call) })
        func who(_ id: String) -> String { id == "you" ? "You" : (calls[id] ?? id) }
        let inaudible = r.turn.voices.filter { $0.levelYou <= 0 }
        return VStack(alignment: .leading, spacing: 6) {
            Text("WINDOW \(r.round) · \(HuntCopy.kind(r.turn.kind).uppercased())")
                .font(.system(size: 10, weight: .bold)).tracking(1.5).foregroundStyle(Theme.textSecondary)
            mono("The activator sent: \(r.turn.text)")
            Text("What you heard").font(.caption.weight(.bold)).foregroundStyle(Theme.textSecondary)
            if r.youHeard.isEmpty && inaudible.isEmpty { mono("nobody", secondary: true) }
            ForEach(Array(r.youHeard.enumerated()), id: \.offset) { _, h in mono("\(who(h.who)): \(h.decoded)") }
            ForEach(Array(inaudible.enumerated()), id: \.offset) { _, v in
                mono("\(v.call) (you could not hear them)", secondary: true)
            }
            Text("What the activator heard").font(.caption.weight(.bold)).foregroundStyle(Theme.textSecondary)
            if r.activatorHeard.isEmpty { mono("nobody", secondary: true) }
            ForEach(Array(r.activatorHeard.enumerated()), id: \.offset) { _, h in mono("\(who(h.who)): \(h.decoded)") }
            mono(yourLine(r), weight: .semibold)
            ForEach(Array(r.grade.enumerated()), id: \.offset) { _, p in
                VStack(alignment: .leading, spacing: 2) {
                    Text((p.good ? "✓ " : "✗ ") + HuntCopy.point(p.code))
                        .font(.footnote)
                        .foregroundStyle(p.good ? Theme.tealBright : .orange)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Lesson: \(OpCopy.title(p.lesson))") { onOpenLesson(p.lesson) }
                        .font(.caption)
                        .tint(Theme.teal)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }

    private func yourLine(_ r: HuntRecord) -> String {
        guard let you = r.you, !you.text.trimmingCharacters(in: .whitespaces).isEmpty else { return "You stayed silent" }
        if r.late { return "You sent: \(you.text.uppercased()) (too late to be heard)" }
        return "You sent: \(you.text.uppercased()) (XIT \(HuntCopy.signed(you.offsetHz)) Hz)"
    }
}
