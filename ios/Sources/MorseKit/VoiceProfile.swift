import Foundation

/// A lightweight, per-user "voice profile" that learns how an individual tends
/// to be transcribed when answering by voice.
///
/// Apple's Speech framework does not expose true per-user acoustic-model
/// training, so this is a deliberately simple, transparent, fully-offline
/// alternative: every time the learner confirms (via "Did you say X?") or
/// corrects (by picking the right answer) what they said, we remember that this
/// transcript maps to that answer. Over time ``VoiceMatcher`` can short-circuit
/// straight to the answer the user reliably means — personalization without any
/// model training. Codable so the app can persist it in UserDefaults.
public struct VoiceProfile: Codable, Equatable {

    /// normalized transcript → (answer token → times confirmed)
    private var corrections: [String: [String: Int]]

    public init(corrections: [String: [String: Int]] = [:]) {
        self.corrections = corrections
    }

    /// Record that `heard` was confirmed to mean `answer`.
    public mutating func record(heard: String, answer: String) {
        let key = VoiceMatcher.normalize(heard)
        guard !key.isEmpty, !answer.isEmpty else { return }
        corrections[key, default: [:]][answer, default: 0] += 1
    }

    /// The answer this user most often means by `heard`, if any has been
    /// confirmed at least once. Ties break deterministically by token.
    public func suggestion(for heard: String) -> String? {
        let key = VoiceMatcher.normalize(heard)
        guard let tally = corrections[key], !tally.isEmpty else { return nil }
        return tally.max { a, b in
            a.value != b.value ? a.value < b.value : a.key > b.key
        }?.key
    }

    public var isEmpty: Bool { corrections.isEmpty }
}
