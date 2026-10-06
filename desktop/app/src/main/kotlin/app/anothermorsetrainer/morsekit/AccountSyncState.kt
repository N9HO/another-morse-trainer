package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * The device's OWN seconds per local day (`fixtures/sync-wire.json`,
 * `merge.deviceDays`), kept apart from the displayed ledger. The server keeps
 * the larger figure per device and day and sums the devices, so only this
 * record is ever pushed: pushing the displayed ledger after it has adopted a
 * summed figure would count the other devices twice.
 *
 * It grows with every local session and practice-day mark (same day key and
 * whole seconds the ledger records), is seeded once from the local ledger the
 * first time this device signs in, survives sign-out, and adoption never
 * touches it. Pure functions over maps; the sync engine keeps the map.
 */
object SyncOwnDays {
    /** [day] plus [seconds] (negative counts as 0; 0 still marks the day), capped like the ledger. */
    fun record(own: Map<LocalDate, Int>, day: LocalDate, seconds: Int): Map<LocalDate, Int> {
        val out = sortedMapOf<LocalDate, Int>()
        out.putAll(own)
        out[day] = (out[day] ?: 0) + maxOf(0, seconds)
        while (out.size > ActivityLedger.CAP_DAYS) out.remove(out.firstKey())
        return out
    }

    /** The first sign-in's seed: the local ledger as it stands, before anything is adopted. */
    fun seed(ledger: Map<LocalDate, Int>): Map<LocalDate, Int> = ledger.toSortedMap()

    /**
     * The `POST /v1/sync/days` figures for the queued days: this device's own
     * figure for each, earliest first. A queued day the own record no longer
     * holds (fallen off the cap) is left out.
     */
    fun pushBody(own: Map<LocalDate, Int>, queued: Collection<LocalDate>): Map<LocalDate, Int> {
        val out = sortedMapOf<LocalDate, Int>()
        for (day in queued) own[day]?.let { out[day] = it }
        return out
    }

    /** Most days in one `POST /v1/sync/days` (the server's limit). */
    const val BATCH_SIZE = 400
}

/**
 * Everything the sync engine persists that is not a secret (the tokens live
 * in their own owner-only file): who is signed in, the pull cursor, when the
 * last sync finished, the three outboxes, the own-day record and its seeded
 * flag, the per-key `updatedAt` of the five synced state keys, whether the
 * restore snapshot is still owed, and the "you were signed out" banner.
 *
 * Immutable; the engine replaces it whole. [encode] / [decode] are the stored
 * form (one JSON document in the `amt_account` prefs file).
 */
