package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.BuddyEntry
import app.anothermorsetrainer.morsekit.Leaderboard
import java.time.LocalDate

/**
 * The buddy-streak client (docs/buddy-streak-design.md, #219, #237), desktop
 * edition: **not available.**
 *
 * On the phones every buddy call — invite, join, leave, status, the daily
 * practice report — is an attested `POST` to the leaderboard Worker, riding
 * the same Play Integrity / App Attest identity the leaderboard uses, so a
 * buddy is one more set of routes on an identity the server can trust. The
 * desktop port has no attestation ([LeaderboardClient] is read-only here), so
 * it cannot make any of those calls: each suspend call below returns
 * [UNAVAILABLE] as its refusal, and [refreshIfStale] / [reportPracticeDay]
 * do nothing (no network at all). The buddy cache in [Settings] is kept and
 * read as on Android, so the stored file stays in the same format; on a fresh
 * desktop install it is simply empty.
 *
 * The public API keeps the Android port's shapes so the ported screens and
 * `Stats` compile unchanged. Pure rules (the day label, the invite-code rule,
 * the status parse, the digest) are still in `morsekit/Buddy.kt`.
 */
object BuddyClient {

    /**
     * Why a buddy action cannot run here, in the words that follow
     * "Couldn't do that: " in Settings.
     */
    const val UNAVAILABLE =
        "buddy streaks need the phone app: the desktop app cannot prove it is a genuine install to the server"

    /** A minted invite: the code to send and when the server forgets it (epoch ms). */
    class Invite(val code: String, val expiresAt: Long)

    sealed class InviteResult {
        data class Ok(val invite: Invite) : InviteResult()
        data class Failed(val reason: String) : InviteResult()
    }

    /** The leaderboard display name as the server accepts it, or null when there is none to pair under. */
    fun displayName(): String? = Leaderboard.normalizeDisplayName(Settings.leaderboardName)

    /** Desktop: never succeeds — see the class note. */
    suspend fun invite(): InviteResult = InviteResult.Failed(UNAVAILABLE)

    /** Desktop: never succeeds; returns the reason. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun join(rawCode: String): String? = UNAVAILABLE

    /** Desktop: never succeeds; returns the reason. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun leave(buddy: BuddyEntry): String? = UNAVAILABLE

    /** Desktop: never succeeds; returns the reason. */
    suspend fun refresh(): String? = UNAVAILABLE

    /** Desktop: does nothing — there is no status to fetch without attestation. */
    @Suppress("UNUSED_PARAMETER")
    fun refreshIfStale(evenIfUnpaired: Boolean = false) {}

    /**
     * Desktop: does nothing. `Stats.record` and `Stats.recordPracticeDay`
     * still call it wherever the personal streak marks a day, as on Android;
     * with no buddy possible here there is nothing to report.
     */
    @Suppress("UNUSED_PARAMETER")
    fun reportPracticeDay(today: LocalDate = LocalDate.now()) {}
}
