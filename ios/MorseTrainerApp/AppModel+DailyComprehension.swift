// AppModel+DailyComprehension.swift
// Daily Comprehension (#353, docs/daily-comprehension-design.md): the thin
// layer between the MorseKit rules (Comprehension.swift) and the screen —
// where today's game is kept, how the sentence is sounded, and what counts as
// practice.
//
// Like Daily Dit it is not a session: nothing here writes a SessionRecord,
// touches the Koch ladder or submits to the leaderboard. An answer marks the
// day as practised, so the streak and the buddy streak see it. It is NOT a
// ranked run.

import Foundation

/// Today's Daily Comprehension, saved on every listen and on the answer so a
/// relaunch cannot hand back a fresh question. An `ObservableObject` of its
/// own (rather than a stored property on `AppModel`, which an extension cannot
/// add) so the Home card and the screen both redraw off one source.
@MainActor
final class DailyComprehensionStore: ObservableObject {
    static let shared = DailyComprehensionStore()
    static let key = "MorseTrainer.dailyComprehension"

    @Published private(set) var game: DailyComprehensionGame

    private init() {
        let number = DailyComprehension.puzzleNumber(for: Date())
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode(DailyComprehensionGame.self, from: data),
           saved.puzzleNumber == number {
            game = saved
        } else {
            game = DailyComprehensionGame(puzzleNumber: number)
        }
    }

    /// Bring `game` up to date with the calendar: the app can sit open across
    /// midnight. Yesterday's game is not migrated.
    func refresh(now: Date = Date()) {
        let number = DailyComprehension.puzzleNumber(for: now)
        guard game.puzzleNumber != number else { return }
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode(DailyComprehensionGame.self, from: data),
           saved.puzzleNumber == number {
            game = saved
        } else {
            game = DailyComprehensionGame(puzzleNumber: number)
            save()
        }
    }

    func noteListen() {
        var next = game
        next.noteListen()
        guard next != game else { return }
        game = next
        save()
    }

    /// Lock in an answer. True when it was taken.
    func choose(_ position: Int) -> Bool {
        var next = game
        guard next.choose(position) else { return false }
        game = next
        save()
        return true
    }

    private func save() {
        if let data = try? JSONEncoder().encode(game) {
            UserDefaults.standard.set(data, forKey: Self.key)
        }
    }
}

extension AppModel {
    /// Sound today's sentence at the learner's own speed and tone, the timing
    /// every copy mode uses. Every play is counted until the question is
    /// answered; replays afterwards are free. Returns its length in seconds.
    @discardableResult
    func playDailyComprehension() -> TimeInterval {
        let store = DailyComprehensionStore.shared
        store.refresh()
        store.noteListen()
        return player.replaySound(playable: .text(store.game.item.sentence),
                                  frequency: settings.toneFrequency,
                                  timing: timing)
    }

    func stopDailyComprehension() { player.stop() }

    /// Answer today's question. Counts toward the daily streak (the day is
    /// practised) whether the answer is right or wrong.
    @discardableResult
    func answerDailyComprehension(_ position: Int) -> Bool {
        let taken = DailyComprehensionStore.shared.choose(position)
        if taken { markPracticedToday() }
        return taken
    }
}
