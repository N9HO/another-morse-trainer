import Foundation
import AVFoundation

/// Speaks the English answer aloud in the hands-free "Listen & Learn" mode.
///
/// Wraps `AVSpeechSynthesizer` and reports completion on the main thread so the
/// listen loop can chain: play Morse → pause → speak → next. It deliberately
/// does NOT touch the shared `AVAudioSession` (MorsePlayer owns that, configured
/// for `.playback` so both the tone engine and speech keep working in the
/// background with the screen locked).
final class SpeechPlayer: NSObject, AVSpeechSynthesizerDelegate {

    private let synth = AVSpeechSynthesizer()
    private var completion: (() -> Void)?

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
               completion: @escaping () -> Void) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { completion(); return }
        self.completion = completion
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
        completion = nil
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
    }

    // MARK: AVSpeechSynthesizerDelegate

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                           didFinish utterance: AVSpeechUtterance) {
        let done = completion
        completion = nil
        DispatchQueue.main.async { done?() }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                           didCancel utterance: AVSpeechUtterance) {
        completion = nil
    }
}
