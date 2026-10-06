// AppModel+FirstFour.swift
// First Four (#265, docs/first-four-design.md): the thin layer between the
// MorseKit rules (FirstFour.swift) and the screen — where progress is kept,
// how the activator's transmissions are sounded, and what counts as practice.
//
// Not a session: nothing here writes a SessionRecord, touches the Koch
// ladder or submits to the leaderboard. A graded answer marks the day as
// practised, as a Daily Dit guess does, so the streak and the buddy streak
// see it.

import Foundation

/// Where First Four's progress lives: its own key, not `AppSettings` — it is
/// progress, not a preference. Twin of the Kotlin `FirstFourStore`.
enum FirstFourStore {
    static let key = "MorseTrainer.firstFour"
    /// Set by onboarding's "first POTA contact" button; Home reads it once
    /// it appears and opens First Four.
    static let openOnHomeKey = "MorseTrainer.firstFour.openOnHome"

    static func load() -> FirstFourProgress {
        guard let data = UserDefaults.standard.data(forKey: key),
              let progress = try? JSONDecoder().decode(FirstFourProgress.self, from: data)
        else { return FirstFourProgress() }
        return progress
    }

    /// `noteSync` false is for a value the account just handed us: saved,
    /// but not stamped as a change of ours (SyncCoordinator.swift).
    @MainActor
    static func save(_ progress: FirstFourProgress, noteSync: Bool = true) {
        if let data = try? JSONEncoder().encode(progress) {
            UserDefaults.standard.set(data, forKey: key)
        }
        if noteSync { SyncCoordinator.shared.noteState(.firstFour, value: AccountSync.firstFourValue(progress)) }
    }

    /// "Start over" is local: it never stamps or pushes (the next ordinary
    /// save does).
    @MainActor
    static func reset() {
        UserDefaults.standard.removeObject(forKey: key)
        SyncCoordinator.shared.noteReset(.firstFour, value: AccountSync.firstFourValue(FirstFourProgress()))
    }
}

extension AppModel {
    /// Sound one activator transmission at the learner's own speed and tone,
    /// the timing every copy mode uses. Returns its length in seconds, so the
    /// screen can reveal the text once it has been heard.
    @discardableResult
    func playFirstFour(_ text: String) -> TimeInterval {
        guard !text.isEmpty else { return 0 }
        return player.replaySound(playable: .text(text),
                                  frequency: settings.toneFrequency,
                                  timing: timing)
    }

    func stopFirstFour() { player.stop() }

    /// A graded First Four answer is practice for the day, like a Daily Dit
    /// guess: the streak moves, no session is recorded.
    func noteFirstFourPractice() { markPracticedToday() }

    /// The call and state the learner confirmed, written back to Your Station
    /// so every other mode that uses them sees the same.
    func saveFirstFourStation(call: String, state: String) {
        settings.qso.myCall = FirstFour.normalizeCall(call)
        settings.qso.myState = FirstFour.normalizeState(state)
    }
}
