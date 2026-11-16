// AttestationAvailability.swift
// Whether this install can attest itself, and what that decides (#349).
//
// The shared leaderboard and the buddy streak rest on device attestation
// (App Attest here; Play Integrity on Android; docs/high-scores-design.md
// section 3). Nothing else does: the training modes, personal bests and
// account sync never touch it. So a device that cannot attest (the simulator,
// a Mac, an unsupported device) must start, run every mode and keep its scores
// as local personal bests, with the ranked features replaced by a one-line
// explanation (design section 3a).
//
// This file is only the rule: state in, feature switches out. The rule is held
// to `fixtures/attestation-availability.json`, which the Kotlin tests on
// Android and desktop read as well, so the three apps present the same thing
// in the same situation. The Android twin is `AttestationAvailability.kt`.

import Foundation

public enum AttestationState: String, CaseIterable, Sendable {
    /// Can attest: the ranked features are on offer.
    case available
    /// A build with no Cloud project number (Android only; never produced here).
    case notConfigured
    /// Android: the Play Store or Play services is missing or disabled (never produced here).
    case noPlayServices
    /// iOS, iPadOS, Mac: App Attest is unsupported (simulator, Mac, old device).
    case deviceUnsupported
    /// Desktop: neither OS offers an attestation (never produced here).
    case noPlatformAttestation
}

/// Which explanation replaces the Share scores switch.
public enum AttestationNote: String, CaseIterable, Sendable {
    case none
    case notConfigured
    case noPlayServices
    case deviceUnsupported
    case noPlatformAttestation
}

public struct AttestationFeatures: Equatable, Sendable {
    /// The "Share scores" switch is shown (otherwise `note` stands where it was).
    public let shareSwitchVisible: Bool
    /// A game's Start may ask once whether to share scores.
    public let optInPromptOffered: Bool
    /// A finished ranked-mode run is registered and submitted.
    public let runsRanked: Bool
    /// Settings offers "Delete my scores" (it is an attested write).
    public let deleteScoresOffered: Bool
    /// Invite and Join work. Whether the buttons explain on tap or are absent is the platform's idiom.
    public let buddyActionsEnabled: Bool
    /// The public boards can be read: no attestation needed.
    public let boardReadable: Bool
    /// Scores still count toward local personal bests. Always true.
    public let personalBestsKept: Bool
    /// Every training mode works. Always true.
    public let trainingModesWork: Bool
    public let note: AttestationNote
}

public enum AttestationAvailability {

    public static func features(for state: AttestationState) -> AttestationFeatures {
        let on = state == .available
        let note: AttestationNote
        switch state {
        case .available: note = .none
        case .notConfigured: note = .notConfigured
        case .noPlayServices: note = .noPlayServices
        case .deviceUnsupported: note = .deviceUnsupported
        case .noPlatformAttestation: note = .noPlatformAttestation
        }
        return AttestationFeatures(
            shareSwitchVisible: on,
            optInPromptOffered: on,
            runsRanked: on,
            deleteScoresOffered: on,
            buddyActionsEnabled: on,
            boardReadable: true,
            personalBestsKept: true,
            trainingModesWork: true,
            note: note
        )
    }

    /// Apple's probe: `DCAppAttestService.shared.isSupported` decides.
    public static func appleState(isSupported: Bool) -> AttestationState {
        isSupported ? .available : .deviceUnsupported
    }
}
