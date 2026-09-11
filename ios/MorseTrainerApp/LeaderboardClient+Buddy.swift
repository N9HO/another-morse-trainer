// LeaderboardClient+Buddy.swift
// The buddy-streak routes (docs/buddy-streak-design.md, #219) on the same
// actor as the leaderboard: the pairing is keyed by the leaderboard identity,
// so it is the same App Attest key, the same challenge fetch and the same
// one "unknown key" retry. Wire types are in MorseKit/Buddy.swift.
//
// Every buddy call is: fetch a challenge, attest with clientData = that
// challenge, POST `{ challenge, attestation, ...fields }`. `today` and `day`
// are the device's LOCAL calendar day (`BuddyDay.label`), never a
// timestamp: the server folds two calendars, each in its owner's zone.

import Foundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "buddy")

extension LeaderboardClient {
    /// `/v1/buddy/invite`: a six-character code, valid 24 hours, single
    /// use, five a day. The display name is what the joiner will see.
    func buddyInvite(displayName: String) async throws -> BuddyInviteResponse {
        let r: BuddyInviteResponse = try await attestedWithChallenge(path: "v1/buddy/invite") { challenge, attestation in
            BuddyInviteRequest(challenge: challenge, attestation: attestation, displayName: displayName)
        }
        log.info("invite issued, expires \(r.expiryDate, privacy: .public)")
        return r
    }

    /// `/v1/buddy/join`: pair with whoever issued `code`. The answer is the
    /// new status. The server's refusals (400 own code or malformed, 404
    /// unknown/used/expired, 409 already paired) come back as
    /// `LeaderboardError.server` with the reason to show.
    func buddyJoin(displayName: String, code: String, today: String) async throws -> BuddyStatus {
        let r: BuddyStatus = try await attestedWithChallenge(path: "v1/buddy/join") { challenge, attestation in
            BuddyJoinRequest(challenge: challenge, attestation: attestation, displayName: displayName, code: code, today: today)
        }
        log.info("joined: paired with \(r.buddy?.displayName ?? "?", privacy: .public)")
        return r
    }

    /// `/v1/buddy/day`: this device practised on `day` (its local day). The
    /// server allows a day's slack either side of its own clock, nothing
    /// more, so `day` is always today's label at the moment of the call.
    func buddyReportDay(_ day: String, today: String) async throws -> BuddyStatus {
        let r: BuddyStatus = try await attestedWithChallenge(path: "v1/buddy/day") { challenge, attestation in
            BuddyDayRequest(challenge: challenge, attestation: attestation, day: day, today: today)
        }
        log.info("day \(day, privacy: .public) reported; streak \(r.streak)")
        return r
    }

    /// `/v1/buddy/status`: the pairing, the buddy's day and the streak as
    /// of `today`.
    func buddyStatus(today: String) async throws -> BuddyStatus {
        try await attestedWithChallenge(path: "v1/buddy/status") { challenge, attestation in
            BuddyStatusRequest(challenge: challenge, attestation: attestation, today: today)
        }
    }

    /// `/v1/buddy/leave`: unpair. Either side can; the other learns of it
    /// on their next refresh.
    func buddyLeave() async throws {
        struct Left: Decodable { var ok: Bool }
        let _: Left = try await attestedWithChallenge(path: "v1/buddy/leave") { challenge, attestation in
            BuddyLeaveRequest(challenge: challenge, attestation: attestation)
        }
        log.info("left buddy")
    }
}
