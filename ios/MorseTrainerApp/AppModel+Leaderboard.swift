// AppModel+Leaderboard.swift
// The shared leaderboard's run lifecycle (docs/high-scores-design.md, step
// 2): a run token at session start, the transcript at session end, the
// verdict on the summary. The transcript itself is appended by the same
// callbacks that feed the session tally, in AppModel.swift — see
// `noteLeaderboardItem` and the "Shared leaderboard" section there.
//
// What a transcript item is, per mode (the Android port matches these
// exactly; the server grades `answered == sent` case- and space-insensitively
// and sums the speed over the correct items):
//
//   Rapid Fire   one per answered drill: sent = the target text, answered =
//                the copy, credited in the target's form when the quiz graded
//                it correct (a serial copied as cut numbers is still a copy);
//                reactionMs = the drill's TTR. Review (listen-only) is not
//                submitted — nothing is answered.
//   Contest,     one per logged QSO: sent = answered = "CALL EXCHANGE", the
//   Pileup       log's own form (true digits, e.g. "K1ABC 599 BOB OH");
//                one per bust: sent = "CALL EXCHANGE" of the station being
//                worked, or the call of the first station in the pileup when
//                the bust was a miscopied call, answered = what was sent.
//                reactionMs = 0 (the pileup has no per-item TTR).
//   Invaders,    one per shot and per escape: sent = the character (the
//   Galaga,      invader hit, or the lowest/most threatening one on a wrong
//   Frogger      key), answered = the key pressed ("" on an escape); Frogger
//                the same with the lane cue and the chosen object's label.
//   Defender     one per routed defence and per strike: sent = the callsign
//                (the attacker destroyed, or nearest arrival on a wrong
//                route), answered = the asset tapped or text typed ("" on a
//                strike).
//   Dungeon      one per cast: sent = the counter word the player had to
//                key, answered = what was keyed ("" if nothing).
//   Asteroids    one per asteroid resolved: sent = its label, answered = the
//                label when destroyed, "" when it struck the ship. A wrong
//                character keyed mid-label is not an item (the asteroid is
//                still in play and will resolve one way or the other).
//   Games' wpm = the engine's `currentWpm`, rounded, at the item.
//
// Run speeds: Rapid Fire and the games send the session timing (character
// and effective WPM); Contest and Pileup Runner have no single speed — see
// `LeaderboardRunSpeeds.pileup`.

import Foundation

/// The state of this session's submission, shown on the session summary.
enum LeaderboardStatus: Equatable {
    /// Opted out, an unranked mode, or nothing to send: the summary shows
    /// nothing at all.
    case notSubmitted
    /// A run token was requested, or the submission is in flight.
    case pending
    /// The server accepted the run.
    case posted(rank: Int, metric: Int, correct: Int, total: Int, personalBest: Bool)
    /// The server refused the run and said why (a grading, timing or name
    /// verdict on this run).
    case refused(String)
    /// The device or the network got in the way; the run itself may have
    /// been fine.
    case unavailable(String)
}

extension AppModel {
    /// The board the running mode ranks on, or nil for an unranked mode.
    var leaderboardMode: LeaderboardMode? {
        LeaderboardMode(trainingModeRawValue: mode.rawValue)
    }

    /// The speeds a run token is issued for (see the header).
    func leaderboardRunSpeeds(for board: LeaderboardMode) -> LeaderboardRunSpeeds {
        switch board {
        case .contest:
            return .pileup(minWpm: contestType.minWPM, maxWpm: contestType.maxWPM)
        case .pileup:
            return .pileup(minWpm: settings.qso.minWPM, maxWpm: settings.qso.maxWPM)
        default:
            return LeaderboardRunSpeeds(characterWpm: timing.wpm, effectiveWpm: timing.effectiveWpm)
        }
    }

    /// Session start: clear the transcript and, when this run is ranked and
    /// the user has opted in, ask the server for a run token in the
    /// background. The token's issue time is the server's clock on the run
    /// (design §3, layer 2), so this has to happen now, not at the end.
    func leaderboardBeginRun() {
        leaderboardTranscript = []
        leaderboardRunStart?.cancel()
        leaderboardRunStart = nil
        leaderboardGeneration += 1
        leaderboardStatus = .notSubmitted

        guard settings.leaderboard.shareScores, let board = leaderboardMode else { return }
        // Rapid Fire's review response plays without grading: nothing to rank.
        if board == .rapidFire && settings.rapidFire.response == .review { return }
        guard LeaderboardDisplayName.isValid(settings.leaderboard.displayName) else {
            leaderboardStatus = .unavailable("Pick a display name in Settings › Leaderboard to post scores.")
            return
        }
        guard leaderboard.canAttest else {
            leaderboardStatus = .unavailable(LeaderboardError.unsupported.message)
            return
        }
        let speeds = leaderboardRunSpeeds(for: board)
        let client = leaderboard
        leaderboardStatus = .pending
        leaderboardRunStart = Task { try await client.startRun(mode: board, speeds: speeds) }
    }

    /// Session end: hand the transcript to the server with the run token and
    /// show the verdict on the summary when it comes back. Never blocks; a
    /// token still in flight is awaited inside the task.
    func leaderboardFinishRun() {
        guard let start = leaderboardRunStart else { return }
        leaderboardRunStart = nil
        let items = leaderboardTranscript
        guard !items.isEmpty else {
            start.cancel()
            leaderboardStatus = .notSubmitted
            return
        }
        let name = LeaderboardDisplayName.normalize(settings.leaderboard.displayName)
        let generation = leaderboardGeneration
        let client = leaderboard
        leaderboardStatus = .pending
        Task { [weak self] in
            let status: LeaderboardStatus
            do {
                let token = try await start.value
                let r = try await client.submit(runToken: token, displayName: name, transcript: items)
                status = .posted(rank: r.rank, metric: Int(r.metric.rounded()),
                                 correct: r.correct, total: r.total, personalBest: r.personalBest)
            } catch let error as LeaderboardError {
                status = error.isRefusal ? .refused(error.message) : .unavailable(error.message)
            } catch is CancellationError {
                status = .notSubmitted
            } catch {
                status = .unavailable(error.localizedDescription)
            }
            // A later session has its own status by now; leave it alone.
            guard let self, self.leaderboardGeneration == generation else { return }
            self.leaderboardStatus = status
        }
    }

    /// One graded item for this run's transcript. Items the server would
    /// reject outright are dropped here rather than sink the whole run: an
    /// empty target (nothing was sent), a target over the length cap, or a
    /// character the server's Morse table does not know.
    func noteLeaderboardItem(_ item: LeaderboardTranscriptItem) {
        guard leaderboardRunStart != nil else { return }
        guard item.isPlausible, leaderboardTranscript.count < LeaderboardTranscriptItem.maxItems else { return }
        leaderboardTranscript.append(item)
    }

    /// Settings › Leaderboard › Delete my scores. Attested like a run start;
    /// the server drops every score, submission and token for this device's
    /// key, and the key itself.
    func leaderboardDeleteMyScores() async -> String? {
        guard leaderboard.canAttest else { return LeaderboardError.unsupported.message }
        do {
            try await leaderboard.deleteMyScores()
            return nil
        } catch let error as LeaderboardError {
            return error.message
        } catch {
            return error.localizedDescription
        }
    }
}
