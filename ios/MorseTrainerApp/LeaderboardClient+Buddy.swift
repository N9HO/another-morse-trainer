// LeaderboardClient+Buddy.swift
// The buddy-streak routes (docs/buddy-streak-design.md, #219, #237) on the
// same actor as the leaderboard: the pairing is keyed by the leaderboard
// identity, so it is the same App Attest key, the same challenge fetch and
// the same one "unknown key" retry. Wire types are in MorseKit/Buddy.swift.
//
// Every buddy call is: fetch a challenge, attest with clientData = that
// challenge, POST `{ challenge, attestation, ...fields }`. `today` and `day`
// are the device's LOCAL calendar day (`BuddyDay.label`), never a
// timestamp: the server folds two calendars, each in its owner's zone.
//
// Several buddies (#237) are the `/v2/buddy/*` routes. A Worker deployed
// before them answers any `/v2` path with a bare 404 "not found" before it
// reads the body (the challenge is not spent), so each call tries v2 first
// and, on exactly that answer, remembers it for this launch and repeats the
// call on `/v1`, whose status is read as a list of at most one buddy
// (`BuddyListStatus` takes both shapes). Every other refusal is the v2
// route's own and is passed on.

import Foundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "buddy")

extension LeaderboardClient {
    /// The Worker's answer to a path it does not have (its router's
    /// `reject(404, "not found")`), as opposed to a v2 route's own 404s,
    /// which always say more ("invite code unknown…", "no such buddy").
    static func isMissingRoute(_ error: Error) -> Bool {
        if case LeaderboardError.server(status: 404, reason: let reason) = error { return reason == "not found" }
        return false
    }

    /// Try `v2/buddy/<action>`, and if this Worker has no v2 (now or earlier
    /// this launch) return nil so the caller makes the v1 call instead.
    private func buddyV2<Body: Encodable, Result: Decodable>(
        _ action: String,
        _ makeBody: @Sendable (String, LeaderboardAttestation) -> Body
    ) async throws -> Result? {
        guard !buddyV2Missing else { return nil }
        do {
            return try await attestedWithChallenge(path: "v2/buddy/\(action)", makeBody)
        } catch where Self.isMissingRoute(error) {
            log.notice("the Worker has no /v2/buddy; using the one-buddy routes this launch")
            buddyV2Missing = true
            return nil
        }
    }

    /// Invite a buddy: a six-character code, valid 24 hours, single use,
    /// five a day. The display name is what the joiner will see. v2 refuses
    /// with 409 when the list is full; v1 while paired at all.
    func buddyInvite(displayName: String) async throws -> BuddyInviteResponse {
        let make: @Sendable (String, LeaderboardAttestation) -> BuddyInviteRequest = { challenge, attestation in
            BuddyInviteRequest(challenge: challenge, attestation: attestation, displayName: displayName)
        }
        let r: BuddyInviteResponse
        if let v2: BuddyInviteResponse = try await buddyV2("invite", make) {
            r = v2
        } else {
            r = try await attestedWithChallenge(path: "v1/buddy/invite", make)
        }
        log.info("invite issued, expires \(r.expiryDate, privacy: .public)")
        return r
    }

    /// Pair with whoever issued `code`. The answer is the new list; on v2 its
    /// `joined` names the pairing just made. The server's refusals (400 own
    /// code or malformed, 404 unknown/used/expired, 409 full, already
    /// buddies, or the inviter's list full) come back as
    /// `LeaderboardError.server` with the reason to show.
    func buddyJoin(displayName: String, code: String, today: String) async throws -> BuddyListStatus {
        let make: @Sendable (String, LeaderboardAttestation) -> BuddyJoinRequest = { challenge, attestation in
            BuddyJoinRequest(challenge: challenge, attestation: attestation, displayName: displayName, code: code, today: today)
        }
        let r: BuddyListStatus
        if let v2: BuddyListStatus = try await buddyV2("join", make) {
            r = v2
        } else {
            r = try await attestedWithChallenge(path: "v1/buddy/join", make)
        }
        log.info("joined: \(r.buddies.count) buddies now")
        return r
    }

    /// This device practised on `day` (its local day). The server allows a
    /// day's slack either side of its own clock, nothing more, so `day` is
    /// always today's label at the moment of the call. One report counts
    /// toward every pairing.
    func buddyReportDay(_ day: String, today: String) async throws -> BuddyListStatus {
        let make: @Sendable (String, LeaderboardAttestation) -> BuddyDayRequest = { challenge, attestation in
            BuddyDayRequest(challenge: challenge, attestation: attestation, day: day, today: today)
        }
        let r: BuddyListStatus
        if let v2: BuddyListStatus = try await buddyV2("day", make) {
            r = v2
        } else {
            r = try await attestedWithChallenge(path: "v1/buddy/day", make)
        }
        log.info("day \(day, privacy: .public) reported; \(r.buddies.count) buddies")
        return r
    }

    /// Every buddy, their day and each pairing's streak as of `today`.
    func buddyStatus(today: String) async throws -> BuddyListStatus {
        let make: @Sendable (String, LeaderboardAttestation) -> BuddyStatusRequest = { challenge, attestation in
            BuddyStatusRequest(challenge: challenge, attestation: attestation, today: today)
        }
        if let v2: BuddyListStatus = try await buddyV2("status", make) { return v2 }
        return try await attestedWithChallenge(path: "v1/buddy/status", make)
    }

    /// Leave one buddy. Either side can; the other learns of it on their next
    /// refresh. Returns the list that is left on v2, or nil when the v1
    /// route was used — for an entry with no pairing id (a one-buddy answer
    /// or cache) or a Worker without v2 — which ends every pairing, i.e. the
    /// one it knows about; the caller then clears the cache.
    func buddyLeave(buddyId: String, today: String) async throws -> BuddyListStatus? {
        if !buddyId.isEmpty,
           let v2: BuddyListStatus = try await buddyV2("leave", { challenge, attestation in
               BuddyLeaveOneRequest(challenge: challenge, attestation: attestation, buddyId: buddyId, today: today)
           }) {
            log.info("left one buddy; \(v2.buddies.count) left")
            return v2
        }
        struct Left: Decodable { var ok: Bool }
        let _: Left = try await attestedWithChallenge(path: "v1/buddy/leave") { challenge, attestation in
            BuddyLeaveRequest(challenge: challenge, attestation: attestation)
        }
        log.info("left buddy (v1)")
        return nil
    }
}
