package app.anothermorsetrainer

import android.util.Log
import androidx.annotation.StringRes
import app.anothermorsetrainer.morsekit.Buddy
import app.anothermorsetrainer.morsekit.BuddyEntry
import app.anothermorsetrainer.morsekit.BuddyStatus
import app.anothermorsetrainer.morsekit.Leaderboard
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/**
 * The buddy-streak client (docs/buddy-streak-design.md, #219, #237): the
 * attested calls to the leaderboard Worker, a cached status in [Settings],
 * and the once-a-day practice report. Everything network-shaped is borrowed
 * from [LeaderboardClient] — the OkHttp client, the challenge fetch, the
 * Play Integrity attestation, the coroutine scope that outlives a screen — so
 * the buddy feature is one more set of routes on the identity the
 * leaderboard already has, not a second identity. Pure rules (the day label,
 * the invite-code rule, the status parse, the digest) are in
 * `morsekit/Buddy.kt`. The iOS twin is `LeaderboardClient+Buddy.swift` and
 * `AppModel+Buddy.swift`.
 *
 * Several buddies (#237) are the `/v2/buddy/...` routes. A Worker deployed
 * before them answers any `/v2` path with a bare 404 "not found" before it
 * reads the body (the challenge is not spent), so each call tries v2 first
 * and, on exactly that answer, remembers it for this process and repeats the
 * call on `/v1`, whose status [Buddy.parseStatus] reads as a list of at most
 * one buddy. Every other refusal is the v2 route's own and is passed on.
 *
 * Nothing here blocks a screen: the suspend calls are for the Settings
 * buttons, which show a spinner; [refreshIfStale] and [reportPracticeDay]
 * launch and forget, and a failure there is a log line, never a dialog.
 *
 * Pairing is its own consent: none of this reads `Settings.leaderboardEnabled`.
 * It does need the leaderboard display name, which is what buddies see.
 */
object BuddyClient {
    private const val TAG = "Buddy"

    /** The status [refreshIfStale] is fetching, so two resumes do not fetch twice. */
    private var refreshing = false
    /** Likewise for the day report, which every finished session offers. */
    private var reporting = false

    /**
     * Set when the Worker answered a `/v2/buddy` call with its route-not-found
     * 404: it predates #237, so the one-buddy `/v1` routes are used until the
     * process ends. Not persisted, so the first launch after the Worker is
     * deployed picks v2 up by itself.
     */
    @Volatile
    private var v2Missing = false

    /** A minted invite: the code to send and when the server forgets it (epoch ms). */
    class Invite(val code: String, val expiresAt: Long)

    sealed class InviteResult {
        data class Ok(val invite: Invite) : InviteResult()
        data class Failed(val reason: String) : InviteResult()
    }

    /** The leaderboard display name as the server accepts it, or null when there is none to pair under. */
    fun displayName(): String? = Leaderboard.normalizeDisplayName(Settings.leaderboardName)

    /**
     * The Worker's answer to a path it does not have (its router's
     * `reject(404, "not found")`), as opposed to a v2 route's own 404s, which
     * always say more ("invite code unknown…", "no such buddy").
     */
    internal fun isMissingRoute(code: Int, reason: String): Boolean = code == 404 && reason == "not found"

    /**
     * `POST /v2/buddy/<action>`, or `/v1/buddy/<action>` when this Worker has
     * no v2. The v1 call gets the same [fields] (the routes share their
     * bodies, leave aside, which [leave] handles itself).
     */
    private suspend fun post(action: String, fields: JSONObject): LeaderboardClient.Reply {
        if (!v2Missing) {
            val reply = LeaderboardClient.attestedPost("/v2/buddy/$action", fields)
            if (!isMissingRoute(reply.code, reply.reason)) return reply
            Log.i(TAG, "the Worker has no /v2/buddy; using the one-buddy routes for now")
            v2Missing = true
        }
        return LeaderboardClient.attestedPost("/v1/buddy/$action", fields)
    }

