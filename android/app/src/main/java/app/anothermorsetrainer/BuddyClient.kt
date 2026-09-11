package app.anothermorsetrainer

import android.util.Log
import androidx.annotation.StringRes
import app.anothermorsetrainer.morsekit.Buddy
import app.anothermorsetrainer.morsekit.BuddyStatus
import app.anothermorsetrainer.morsekit.Leaderboard
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/**
 * The buddy-streak client (docs/buddy-streak-design.md): five attested
 * calls to the leaderboard Worker, a cached status in [Settings], and the
 * once-a-day practice report. Everything network-shaped is borrowed from
 * [LeaderboardClient] — the OkHttp client, the challenge fetch, the Play
 * Integrity attestation, the coroutine scope that outlives a screen — so the
 * buddy feature is one more set of routes on the identity the leaderboard
 * already has, not a second identity. Pure rules (the day label, the
 * invite-code rule, the status parse) are in `morsekit/Buddy.kt`. The iOS
 * twin is `BuddyClient.swift`.
 *
 * Nothing here blocks a screen: the suspend calls are for the Settings
 * buttons, which show a spinner; [refreshIfStale] and [reportPracticeDay]
 * launch and forget, and a failure there is a log line, never a dialog.
 *
 * Pairing is its own consent: none of this reads `Settings.leaderboardEnabled`.
 * It does need the leaderboard display name, which is what the buddy sees.
 */
object BuddyClient {
    private const val TAG = "Buddy"

    /** The status [refreshIfStale] is fetching, so two resumes do not fetch twice. */
    private var refreshing = false
    /** Likewise for the day report, which every finished session offers. */
    private var reporting = false

    /** A minted invite: the code to send and when the server forgets it (epoch ms). */
    class Invite(val code: String, val expiresAt: Long)

    sealed class InviteResult {
        data class Ok(val invite: Invite) : InviteResult()
        data class Failed(val reason: String) : InviteResult()
    }

    /** The leaderboard display name as the server accepts it, or null when there is none to pair under. */
    fun displayName(): String? = Leaderboard.normalizeDisplayName(Settings.leaderboardName)

    /**
     * `POST /v1/buddy/invite`: a six-character code, single use, 24 hours,
     * five a day. Kept in [Settings] so the section can show it again and the
     * app keeps polling for the join until it expires.
     */
    suspend fun invite(): InviteResult {
        val name = displayName() ?: return InviteResult.Failed(string(R.string.buddy_reason_no_name))
        return try {
            val reply = LeaderboardClient.attestedPost("/v1/buddy/invite", JSONObject().put("displayName", name))
            val code = reply.body?.optString("code")?.let { Buddy.normalizeInviteCode(it) }
            val expiresAt = reply.body?.optLong("expiresAt", 0L) ?: 0L
            if (reply.code == 200 && code != null) {
                Settings.updateBuddyInvite(code, expiresAt)
                InviteResult.Ok(Invite(code, expiresAt))
            } else {
                InviteResult.Failed(reply.reason)
            }
        } catch (e: Exception) {
            Log.d(TAG, "invite failed: ${e.message}")
            InviteResult.Failed(LeaderboardClient.describe(e))
        }
    }

    /**
     * `POST /v1/buddy/join` with a code someone sent. Returns null on success
     * (the cache now says paired) or the reason it was refused, in the
     * server's words where it has them.
     */
    suspend fun join(rawCode: String): String? {
        val name = displayName() ?: return string(R.string.buddy_reason_no_name)
        val code = Buddy.normalizeInviteCode(rawCode) ?: return string(R.string.buddy_reason_bad_code)
        return statusCall("/v1/buddy/join", JSONObject().put("displayName", name).put("code", code))
    }

    /** `POST /v1/buddy/leave`. Returns null on success or the reason it failed. */
    suspend fun leave(): String? {
        return try {
            val reply = LeaderboardClient.attestedPost("/v1/buddy/leave", JSONObject())
            if (reply.code == 200) {
                Settings.clearBuddy()
                null
            } else {
                reply.reason
            }
        } catch (e: Exception) {
            Log.d(TAG, "leave failed: ${e.message}")
            LeaderboardClient.describe(e)
        }
    }