data class SyncAccountState(
    val accountId: String? = null,
    val email: String? = null,
    val callsign: String? = null,
    val displayName: String? = null,
    /** `since` for `GET /v1/sync/sessions`: the highest `seq` merged and saved locally. */
    val cursor: Long = 0,
    /** Epoch ms of the last sync that finished cleanly; null for "Not yet". */
    val lastSyncedAt: Long? = null,
    /** Session ids waiting to be pushed, oldest first. */
    val sessionOutbox: SyncMerge.Outbox = SyncMerge.Outbox(),
    /** The encoded wire record for each id in [sessionOutbox], so a row that ages out of history is still pushed. */
    val sessionPayloads: Map<String, String> = emptyMap(),
    /** Days whose own figure changed since the last successful days push. */
    val dayOutbox: Set<LocalDate> = emptySet(),
    /** State keys changed since the last successful state push. */
    val stateOutbox: Set<String> = emptySet(),
    val ownDays: Map<LocalDate, Int> = emptyMap(),
    val ownDaysSeeded: Boolean = false,
    /** Per synced key, the `updatedAt` (epoch ms) of the value held locally. */
    val stateStamps: Map<String, Long> = emptyMap(),
    /** True from sign-in until the restore snapshot has been merged. */
    val needsSnapshot: Boolean = false,
    /** The server signed this device out; the Account section says so until sign-in or dismissal. */
    val signedOutBanner: Boolean = false
) {
    val isSignedIn: Boolean get() = accountId != null

    /**
     * A record (already wire-encoded) queued for push. An id already queued
     * keeps its place; past the cap the oldest goes, payload and all.
     */
    fun enqueueSession(id: String, encoded: String): SyncAccountState {
        val key = id.lowercase(Locale.ROOT)
        val outbox = if (key in sessionOutbox.ids) sessionOutbox else sessionOutbox.enqueue(key)
        val payloads = HashMap(sessionPayloads)
        payloads[key] = encoded
        return copy(sessionPayloads = payloads).withSessionOutbox(outbox)
    }

    /** The session outbox replaced, payloads pruned to the ids still in it. */
    fun withSessionOutbox(outbox: SyncMerge.Outbox): SyncAccountState {
        val keep = outbox.ids.toHashSet()
        return copy(sessionOutbox = outbox, sessionPayloads = sessionPayloads.filterKeys { it in keep })
    }

    /**
     * Signed out (by the user, by deleting the account, or by the server):
     * account, cursor, outboxes, last-synced and the owed snapshot dropped.
     * The own-day record, its seeded flag and the state stamps stay, as does
     * every local record (they are not in here).
     */
    fun signedOut(banner: Boolean): SyncAccountState = copy(
        accountId = null, email = null, callsign = null, displayName = null,
        cursor = 0, lastSyncedAt = null,
        sessionOutbox = SyncMerge.Outbox(), sessionPayloads = emptyMap(),
        dayOutbox = emptySet(), stateOutbox = emptySet(),
        needsSnapshot = false, signedOutBanner = banner
    )

    fun encode(): JSONObject {
        val o = JSONObject()
        accountId?.let { o.put("accountId", it) }
        email?.let { o.put("email", it) }
        callsign?.let { o.put("callsign", it) }
        displayName?.let { o.put("displayName", it) }
        o.put("cursor", cursor)
        lastSyncedAt?.let { o.put("lastSyncedAt", it) }
        val sessions = JSONArray()
        for (id in sessionOutbox.ids) {
            val payload = sessionPayloads[id] ?: continue
            sessions.put(JSONObject().put("id", id).put("record", payload))
        }
        o.put("sessionOutbox", sessions)
        o.put("dayOutbox", JSONArray(dayOutbox.sorted().map { it.toString() }))
        o.put("stateOutbox", JSONArray(stateOutbox.sorted()))
        o.put("ownDays", JSONObject().apply { for ((d, s) in ownDays.toSortedMap()) put(d.toString(), s) })
        o.put("ownDaysSeeded", ownDaysSeeded)
        o.put("stateStamps", JSONObject().apply { for ((k, t) in stateStamps) put(k, t) })
        o.put("needsSnapshot", needsSnapshot)
        o.put("signedOutBanner", signedOutBanner)
        return o
    }

    companion object {
        /** The stored form back; anything unreadable is left at its default, one bad entry dropped. */
        fun decode(json: String?): SyncAccountState {
            if (json.isNullOrBlank()) return SyncAccountState()
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return SyncAccountState()
            fun str(key: String): String? = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }
            val ids = ArrayList<String>()
            val payloads = HashMap<String, String>()
            o.optJSONArray("sessionOutbox")?.let { a ->
                for (i in 0 until a.length()) runCatching {
                    val e = a.getJSONObject(i)
                    val id = e.getString("id").lowercase(Locale.ROOT)
                    payloads[id] = e.getString("record")
                    ids.add(id)
                }
            }
            val days = LinkedHashSet<LocalDate>()
            o.optJSONArray("dayOutbox")?.let { a ->
                for (i in 0 until a.length()) runCatching { days.add(LocalDate.parse(a.getString(i))) }
            }
            val keys = LinkedHashSet<String>()
            o.optJSONArray("stateOutbox")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { keys.add(it) } }
            val own = sortedMapOf<LocalDate, Int>()
            o.optJSONObject("ownDays")?.let { d ->
                for (k in d.keys()) runCatching { own[LocalDate.parse(k)] = d.getInt(k) }
            }
            val stamps = LinkedHashMap<String, Long>()
            o.optJSONObject("stateStamps")?.let { s ->
                for (k in s.keys()) runCatching { stamps[k] = s.getLong(k) }
            }
            return SyncAccountState(
                accountId = str("accountId"),
                email = str("email"),
                callsign = str("callsign"),
                displayName = str("displayName"),
                cursor = o.optLong("cursor", 0),
                lastSyncedAt = if (o.has("lastSyncedAt") && !o.isNull("lastSyncedAt")) o.optLong("lastSyncedAt") else null,
                sessionOutbox = SyncMerge.Outbox(ids),
                sessionPayloads = payloads,
                dayOutbox = days,
                stateOutbox = keys,
                ownDays = own,
                ownDaysSeeded = o.optBoolean("ownDaysSeeded", false),
                stateStamps = stamps,
                needsSnapshot = o.optBoolean("needsSnapshot", false),
                signedOutBanner = o.optBoolean("signedOutBanner", false)
            )
        }
    }
}

