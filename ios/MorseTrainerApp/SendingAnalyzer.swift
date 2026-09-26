import Foundation
import SwiftUI

/// Drives the Sending Analyzer (#241, #234, #235): pick a text, send it on any
/// key — on-screen, a Vail adapter or other MIDI key, or a tone the microphone
/// hears — and get back what was copied, how it lines up against the text, and
/// how the elements and spacing compare with 1:3 / 1:3:7.
///
/// The analysis is MorseKit's `SendingAnalysis`; this only collects key edges
/// into a `KeyingRecorder` and keeps the analyzer's own small settings and its
/// `SendingRecord`. Any keyer that can report "down at t / up at t" plugs in
/// through `keyEdge(isDown:atMs:)` — the on-screen paddles (#233) included.
@MainActor
final class SendingAnalyzerModel: ObservableObject {

    enum Phase { case setup, sending, results }

    enum Source: String, CaseIterable, Identifiable {
        case pangram, callsigns, words, groups, custom
        var id: String { rawValue }
        var title: String {
            switch self {
            case .pangram:   return "Pangram"
            case .callsigns: return "Call signs"
            case .words:     return "Words"
            case .groups:    return "Groups"
            case .custom:    return "Custom"
            }
        }
    }

    enum Input: String, CaseIterable, Identifiable {
        case key, microphone
        var id: String { rawValue }
        var title: String { self == .key ? "Key" : "Microphone" }
    }

    /// Microphone sensitivity: the detector's required tone-over-room ratio.
    enum Sensitivity: String, CaseIterable, Identifiable {
        case low, normal, high
        var id: String { rawValue }
        var title: String { rawValue.capitalized }
        var snr: Double {
            switch self {
            case .low: return 6
            case .normal: return 4
            case .high: return 2.5
            }
        }
    }

    @Published private(set) var phase: Phase = .setup
    @Published private(set) var target = ""
    @Published private(set) var liveText = ""
    @Published private(set) var analysis: SendingAnalysis?
    @Published private(set) var record: SendingRecord
    /// Whether this attempt was keyed on the on-screen paddles (#233). Their
    /// keyer times every element at the app's Settings speed, so the attempt
    /// is judged as paddles whatever the key picker says — the picker is for
    /// the key the operator brings (a Vail adapter, a MIDI key, the mic).
    @Published private(set) var onScreenPaddlesUsed = false

    @Published var source: Source { didSet { save(); if source != oldValue { newTarget() } } }
    @Published var customText: String { didSet { save(); if source == .custom { newTarget() } } }
    @Published var keyType: SendingKeyType { didSet { save() } }
    @Published var input: Input { didSet { save(); inputChanged() } }
    @Published var sensitivity: Sensitivity { didSet { save(); mic.setSensitivity(sensitivity.snr) } }
    @Published var targetWpm: Double { didSet { save() } }
    @Published var farnsworth: Bool { didSet { save() } }
    @Published var effectiveWpm: Double { didSet { save() } }

    let keyer: SendingKeyer
    let mic = SendingMicInput()
    private var recorder = KeyingRecorder()
    private var finishTask: Task<Void, Never>?
    private var visible = false
    private let studied: [Character]

    private static let prefix = "sendingAnalyzer."
    static let recordKey = "sendingAnalyzer.record.v1"

    /// `keyerWpm` is the app's speed setting: what a Vail adapter's own keyer
    /// sends at (paddle modes), as in every other keyed mode. The speed the
    /// attempt is *judged* against is the analyzer's own `targetWpm`.
    init(toneHz: Double, keyerWpm: Double, studied: [Character]) {
        let d = UserDefaults.standard
        source = Source(rawValue: d.string(forKey: Self.prefix + "source") ?? "") ?? .pangram
        customText = d.string(forKey: Self.prefix + "custom") ?? ""
        keyType = SendingKeyType(rawValue: d.string(forKey: Self.prefix + "keyType") ?? "") ?? .straight
        input = Input(rawValue: d.string(forKey: Self.prefix + "input") ?? "") ?? .key
        sensitivity = Sensitivity(rawValue: d.string(forKey: Self.prefix + "sensitivity") ?? "") ?? .normal
        let wpm = d.double(forKey: Self.prefix + "wpm")
        targetWpm = wpm >= 5 ? min(50, wpm) : 20
        farnsworth = d.bool(forKey: Self.prefix + "farnsworth")
        let eff = d.double(forKey: Self.prefix + "effectiveWpm")
        effectiveWpm = eff >= 5 ? eff : 10
        record = Self.loadRecord()
        self.studied = studied
        keyer = SendingKeyer(wpm: keyerWpm, toneHz: toneHz)
        keyer.onEdge = { [weak self] isDown, ms in
            guard let self, self.input == .key else { return }
            self.keyEdge(isDown: isDown, atMs: Double(ms))
        }
        mic.onEdge = { [weak self] isDown, ms in
            guard let self, self.input == .microphone else { return }
            self.keyEdge(isDown: isDown, atMs: ms)
        }
        newTarget()
    }

    // MARK: Lifecycle

    func appear() {
        visible = true
        inputChanged()
    }

    func disappear() {
        visible = false
        finishTask?.cancel()
        keyer.stop()
        mic.stop()
    }

    /// Only one input runs at a time: the microphone would otherwise hear the
    /// key's own sidetone. The mic runs from setup on, so its meter doubles as
    /// the level check before sending.
    private func inputChanged() {
        guard visible else { return }
        switch input {
        case .key:
            mic.stop()
            keyer.start()
        case .microphone:
            keyer.stop()
            mic.start(sensitivity: sensitivity.snr, manualPitchHz: nil)
        }
    }

