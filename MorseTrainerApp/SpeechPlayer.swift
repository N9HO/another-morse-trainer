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
        utterance.rate = rate
        utterance.postUtteranceDelay = 0
        synth.speak(utterance)
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