    /**
     * Invite a buddy: a six-character code, single use, 24 hours, five a day.
     * Kept in [Settings] so the section can show it again and the app keeps
     * polling for the join until it expires. Refused while the list is full.
     */
    suspend fun invite(): InviteResult {
        val name = displayName() ?: return InviteResult.Failed(string(R.string.buddy_reason_no_name))
        return try {
            val reply = post("invite", JSONObject().put("displayName", name))
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
     * Join with a code someone sent. Returns null on success (the cache now
     * lists the new buddy) or the reason it was refused, in the server's
     * words where it has them. An invite of ours stays up: this buddy came
     * through their code, not ours.
     */
    suspend fun join(rawCode: String): String? {
        val name = displayName() ?: return string(R.string.buddy_reason_no_name)
        val code = Buddy.normalizeInviteCode(rawCode) ?: return string(R.string.buddy_reason_bad_code)
        return statusCall("join", JSONObject().put("displayName", name).put("code", code), joinedByMe = true)
    }

    /**
     * Leave one buddy. Returns null on success or the reason it failed. The
     * other pairings are untouched. An entry with no pairing id (a one-buddy
     * Worker, or a cache from before #237 not yet refreshed) goes through
     * `/v1/buddy/leave`, which ends the only pairing there is, so the whole
     * cache is cleared.
     */
    suspend fun leave(buddy: BuddyEntry): String? {
        return try {
            if (buddy.id.isNotEmpty() && !v2Missing) {
                val today = Buddy.today()
                val reply = LeaderboardClient.attestedPost(
                    "/v2/buddy/leave", JSONObject().put("buddyId", buddy.id).put("today", today)
                )
                if (!isMissingRoute(reply.code, reply.reason)) {
                    val body = reply.body
                    if (reply.code != 200 || body == null) return reply.reason
                    val status = Buddy.parseStatus(body, System.currentTimeMillis())
                        ?: return string(R.string.buddy_reason_bad_reply)
                    Settings.updateBuddyStatus(status)
                    return null
                }
                v2Missing = true
            }
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

    /** The status now, whatever the cache says. Null on success or the reason. */
    suspend fun refresh(): String? = statusCall("status", JSONObject())

    /**
     * Refresh the cached status in the background if it is older than
     * [Buddy.REFRESH_INTERVAL_MS] or from another day; silent on failure.
     * Called when the activity comes to the foreground and when the Settings
     * section appears.
     *
     * Only an install with something to learn polls from the foreground: one
     * with a buddy, or one whose invite may have been taken up since.
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
     * Sends the day once per local day while there is at least one buddy; one
     * report counts toward every pairing. No buddy means no network at all.
     * Never blocks the caller.
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
                statusCall("day", JSONObject().put("day", day), reportedDay = day)
                    ?.let { Log.d(TAG, "day report: $it") }
            } finally {
                reporting = false
            }
        }
    }

    /**
     * The three actions that answer with a status: post [fields] plus `today`,
     * parse the reply into the cache, and return null or the refusal.
     *
     * A successful report pins [reportedDay] as sent; any other status that
     * already shows today as practised pins today too, saving the call. And
     * the reverse: a status with buddies but not practised, when the personal
     * streak says today is done (paired at six, practised at nine), sends the
     * report the pairing missed.
     */
    private suspend fun statusCall(
        action: String,
        fields: JSONObject,
        reportedDay: String? = null,
        joinedByMe: Boolean = false
    ): String? {
        val today = Buddy.today()
        fields.put("today", today)
        return try {
            val reply = post(action, fields)
            val body = reply.body
            if (reply.code != 200 || body == null) return reply.reason
            val status: BuddyStatus = Buddy.parseStatus(body, System.currentTimeMillis())
                ?: return string(R.string.buddy_reason_bad_reply)
            Settings.updateBuddyStatus(status, joinedByMe)
            if (reportedDay != null) {
                Settings.markBuddyReported(reportedDay)
            } else if (status.practisedToday && status.isFor(today)) {
                Settings.markBuddyReported(today)
            } else if (status.paired && Stats.practisedToday) {
                reportPracticeDay()
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "$action failed: ${e.message}")
            LeaderboardClient.describe(e)
        }
    }

    private fun string(@StringRes id: Int): String = LeaderboardClient.appContext.getString(id)
}
