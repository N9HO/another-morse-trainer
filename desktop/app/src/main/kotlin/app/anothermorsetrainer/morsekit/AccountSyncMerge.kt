package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * The client merge rules of account sync (accounts README §7, "Client merge
 * rules"), pinned by `fixtures/sync-wire.json`'s `merge` on every port: how
 * pulled sessions, the server's aggregates, its ledger and its state replies
 * are folded into what this device holds, the outbox, the backoff and what to
 * do with each HTTP outcome. Pure functions that return the new values; the
 * stores adopt them.
 */
object SyncMerge {

    // ---- Sessions ----

    /**
     * Insert each pulled or snapshot record whose id (compared lowercase) is
     * not already local; a local record is never replaced. Then newest date
     * first (equal dates: id ascending) and the newest [limit] kept.
     */
    fun mergeSessions(
        local: List<SessionRecord>,
        pulled: List<SessionRecord>,
        limit: Int = SessionHistory.limit
    ): List<SessionRecord> {
        val have = local.mapTo(HashSet()) { key(it) }
        val merged = ArrayList(local)
        for (r in pulled) if (have.add(key(r))) merged.add(r)
        return merged
            .sortedWith(compareByDescending<SessionRecord> { it.date }.thenBy { key(it) })
            .take(limit)
    }

    private fun key(r: SessionRecord): String = r.id.toString().lowercase(Locale.ROOT)

    // ---- Aggregates ----

    /**
     * The lifetime counters `Stats` keeps beyond its 100 rows, as the server
     * states them. [bestScores] is keyed by this port's mode strings, as
     * `Stats.bestScores` is; [totalAnswered] is `Stats.totalAttempts`.
     */
    data class Aggregates(
        val totalSessions: Int,
        val totalAnswered: Int,
        val totalCorrect: Int,
        val totalPracticeSeconds: Double,
        val bestTtrMs: Int?,
        val bestScores: Map<String, Int>
    )

    /**
     * The aggregates a reply's `stats` replaces the local ones with: totals,
     * `bestTtrMs` (null stays null) and the whole `personalBests` map, its
     * canonical ids mapped back to local mode strings. Null when [stats] has
     * no `totals`, so a malformed reply leaves the local counters alone.
     */
    fun adoptAggregates(stats: JSONObject?): Aggregates? {
        val totals = stats?.optJSONObject("totals") ?: return null
        val bests = LinkedHashMap<String, Int>()
        stats.optJSONObject("personalBests")?.let { pb ->
            for (id in pb.keys()) runCatching { bests[SyncWire.localMode(id)] = pb.getInt(id) }
        }
        return runCatching {
            Aggregates(
                totalSessions = totals.getInt("sessions"),
                totalAnswered = totals.getInt("answered"),
                totalCorrect = totals.getInt("correct"),
                totalPracticeSeconds = totals.getDouble("practiceSeconds"),
                bestTtrMs = if (stats.isNull("bestTtrMs")) null else stats.getInt("bestTtrMs"),
                bestScores = bests
            )
        }.getOrNull()
    }

    // ---- Ledger ----

    /**
     * The server's summed figure replaces each day it mentions; local days it
     * does not mention are kept. Then the ledger's own cap (newest
     * [ActivityLedger.CAP_DAYS] days).
     */
    fun mergeLedger(local: Map<LocalDate, Int>, server: Map<LocalDate, Int>): ActivityLedger {
        val merged = sortedMapOf<LocalDate, Int>()
        merged.putAll(local)
        merged.putAll(server)
        while (merged.size > ActivityLedger.CAP_DAYS) merged.remove(merged.firstKey())
        return ActivityLedger(merged)
    }

    // ---- State ----

    /**
     * Per key, a reply entry replaces the local one only when its `updatedAt`
     * is strictly newer; a tie or an older one keeps local, and keys the reply
     * omits keep local.
     */
    fun mergeState(
        local: Map<String, SyncWire.StateEntry>,
        reply: Map<String, SyncWire.StateEntry>
    ): Map<String, SyncWire.StateEntry> {
        val out = LinkedHashMap(local)
        for ((key, theirs) in reply) {
            val mine = out[key]
            if (mine == null || theirs.updatedAt > mine.updatedAt) out[key] = theirs
        }
        return out
    }

    // ---- Push reply and outbox ----

    /** `POST /v1/sync/sessions`'s reply. [stats] is adopted through [adoptAggregates]. */
    data class PushReply(
        val accepted: List<String>,
        val skipped: List<String>,
        val rejected: List<Rejected>,
        val stats: JSONObject?
    ) {
        data class Rejected(val id: String?, val reason: String)

        companion object {
            fun parse(o: JSONObject): PushReply {
                fun ids(key: String): List<String> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.optString(it) }
                }
                val rej = o.optJSONArray("rejected")
                val rejected = if (rej == null) emptyList() else (0 until rej.length()).mapNotNull { i ->
                    rej.optJSONObject(i)?.let { r ->
                        Rejected(if (r.isNull("id")) null else r.optString("id"), r.optString("reason", ""))
                    }
                }
                return PushReply(ids("accepted"), ids("skipped"), rejected, o.optJSONObject("stats"))
            }
        }
    }

    /**
     * Session ids waiting to be pushed, oldest first. Enqueuing past [max]
     * drops the oldest. An id leaves only when a 2xx reply lists it as
     * accepted, skipped or rejected (a rejected record stays in local
     * history); ids the reply does not mention stay, in order. Ids are kept
     * and compared lowercase, as on the wire.
     */
    data class Outbox(val ids: List<String> = emptyList(), val max: Int = APP_MAX) {
        fun enqueue(id: String): Outbox {
            val next = ids + id.lowercase(Locale.ROOT)
            return copy(ids = if (next.size > max) next.takeLast(max) else next)
        }

        /** The next batch to push: up to [size] ids, oldest first. */
        fun nextBatch(size: Int = BATCH_SIZE): List<String> = ids.take(size)

        fun afterPush(reply: PushReply): Outbox {
            val gone = HashSet<String>()
            reply.accepted.forEach { gone.add(it.lowercase(Locale.ROOT)) }
            reply.skipped.forEach { gone.add(it.lowercase(Locale.ROOT)) }
            reply.rejected.forEach { r -> r.id?.let { gone.add(it.lowercase(Locale.ROOT)) } }
            return copy(ids = ids.filter { it !in gone })
        }

        val isEmpty: Boolean get() = ids.isEmpty()

        companion object {
            /** The app's outbox cap. */
            const val APP_MAX = 1000
            /** The server's most records per push. */
            const val BATCH_SIZE = 200
        }
    }

    // ---- Retry ----

    /** Seconds before retry [n] (1-based): 2^n, capped at 600. */
    fun backoffSeconds(n: Int): Long {
        if (n < 1) return 2
        if (n >= 10) return BACKOFF_CAP_SECONDS
        return minOf(BACKOFF_CAP_SECONDS, 1L shl n)
    }

    const val BACKOFF_CAP_SECONDS = 600L

    /** What the sync engine does with an HTTP outcome. */
    enum class Action(val raw: String) {
        /** It landed. */
        DONE("done"),
        /** A 4xx: resending the same request cannot help. */
        DROP("drop"),
        /** Refresh the token once, then retry once; if the refresh fails, sign out. */
        REFRESH("refresh"),
        /** Rate limited, a server fault, or no reply at all: try again after [backoffSeconds]. */
        BACKOFF("backoff")
    }

    /** The action for [status]; null is "no HTTP reply at all". */
    fun retryAction(status: Int?): Action = when {
        status == null -> Action.BACKOFF
        status in 200..299 -> Action.DONE
        status == 401 -> Action.REFRESH
        status == 429 -> Action.BACKOFF
        status >= 500 -> Action.BACKOFF
        else -> Action.DROP
    }
}