    /** `POST /v1/buddy/status` now, whatever the cache says. Null on success or the reason. */
    suspend fun refresh(): String? = statusCall("/v1/buddy/status", JSONObject())

    /**
     * Refresh the cached status in the background if it is older than
     * [Buddy.REFRESH_INTERVAL_MS] or from another day; silent on failure.
     * Called when the activity comes to the foreground and when the Settings
     * section appears.
     *
     * Only an install with something to learn polls from the foreground: one
     * that is paired, or one whose invite may have been taken up since.
     * Otherwise every app open would attest and call for nothing, for every
     * user who never touched the feature. [evenIfUnpaired] is the Settings
     * section, where the user is looking at the answer.
     */
    fun refreshIfStale(evenIfUnpaired: Boolean = false) {
        if (!LeaderboardClient.isInitialized || !LeaderboardClient.isConfigured) return
        if (displayName() == null) return
        val status = Settings.buddyStatus
        val now = System.currentTimeMillis()
        if (!evenIfUnpaired) {
            val interested = status?.paired == true || Settings.buddyInviteExpiresAt > now
            if (!interested) return
        }
        if (status != null && status.isFor(Buddy.today()) && now - status.fetchedAt < Buddy.REFRESH_INTERVAL_MS) return
        if (refreshing) return
        refreshing = true
        LeaderboardClient.scope.launch {
            try {
                refresh()?.let { Log.d(TAG, "status refresh: $it") }
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * The practice-day report (design §3): called wherever the personal
     * streak marks a day — `Stats.record` and `Stats.recordPracticeDay`, which
     * between them cover every session, Listen & Learn and a Daily Dit guess.
     * Sends `POST /v1/buddy/day` once per local day while paired; not paired
     * means no network at all. Never blocks the caller.
     *
     * @param today the day the streak marked, the caller's clock — the same
     *   [LocalDate] `PracticeStreak` used, so the two can never disagree about
     *   which day it was.
     */
    fun reportPracticeDay(today: LocalDate = LocalDate.now()) {
        if (!LeaderboardClient.isInitialized || !LeaderboardClient.isConfigured) return
        val status = Settings.buddyStatus ?: return
        if (!status.paired) return
        val day = Buddy.dayLabel(today)
        if (Settings.buddyLastReportedDay == day) return
        if (reporting) return
        reporting = true
        LeaderboardClient.scope.launch {
            try {
                statusCall("/v1/buddy/day", JSONObject().put("day", day), reportedDay = day)
                    ?.let { Log.d(TAG, "day report: $it") }
            } finally {
                reporting = false
            }
        }
    }

    /**
     * The three routes that answer with a status: post [fields] plus `today`,
     * parse the reply into the cache, and return null or the refusal.
     *
     * A successful report pins [reportedDay] as sent; any other status that
     * already shows today as practised pins today too, saving the call. And
     * the reverse: a status that says paired but not practised, when the
     * personal streak says today is done (paired at six, practised at nine),
     * sends the report the pairing missed.
     */
    private suspend fun statusCall(path: String, fields: JSONObject, reportedDay: String? = null): String? {
        val today = Buddy.today()
        fields.put("today", today)
        return try {
            val reply = LeaderboardClient.attestedPost(path, fields)
            val body = reply.body
            if (reply.code != 200 || body == null) return reply.reason
            val status: BuddyStatus = Buddy.parseStatus(body, System.currentTimeMillis())
                ?: return string(R.string.buddy_reason_bad_reply)
            Settings.updateBuddyStatus(status)
            if (reportedDay != null) {
                Settings.markBuddyReported(reportedDay)
            } else if (status.practisedToday && status.isFor(today)) {
                Settings.markBuddyReported(today)
            } else if (status.paired && Stats.practisedToday) {
                reportPracticeDay()
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "$path failed: ${e.message}")
            LeaderboardClient.describe(e)
        }
    }

    private fun string(@StringRes id: Int): String = LeaderboardClient.appContext.getString(id)
}
