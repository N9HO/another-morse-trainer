import Foundation
import AVFoundation
import os

/// Speaks the English answer aloud in the hands-free "Listen & Learn" mode.
///
/// Wraps `AVSpeechSynthesizer` and reports completion on the main thread so the
/// listen loop can chain: play Morse → pause → speak → next. It deliberately
/// takes no `AudioSession` claim of its own: it only ever speaks alongside the
/// tone player, whose permanent `.playback` claim is exactly what speech needs,
/// and a claim of its own would just be a second vote for the same profile.
///
/// The SDK marks `AVSpeechSynthesizerDelegate` `Sendable`, so anything that
/// conforms has to be too. This is main-actor isolated, which makes it
/// `Sendable` honestly: `speak`/`stop` and the synthesizer are only ever
/// touched from AppModel, on the main actor. The delegate callbacks are the
/// exception — the synthesizer does not document which thread they arrive on
/// — so they are `nonisolated` and touch exactly one thing, the pending
/// completion, which sits behind a lock for that reason.
@MainActor
final class SpeechPlayer: NSObject, AVSpeechSynthesizerDelegate {

    private let synth = AVSpeechSynthesizer()
    nonisolated private let completion =
        OSAllocatedUnfairLock<(@MainActor @Sendable () -> Void)?>(initialState: nil)

    /// The most natural English voice available on this device. Prefers the
    /// downloadable premium/enhanced ("Siri"-quality) voices over the default
    /// compact (robotic) one; falls back gracefully when none are installed.
    private let voice: AVSpeechSynthesisVoice? = SpeechPlayer.bestEnglishVoice()

    override init() {
        super.init()
        synth.delegate = self
    }

    /// Speak `text`, calling `completion` once on the main thread when done
    /// (or immediately if the text is empty).
    func speak(_ text: String,
               rate: Float = AVSpeechUtteranceDefaultSpeechRate,
               completion: @escaping @MainActor @Sendable () -> Void) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { completion(); return }
        self.completion.withLock { $0 = completion }
        let utterance = AVSpeechUtterance(string: trimmed)
        if let voice { utterance.voice = voice }
        utterance.rate = rate
        utterance.pitchMultiplier = 1.0
        utterance.postUtteranceDelay = 0
        synth.speak(utterance)
    }

    /// Pick the highest-quality installed English voice. Premium > enhanced >
    /// default; US English preferred. Returns nil to let the system choose.
    private static func bestEnglishVoice() -> AVSpeechSynthesisVoice? {
        let english = AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix("en") }
        func score(_ v: AVSpeechSynthesisVoice) -> Int {
            var s = 0
            switch v.quality {
            case .premium:  s += 100
            case .enhanced: s += 50
            default:        break
            }
            if v.language == "en-US" { s += 10 }
            // Avoid the novelty/“eloquence” compact voices when possible.
            if v.identifier.contains("eloquence") { s -= 20 }
            return s
        }
        return english.max { score($0) < score($1) }
    }

    /// Stop any in-progress speech and drop the pending completion.
    func stop() {
        completion.withLock { $0 = nil }
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
    }

    // MARK: AVSpeechSynthesizerDelegate

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                       didFinish utterance: AVSpeechUtterance) {
        let done = completion.withLock { pending -> (@MainActor @Sendable () -> Void)? in
            let taken = pending
            pending = nil
            return taken
        }
        DispatchQueue.main.async { done?() }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                       didCancel utterance: AVSpeechUtterance) {
        completion.withLock { $0 = nil }
    }
}