/** What asked for a sync (fixture `merge.foregroundThrottle`); [raw] is the fixture's name. */
enum class SyncTrigger(val raw: String) {
    /** Launch, and the window regaining focus. */
    FOREGROUND("foreground"),
    /** The Sync now row. */
    SYNC_NOW("syncNow"),
    /** The push after a local change. */
    LOCAL_CHANGE("localChange"),
    /** The first sync after signing in. */
    SIGN_IN("signIn"),
    /** A backoff retry. */
    RETRY("retry")
}

/**
 * Keeps foreground syncs at least [MIN_INTERVAL_SECONDS] apart (fixture
 * `merge.foregroundThrottle`). A full sync is several requests and window
 * focus can change many times a minute; this keeps the Worker's rate limit
 * out of reach. Only [SyncTrigger.FOREGROUND] is gated and only a foreground
 * that runs moves the clock; every other trigger always runs. Held in
 * memory, so a cold launch always syncs.
 */
class SyncThrottle {
    private var lastForegroundMs: Long? = null

    /**
     * Whether a sync for [trigger] at [nowMs] should run. A held-back
     * foreground must do nothing at all, not even reset the backoff, which
     * would cancel a scheduled retry.
     */
    @Synchronized
    fun admit(trigger: SyncTrigger, nowMs: Long): Boolean {
        if (trigger != SyncTrigger.FOREGROUND) return true
        val last = lastForegroundMs
        if (last != null && nowMs >= last && nowMs - last < MIN_INTERVAL_SECONDS * 1000) return false
        lastForegroundMs = nowMs
        return true
    }

    companion object {
        /** Five minutes. */
        const val MIN_INTERVAL_SECONDS = 240L
    }
}