    /// Re-arm the microphone's pitch search (after it locked onto the wrong
    /// sound, say).
    func refindPitch() {
        guard input == .microphone else { return }
        // The detector's clock restarts with it, so an attempt in progress
        // restarts too rather than mixing two timelines.
        if phase == .sending {
            finishTask?.cancel()
            recorder.reset()
            liveText = ""
        }
        mic.stop()
        mic.start(sensitivity: sensitivity.snr, manualPitchHz: nil)
    }

    // MARK: Target

    func newTarget() {
        var rng = SystemRandomNumberGenerator()
        let text: String
        switch source {
        case .pangram:
            let others = SendingTargets.pangrams.filter { $0 != target }
            text = others.randomElement() ?? SendingTargets.pangrams[0]
        case .callsigns:
            text = (0..<5).map { _ in
                CallsignGenerator.generate(formats: CallsignFormat.commonDefaults, usOnly: false, using: &rng)
            }.joined(separator: " ")
        case .words:
            let pool = Array(MorseData.rankedWords.prefix(200))
            text = (0..<6).compactMap { _ in pool.randomElement() }.joined(separator: " ")
        case .groups:
            let sheet = SendingDrill.generate(kind: .studied, studied: studied,
                                              groupCount: 5, groupSize: 5, groupsPerRow: 5)
            text = sheet.rows.joined(separator: " ")
        case .custom:
            text = customText
        }
        target = SendingAnalysis.normalizedTarget(text)
        if phase == .results { phase = .setup }
    }

    var canStart: Bool { !target.isEmpty }

    // MARK: Sending

    /// The key type the attempt is judged as: paddles when any of it came
    /// from the on-screen paddles, otherwise the picker's choice.
    var judgedKeyType: SendingKeyType { onScreenPaddlesUsed ? .keyer : keyType }

    func startSending() {
        guard canStart else { return }
        recorder.reset()
        onScreenPaddlesUsed = false
        liveText = ""
        analysis = nil
        phase = .sending
        if input == .microphone, !mic.isListening { inputChanged() }
    }

    /// An edge from the on-screen paddles, at the time their keyer scheduled
    /// it. It goes through the keyer like the straight key's press does (so
    /// the sidetone sounds), and the keyer's `onEdge` hands the same time on
    /// to `keyEdge` — the element lengths recorded are the keyer's, never the
    /// main actor's wake-up jitter.
    func onScreenPaddleEdge(isDown: Bool, atMs ms: Int64) {
        guard input == .key else { return }
        if phase == .sending { onScreenPaddlesUsed = true }
        keyer.touchKey(isDown: isDown, atMs: ms)
    }

    /// One edge from any input.
    func keyEdge(isDown: Bool, atMs ms: Double) {
        guard phase == .sending else { return }
        finishTask?.cancel()
        if isDown {
            recorder.keyDown(atMs: ms)
            return
        }
        recorder.keyUp(atMs: ms)
        let live = analyse()
        liveText = live.decodedText
        // Once everything has been sent, a pause finishes the attempt: longer
        // than any word gap at the speed actually sent, never under 1.5 s.
        let sentCount = live.sentCharacters.count
        let wanted = target.filter { $0 != " " }.count
        guard sentCount >= wanted else { return }
        let wait = min(4, max(1.5, live.unitMs * 12 / 1000))
        finishTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard let self, !Task.isCancelled, !self.recorder.isDown else { return }
            self.finish()
        }
    }

    private func analyse() -> SendingAnalysis {
        SendingAnalysis(marks: recorder.marks, target: target, keyType: judgedKeyType,
                        characterWpm: targetWpm,
                        effectiveWpm: farnsworth ? min(effectiveWpm, targetWpm) : targetWpm)
    }

    func finish() {
        finishTask?.cancel()
        guard phase == .sending else { return }
        let result = analyse()
        analysis = result
        if !result.isEmpty {
            record.record(result)
            saveRecord()
        }
        phase = .results
    }

    func sendAgain() {
        startSending()
    }

    func cancelSending() {
        finishTask?.cancel()
        recorder.reset()
        onScreenPaddlesUsed = false
        liveText = ""
        phase = .setup
    }

    func clearRecord() {
        record = SendingRecord()
        UserDefaults.standard.removeObject(forKey: Self.recordKey)
    }

    // MARK: Persistence

    private func save() {
        let d = UserDefaults.standard
        d.set(source.rawValue, forKey: Self.prefix + "source")
        d.set(customText, forKey: Self.prefix + "custom")
        d.set(keyType.rawValue, forKey: Self.prefix + "keyType")
        d.set(input.rawValue, forKey: Self.prefix + "input")
        d.set(sensitivity.rawValue, forKey: Self.prefix + "sensitivity")
        d.set(targetWpm, forKey: Self.prefix + "wpm")
        d.set(farnsworth, forKey: Self.prefix + "farnsworth")
        d.set(effectiveWpm, forKey: Self.prefix + "effectiveWpm")
    }

    private func saveRecord() {
        if let data = try? JSONEncoder().encode(record) {
            UserDefaults.standard.set(data, forKey: Self.recordKey)
        }
    }

    private static func loadRecord() -> SendingRecord {
        guard let data = UserDefaults.standard.data(forKey: recordKey),
              let r = try? JSONDecoder().decode(SendingRecord.self, from: data) else { return SendingRecord() }
        return r
    }
}
