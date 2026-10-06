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

    /// `noteSync` false is for a value the account just handed us: saved,
    /// but not stamped as a change of ours (SyncCoordinator.swift).
    @MainActor
    static func save(_ progress: OperatingProcedureProgress, noteSync: Bool = true) {
        if let data = try? JSONEncoder().encode(progress) {
            UserDefaults.standard.set(data, forKey: key)
        }
        if noteSync { SyncCoordinator.shared.noteState(.operatingProcedure, value: AccountSync.operatingProcedureValue(progress)) }
    }

    /// "Start over" is local: it never stamps or pushes (the next ordinary
    /// save does).
    @MainActor
    static func reset() {
        UserDefaults.standard.removeObject(forKey: key)
        SyncCoordinator.shared.noteReset(.operatingProcedure, value: AccountSync.operatingProcedureValue(OperatingProcedureProgress()))
    }
}

extension AppModel {
    /// One transmission at the learner's own speed and tone. Returns its
    /// length in seconds.
    @discardableResult
    func playOperating(_ text: String) -> TimeInterval {
        let parts = OperatingProcedure.clipParts(text)
        guard !parts.isEmpty else { return 0 }
        // An error (`<ERR>`) is sounded in its own shape — a run of 5–8 dits,
        // run together or slapped, at its own speed — so the clip plays as a
        // chain of pieces a word gap apart. A newer clip cancels the rest.
        operatingClipGeneration += 1
        let gen = operatingClipGeneration
        let tone = settings.toneFrequency
        let gap = timing.wordGap
        var at: TimeInterval = 0
        for part in parts {
            let (playable, t) = operatingPlayable(part)
            let synth = MorseSynth(playable: playable, timing: t, sampleRate: 44_100, frequency: tone)
            let length = Double(synth.totalSamples) / 44_100
            if at == 0 {
                player.replaySound(playable: playable, frequency: tone, timing: t)
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + at) { [weak self] in
                    guard let self, self.operatingClipGeneration == gen else { return }
                    self.player.replaySound(playable: playable, frequency: tone, timing: t)
                }
            }
            at += length + gap
        }
        return max(0, at - gap)
    }

    /// One piece of a clip: text at the learner's timing, or an error in the
    /// shape it names (a random one when it names none).
    private func operatingPlayable(_ part: OperatingProcedure.ClipPart) -> (MorseItem.Playable, MorseTiming) {
        switch part {
        case .text(let text):
            return (.text(text), timing)
        case .error(let row):
            let v = row.map(OperatingProcedure.errorVariant)
                ?? OperatingProcedure.errorVariant(Int.random(in: 0..<OperatingProcedure.errorVariants.count))
            let t = MorseTiming(wpm: max(OperatingProcedure.minimumWpm, settings.wpm * v.speed))
            return v.runTogether ? (.pattern(v.pattern), t) : (.text(v.spacedText), t)
        }
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
        operatingClipGeneration += 1
        player.playPileup(voices.map { v in
            MorsePlayer.PileupVoice(text: v.text, frequency: v.pitch, timing: MorseTiming(wpm: v.wpm),
                                    gain: Float(v.gain), startDelay: v.delay, qsbRate: nil)
        }, qrn: 0, onFinished: onFinished)
    }

    /// The drill and the RIT demo: a station at `pitch`; with `sidetone`, your
    /// own sidetone sounding with it, so the beat between them can be heard.
    func playOperatingTone(text: String, pitch: Double, sidetone: Double? = nil) {
        operatingGeneration += 1
        operatingClipGeneration += 1
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
        operatingClipGeneration += 1
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