/**
 * The profile and e-mail rules the accounts Worker applies (README §5 and
 * §8), checked locally first so a plain mistake is caught before a request.
 * The server still has the last word; its message is shown when it refuses.
 */
object AccountProfile {
    private val CALLSIGN = Regex("^[A-Z0-9/]{3,16}$")

    /** Trimmed and uppercased, as the server stores it; empty means "clear it". */
    fun normalizeCallsign(raw: String): String = raw.trim().uppercase(Locale.ROOT)

    /** Empty (clear) or 3–16 of A–Z, 0–9 and /. */
    fun isValidCallsign(raw: String): Boolean {
        val c = normalizeCallsign(raw)
        return c.isEmpty() || CALLSIGN.matches(c)
    }

    fun normalizeDisplayName(raw: String): String = raw.trim()

    /** Empty (clear) or 2–24 printable characters (no control characters). */
    fun isValidDisplayName(raw: String): Boolean {
        val n = normalizeDisplayName(raw)
        if (n.isEmpty()) return true
        val count = n.codePointCount(0, n.length)
        return count in 2..24 && n.codePoints().noneMatch { Character.isISOControl(it) || Character.getType(it) == Character.UNASSIGNED.toInt() }
    }

    /**
     * Plausible enough to send a link to: trimmed, at most 254 characters, one
     * `@` with something before it and a dot in what follows, no spaces. The
     * server is the real check (it answers 202 either way).
     */
    fun isPlausibleEmail(raw: String): Boolean {
        val e = raw.trim()
        if (e.isEmpty() || e.length > 254 || e.any { it.isWhitespace() }) return false
        val at = e.indexOf('@')
        if (at <= 0 || at != e.lastIndexOf('@')) return false
        val domain = e.substring(at + 1)
        return domain.length >= 3 && '.' in domain && !domain.startsWith('.') && !domain.endsWith('.')
    }
}

/** "Last synced" and "last seen" as a coarse relative time. */
object SyncTime {
    enum class Span { JUST_NOW, MINUTES, HOURS, DAYS }

    /** How long before [nowMs] [thenMs] was: under a minute is just now; then minutes, hours, days (floored). */
    fun ago(nowMs: Long, thenMs: Long): Pair<Span, Long> {
        val seconds = maxOf(0L, (nowMs - thenMs) / 1000)
        return when {
            seconds < 60 -> Span.JUST_NOW to 0L
            seconds < 3600 -> Span.MINUTES to seconds / 60
            seconds < 86_400 -> Span.HOURS to seconds / 3600
            else -> Span.DAYS to seconds / 86_400
        }
    }
}

/**
 * The account's practice streak (`streak` of `GET /v1/me/stats` and of the
 * snapshot's `stats`), adopted after the server's days so a restored install
 * shows the account's streak instead of 0. The server's current run and last
 * day replace the local ones; the local longest is never lowered. A local
 * last day newer than the server's (a day not pushed yet) keeps the local run.
 */
object SyncStreak {
    data class Server(val current: Int, val longest: Int, val lastPractisedDay: LocalDate?)

    /** The `streak` object of a stats body, or null when there is none or it is unreadable. */
    fun parse(stats: JSONObject?): Server? {
        val s = stats?.optJSONObject("streak") ?: return null
        return runCatching {
            Server(
                current = s.getInt("current"),
                longest = s.getInt("longest"),
                lastPractisedDay = if (s.isNull("lastPractisedDay")) null else LocalDate.parse(s.getString("lastPractisedDay"))
            )
        }.getOrNull()
    }

    /** The streak to keep: [PracticeStreak] with the server's run adopted per the rule above. */
    fun adopt(local: PracticeStreak, server: Server): PracticeStreak {
        val localLast = local.lastPracticeDay
        val serverLast = server.lastPractisedDay
        val keepLocalRun = localLast != null && (serverLast == null || localLast.isAfter(serverLast))
        return if (keepLocalRun) {
            PracticeStreak(local.current, maxOf(local.longest, server.longest, local.current), localLast)
        } else {
            PracticeStreak(server.current, maxOf(local.longest, server.longest, server.current), serverLast)
        }
    }
}
