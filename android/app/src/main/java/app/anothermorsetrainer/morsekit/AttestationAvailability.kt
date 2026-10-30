package app.anothermorsetrainer.morsekit

/**
 * Whether this install can attest itself, and what that decides (#349).
 *
 * The shared leaderboard and the buddy streak rest on device attestation
 * (Play Integrity here; docs/high-scores-design.md §3). Nothing else does: the
 * training modes, personal bests and account sync never touch it. So a device
 * with no Google Play services (LineageOS without GApps, a degoogled build
 * installed through Aurora) must start, run every mode, and keep its scores
 * as local personal bests, with the ranked features hidden behind a one-line
 * explanation and no prompt to install anything (design §3a).
 *
 * This file is only the rule: state in, feature switches out. Probing the
 * device is `LeaderboardClient`'s job. The rule is held to
 * `fixtures/attestation-availability.json`, which the Swift harness and the
 * desktop tests read as well, so the three apps present the same thing in the
 * same situation. The iOS twin is `AttestationAvailability.swift`; desktop's
 * is the same file name and is always [NO_PLATFORM_ATTESTATION].
 */
enum class AttestationState(val id: String) {
    /** Can attest: the ranked features are on offer. */
    AVAILABLE("available"),
    /** A build with no Cloud project number: behaves as though the feature were off. */
    NOT_CONFIGURED("notConfigured"),
    /** Android: the Play Store or Play services is missing or disabled. */
    NO_PLAY_SERVICES("noPlayServices"),
    /** iOS: App Attest is unsupported (simulator, Mac, old device). */
    DEVICE_UNSUPPORTED("deviceUnsupported"),
    /** Desktop: neither OS offers an attestation to an open-source app. */
    NO_PLATFORM_ATTESTATION("noPlatformAttestation");

    companion object {
        fun fromId(id: String): AttestationState? = entries.firstOrNull { it.id == id }
    }
}

/** Which explanation replaces the Share scores switch. [NONE] means no note. */
enum class AttestationNote(val id: String) {
    NONE("none"),
    NOT_CONFIGURED("notConfigured"),
    NO_PLAY_SERVICES("noPlayServices"),
    DEVICE_UNSUPPORTED("deviceUnsupported"),
    NO_PLATFORM_ATTESTATION("noPlatformAttestation");

    companion object {
        fun fromId(id: String): AttestationNote? = entries.firstOrNull { it.id == id }
    }
}

/** What each feature does in one [AttestationState]. */
data class AttestationFeatures(
    /** The "Share scores" switch is shown (otherwise [note] stands where it was). */
    val shareSwitchVisible: Boolean,
    /** A game's Start may ask once whether to share scores. */
    val optInPromptOffered: Boolean,
    /** A finished ranked-mode run is registered and submitted. */
    val runsRanked: Boolean,
    /** Settings offers "Delete my scores" (it is an attested write). */
    val deleteScoresOffered: Boolean,
    /** Invite and Join work. Whether the buttons are disabled, explain on tap, or absent is the platform's idiom. */
    val buddyActionsEnabled: Boolean,
    /** The public boards can be read: no attestation needed. */
    val boardReadable: Boolean,
    /** Scores still count toward local personal bests. Always true. */
    val personalBestsKept: Boolean,
    /** Every training mode works. Always true. */
    val trainingModesWork: Boolean,
    val note: AttestationNote
)

object AttestationAvailability {

    fun features(state: AttestationState): AttestationFeatures {
        val on = state == AttestationState.AVAILABLE
        return AttestationFeatures(
            shareSwitchVisible = on,
            optInPromptOffered = on,
            runsRanked = on,
            deleteScoresOffered = on,
            buddyActionsEnabled = on,
            boardReadable = true,
            personalBestsKept = true,
            trainingModesWork = true,
            note = when (state) {
                AttestationState.AVAILABLE -> AttestationNote.NONE
                AttestationState.NOT_CONFIGURED -> AttestationNote.NOT_CONFIGURED
                AttestationState.NO_PLAY_SERVICES -> AttestationNote.NO_PLAY_SERVICES
                AttestationState.DEVICE_UNSUPPORTED -> AttestationNote.DEVICE_UNSUPPORTED
                AttestationState.NO_PLATFORM_ATTESTATION -> AttestationNote.NO_PLATFORM_ATTESTATION
            }
        )
    }

    /**
     * Android's probe: a build with no Cloud project number is not configured
     * whatever the device has; otherwise both the Play Store and Play services
     * must be present and enabled. Play Integrity needs both.
     */
    fun androidState(configured: Boolean, playStorePresent: Boolean, playServicesPresent: Boolean): AttestationState = when {
        !configured -> AttestationState.NOT_CONFIGURED
        !playStorePresent || !playServicesPresent -> AttestationState.NO_PLAY_SERVICES
        else -> AttestationState.AVAILABLE
    }
}
