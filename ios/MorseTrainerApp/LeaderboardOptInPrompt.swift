// LeaderboardOptInPrompt.swift
// The shared leaderboard's first-play prompt (#226): a game that is about to
// start, on an install that does not share scores and has not said "Don't
// ask again", asks once whether to turn sharing on. It is asked at the
// game's own Start (and Play again) button, never mid-game, and at most once
// per start attempt; whichever answer is given, the game then starts.
//
// The prompt writes the same settings Settings › Leaderboard does, so
// turning sharing on here is exactly the switch there, and the run about to
// begin is registered the way an opted-in run would have been at session
// start. The Android twin is `LeaderboardOptInDialog.kt`.

import SwiftUI

extension AppModel {
    /// Whether a game's Start should first ask about sharing scores: sharing
    /// is off, the question has not been answered, and this device could
    /// actually post (the simulator cannot attest, so it is never asked).
    var shouldOfferLeaderboardOptIn: Bool {
        !settings.leaderboard.shareScores
            && !settings.leaderboard.promptDismissed
            && leaderboard.canAttest
    }

    /// "Share scores" on the prompt: the same switch as Settings, then the
    /// run token the session start skipped while sharing was off. The
    /// display name is whatever the prompt's field holds (it edits the
    /// setting directly); an invalid one is reported on the summary and in
    /// Settings, as it would be for any opted-in run.
    func acceptLeaderboardOptIn() {
        settings.leaderboard.shareScores = true   // didSet also answers the prompt
        leaderboardBeginRun()
    }

    /// "Don't ask again": sharing stays off and no game asks again. Settings
    /// › Leaderboard is still the way to turn it on later.
    func declineLeaderboardOptInForGood() {
        settings.leaderboard.promptDismissed = true
    }
}

/// The alert itself. `proceed` starts the game and runs on every button.
private struct LeaderboardOptInPrompt: ViewModifier {
    @EnvironmentObject var model: AppModel
    @Binding var isPresented: Bool
    let proceed: () -> Void

    func body(content: Content) -> some View {
        content
            .alert("Post your scores to the leaderboard?", isPresented: $isPresented) {
                // Edits the setting as typed, like the field in Settings, so
                // the name is there whichever button follows.
                TextField("Display name", text: $model.settings.leaderboard.displayName)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Button("Share scores") {
                    model.acceptLeaderboardOptIn()
                    proceed()
                }
                Button("Not now", role: .cancel) {
                    proceed()
                }
                Button("Don't ask again") {
                    model.declineLeaderboardOptInForGood()
                    proceed()
                }
            } message: {
                Text("Finished games (and Rapid Fire, Contest and Pileup Runner runs) can be posted to the shared leaderboard under a display name of 2–12 characters: letters, digits, space, / and -. The board ranks a server-graded copy of each run, not the on-screen score. You can turn sharing off at any time in Settings › Leaderboard.")
            }
    }
}

extension View {
    /// Attach to a game view; present by setting `isPresented` from its Start
    /// button when `model.shouldOfferLeaderboardOptIn` is true, else call
    /// `proceed` directly.
    func leaderboardOptInPrompt(isPresented: Binding<Bool>, proceed: @escaping () -> Void) -> some View {
        modifier(LeaderboardOptInPrompt(isPresented: isPresented, proceed: proceed))
    }
}
