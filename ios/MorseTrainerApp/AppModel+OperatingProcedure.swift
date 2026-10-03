// AppModel+OperatingProcedure.swift
// CW Operating Procedure (#294, #295, docs/operating-procedure-design.md):
// the thin layer between the MorseKit rules (OperatingProcedure.swift) and
// the screen — where progress is kept, how clips, the pileup demo and the
// zero-beat drill are sounded, and what counts as practice.
//
// Not a session: nothing here writes a SessionRecord, touches the Koch
// ladder or submits to the leaderboard. A graded answer marks the day as
// practised, as a First Four answer does.

import Foundation

/// Where the section's progress lives: its own key, not `AppSettings`.
/// Twin of the Kotlin `OperatingProcedureStore` on Android and desktop.
enum OperatingProcedureStore {
    static let key = "MorseTrainer.operatingProcedure"

    static func load() -> OperatingProcedureProgress {
        guard let data = UserDefaults.standard.data(forKey: key),
              let progress = try? JSONDecoder().decode(OperatingProcedureProgress.self, from: data)
        else { return OperatingProcedureProgress() }
        return progress
    }

    static func save(_ progress: OperatingProcedureProgress) {
        if let data = try? JSONEncoder().encode(progress) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    static func reset() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}

extension AppModel {
    /// One transmission at the learner's own speed and tone. Returns its
    /// length in seconds.
    @discardableResult
    func playOperating(_ text: String) -> TimeInterval {
        guard !text.isEmpty else { return 0 }
        return player.replaySound(playable: .text(text), frequency: settings.toneFrequency, timing: timing)
    }

    /// A demo's transcript, line after line, each at the learner's speed and
    /// tone with a short pause between, as on the air. `onLine` is told which
    /// line is sounding (nil once it has all played). Returns a token: a newer
    /// call (or `stopOperating`) cancels the rest.
    func playOperatingLines(_ lines: [OpDemo.Line], onLine: @escaping @MainActor (Int?) -> Void) {
        operatingGeneration += 1
        let gen = operatingGeneration
        func step(_ i: Int) {
            guard gen == operatingGeneration else { return }
            guard i < lines.count else { onLine(nil); return }
            onLine(i)
            let seconds = playOperating(lines[i].text)
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds + 0.6) { step(i + 1) }
        }
        step(0)
    }

    /// One pass of the pileup demo, through the Pileup Runner's own mixer.
    func playOperatingPileup(_ voices: [OpPileupVoice], onFinished: @escaping @MainActor () -> Void) {
        operatingGeneration += 1
        player.playPileup(voices.map { v in
            MorsePlayer.PileupVoice(text: v.text, frequency: v.pitch, timing: MorseTiming(wpm: v.wpm),
                                    gain: Float(v.gain), startDelay: v.delay, qsbRate: nil)
        }, qrn: 0, onFinished: onFinished)
    }

    /// The drill and the RIT demo: a station at `pitch`; with `sidetone`, your
    /// own sidetone sounding with it, so the beat between them can be heard.
    func playOperatingTone(text: String, pitch: Double, sidetone: Double? = nil) {
        operatingGeneration += 1
        let t = timing
        var voices = [MorsePlayer.PileupVoice(text: text, frequency: OperatingProcedure.audible(pitch), timing: t,
                                              gain: 0.8, startDelay: 0, qsbRate: nil)]
        if let sidetone {
            voices.append(MorsePlayer.PileupVoice(text: text, frequency: OperatingProcedure.audible(sidetone), timing: t,
                                                  gain: 0.8, startDelay: 0, qsbRate: nil))
        }
        player.playPileup(voices, qrn: 0) {}
    }

    func stopOperating() {
        operatingGeneration += 1
        player.stop()
    }

    /// A graded answer is practice for the day, as in First Four.
    func noteOperatingPractice() { markPracticedToday() }

    /// The call and state the learner confirmed, written back to Your Station.
    func saveOperatingStation(call: String, state: String) {
        settings.qso.myCall = OperatingProcedure.normalizeCall(call)
        settings.qso.myState = OperatingProcedure.normalizeState(state)
    }
}
