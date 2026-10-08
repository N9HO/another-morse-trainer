package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.ActivityLedger
import app.anothermorsetrainer.morsekit.PracticeStreak
import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.StateEntry
import app.anothermorsetrainer.morsekit.SyncMerge
import app.anothermorsetrainer.morsekit.SyncOutbox
import app.anothermorsetrainer.morsekit.SyncRetry
import app.anothermorsetrainer.morsekit.SyncAction
import app.anothermorsetrainer.morsekit.SyncSettings
import app.anothermorsetrainer.morsekit.SyncState
import app.anothermorsetrainer.morsekit.SyncWire
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Where the sync engine keeps its non-secret state: the `amt_account`
 * preferences file in the app, a map in the tests. A null value removes the key.
 */
interface SyncKeyValues {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * The app's live data as the sync engine reads and writes it: `Stats` and
 * the five progress stores in the app ([SyncCoordinator]), fakes in the tests.
 * State values are in their wire shape: a [JSONObject] for a progress key
 * ([SyncState]); for a training setting ([SyncSettings]) any JSON value
 * (Boolean, Int, Double, String, JSONArray, JSONObject).
 */
interface SyncHost {
    fun history(): List<SessionRecord>
    /** Replace the local history with [merged] and persist it. Throws when it could not be saved. */
    fun saveHistory(merged: List<SessionRecord>)
    /** The DISPLAYED ledger (the activity grid). */
    fun ledger(): Map<LocalDate, Int>
    fun saveLedger(days: Map<LocalDate, Int>)
    fun totals(): SyncMerge.LifetimeTotals
    fun saveTotals(totals: SyncMerge.LifetimeTotals)
    fun streak(): PracticeStreak
    fun saveStreak(streak: PracticeStreak)
    /** A key's wire value; null for a progress store never saved here. A setting always has one. */
    fun stateValue(key: String): Any?
    /**
     * Apply a received value to the live store. The store's own save hook
     * must not re-stamp it. A setting arrives normalised ([SyncSettings]).
     */
    fun applyState(key: String, value: Any)
    fun today(): LocalDate
    fun now(): Long
}

/** How one [SyncEngine.sync] ended. */
enum class SyncOutcome {
    /** Everything pushed and pulled. */
    OK,
    /** A transient failure: try again after [SyncRetry.backoffSeconds]. */
    FAILED,
    /** The server refused the refresh token: the account state is cleared and the banner is up. */
    SIGNED_OUT,
    /** Not signed in: nothing to do. */
    IDLE
}

/**
 * The sync engine (accounts README §7, "Client merge rules"; the rules are
 * `fixtures/sync-wire.json`'s `merge`), with no Android types so the JUnit
 * suite can drive it over a fake transport. [SyncCoordinator] is the app's
 * instance and its only caller.
 *
 * Every local write happens first; the hooks here only queue it. While signed
 * in, a finished session queues its encoded record, a practice day queues the
 * day and a changed progress key stamps and queues the key. [sync] drains the
 * queues in order (sessions oldest first, 200 at a time; then days with this
 * device's OWN figures; then state), then pulls what other devices did, or
 * after a sign-in takes the snapshot. The network never gates a local write,
 * and a failure only leaves the queue for the next try.
 *
 * Suspending calls are serialized by one lock. The synchronous hooks are not
 * locked: they run on the main thread, which is where the coroutines that
 * call this resume, so they interleave only at a network wait. Every reply is
 * therefore applied to the queues as they are when it arrives, not as they
 * were when the request went out.
 */
class SyncEngine(
    private val api: AccountApi,
    private val kv: SyncKeyValues,
    private val host: SyncHost,
    private val onChange: () -> Unit = {}
) {
    private val lock = Mutex()

    /** True while a received value is being applied, so the store's save hook does not stamp it as a local change. */
    private var applying = false

    /** Consecutive failed [sync]s; the next retry waits [SyncRetry.backoffSeconds] of it. */
    var failures = 0
        private set

    // ---- Persisted state ----

    /** The signed-in account, or null when signed out. */
    val account: AccountInfo?
        get() = kv.get(K_ACCOUNT)?.let { runCatching { AccountInfo.parse(JSONObject(it)) }.getOrNull() }

    val isSignedIn: Boolean get() = account != null && api.isSignedIn

    /** The `since` the next pull sends. */
    val cursor: Long get() = kv.get(K_CURSOR)?.toLongOrNull() ?: 0L

    /** When a sync last finished cleanly (epoch ms), or null. */
    val lastSynced: Long? get() = kv.get(K_LAST_SYNCED)?.toLongOrNull()

    /** "You were signed out": set when the server refused the refresh token, cleared by a sign-in or a dismiss. */
    val signedOutBanner: Boolean get() = kv.get(K_BANNER) == "1"

    val outbox: SyncOutbox get() = SyncOutbox.decode(kv.get(K_OUTBOX))

    val pendingDays: Set<LocalDate>
        get() = strings(K_PENDING_DAYS).mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSortedSet()

    val pendingState: Set<String> get() = strings(K_PENDING_STATE).toSet()

    /** This device's own seconds per day (fixture `merge.deviceDays`): what days are pushed from. */
    val ownDays: Map<LocalDate, Int>
        get() = kv.get(K_OWN_DAYS)?.let { runCatching { SyncWire.decodeDays(JSONObject(it)) }.getOrNull() } ?: emptyMap()

    val ownDaysSeeded: Boolean get() = kv.get(K_OWN_SEEDED) == "1"

    /** True from a sign-in until its snapshot has been taken. */
    val snapshotPending: Boolean get() = kv.get(K_SNAPSHOT_PENDING) == "1"

    /** Per synced key: when it last changed (epoch ms) and the wire value it had then. */
    data class Stamp(val updatedAt: Long, val signature: String)

    val stamps: Map<String, Stamp>
        get() {
            val o = kv.get(K_STAMPS)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
            val out = LinkedHashMap<String, Stamp>()
            for (k in o.keys()) runCatching {
                val e = o.getJSONObject(k)
                out[k] = Stamp(e.getLong("updatedAt"), e.optString("sig", ""))
            }
            return out
        }

    private fun saveAccount(a: AccountInfo?) {
        kv.put(
            K_ACCOUNT,
            a?.let {
                JSONObject().put("id", it.id).put("email", it.email ?: JSONObject.NULL)
                    .put("callsign", it.callsign ?: JSONObject.NULL).put("displayName", it.displayName ?: JSONObject.NULL)
                    .toString()
            }
        )
    }

    private fun strings(key: String): List<String> {
        val a = kv.get(key)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { s -> s.isNotEmpty() } }
    }

    private fun putStrings(key: String, values: Collection<String>) {
        kv.put(key, if (values.isEmpty()) null else JSONArray(values.toList()).toString())
    }

    private fun setOutbox(o: SyncOutbox) = kv.put(K_OUTBOX, if (o.isEmpty) null else o.encode())
    private fun setPendingDays(days: Collection<LocalDate>) = putStrings(K_PENDING_DAYS, days.sorted().map { it.toString() })
    private fun setPendingState(keys: Collection<String>) = putStrings(K_PENDING_STATE, keys)
    private fun setOwnDays(days: Map<LocalDate, Int>) {
        val o = JSONObject()
        for ((d, s) in days) o.put(d.toString(), s)
        kv.put(K_OWN_DAYS, o.toString())
    }

    private fun setStamps(map: Map<String, Stamp>) {
        val o = JSONObject()
        for ((k, s) in map) o.put(k, JSONObject().put("updatedAt", s.updatedAt).put("sig", s.signature))
        kv.put(K_STAMPS, o.toString())
    }

    private fun setCursor(value: Long) = kv.put(K_CURSOR, value.toString())

    // ---- Hooks (synchronous; the local write has already happened) ----

    /**
     * A session just finished on this device: the own-day record grows by the
     * day and whole seconds the ledger recorded, and while signed in the
     * record is queued.
     */
    fun sessionRecorded(record: SessionRecord, day: LocalDate, seconds: Int) {
        growOwn(day, seconds)
        if (isSignedIn) {
            setOutbox(outbox.enqueue(SyncOutbox.Entry(record.id.toString().lowercase(), SyncWire.encodeSession(record).toString())))
            setPendingDays(pendingDays + day)
        }
        onChange()
    }

    /** A practice day with no session (a Daily Dit guess): the day joins the own record with +0. */
    fun practiceDay(day: LocalDate) {
        growOwn(day, 0)
        if (isSignedIn && day !in pendingDays) setPendingDays(pendingDays + day)
        onChange()
    }

    /**
     * A synced store saved [key]. While signed in, a value that differs from
     * the last stamped one is stamped now and queued; saving the same value
     * again (the Characters track saves after every answer) changes nothing.
     */
    fun stateChanged(key: String) {
        if (applying || !isSignedIn || key !in SyncState.ALL_KEYS) return
        val sig = host.stateValue(key)?.toString() ?: return
        val all = stamps
        if (all[key]?.signature == sig) return
        setStamps(all + (key to Stamp(host.now(), sig)))
        setPendingState(pendingState + key)
    }

    private fun growOwn(day: LocalDate, seconds: Int) {
        val own = java.util.TreeMap(ownDays)
        own[day] = (own[day] ?: 0) + maxOf(0, seconds)
        while (own.size > ActivityLedger.CAP_DAYS) own.pollFirstEntry()
        setOwnDays(own)
    }

    // ---- Sign-in and sign-out ----

    /**
     * After a successful sign-in poll (README §7 rule 1): seed the own-day
     * record from the local ledger if this device never has, queue the whole
     * local history, every own day and every state key saved on this device
     * (a never-stamped one at updatedAt 0), then [sync],
     * which drains them and only then takes the snapshot. A device with data
     * loses nothing; resending is idempotent server-side.
     */
    suspend fun signedIn(info: AccountInfo?): SyncOutcome {
        lock.withLock {
            val known = info ?: (api.me() as? AccountResult.Reply)?.takeIf { it.code in 200..299 }?.let { AccountInfo.parse(it.body) }
            saveAccount(known ?: AccountInfo("unknown", null, null, null))
            kv.put(K_BANNER, null)
            if (!ownDaysSeeded) {
                setOwnDays(host.ledger())
                kv.put(K_OWN_SEEDED, "1")
            }
            var box = SyncOutbox()
            for (r in host.history().sortedWith(compareBy<SessionRecord> { it.date }.thenBy { it.id.toString() })) {
                box = box.enqueue(SyncOutbox.Entry(r.id.toString().lowercase(), SyncWire.encodeSession(r).toString()))
            }
            setOutbox(box)
            setPendingDays(ownDays.keys)
            // A key never stamped goes out with updatedAt 0 (fixture `merge.state`):
            // any value the account already has wins, and this device's value
            // only fills a key the account lacks, so onboarding defaults never
            // overwrite real progress. A key never saved here is not sent, and
            // nor is a setting still at its default (fixture
            // `settings.stamping.firstSignIn`): the account's value fills it.
            val next = LinkedHashMap(stamps)
            for (key in SyncState.ALL_KEYS) {
                val value = host.stateValue(key) ?: continue
                if (SyncSettings.isSetting(key) && next[key] == null && SyncSettings.isDefault(key, value)) continue
                val prior = next[key]
                next[key] = Stamp(prior?.updatedAt ?: 0L, value.toString())
            }
            setStamps(next)
            setPendingState(SyncState.ALL_KEYS)
            setCursor(0L)
            kv.put(K_SNAPSHOT_PENDING, "1")
            onChange()
            return syncLocked()
        }
    }

    /** Sign out this device (`POST /v1/auth/logout`, whatever it answers), keeping every local record. */
    suspend fun signOut() {
        lock.withLock {
            api.logout()
            clearSession(banner = false)
        }
    }

    /** `DELETE /v1/me`; on success the account state goes as for a sign-out. */
    suspend fun deleteAccount(): AccountResult = lock.withLock {
        val result = api.deleteAccount()
        when {
            result is AccountResult.Reply && result.code in 200..299 -> clearSession(banner = false)
            result is AccountResult.SignedOut -> clearSession(banner = true)
        }
        result
    }

    fun dismissBanner() {
        kv.put(K_BANNER, null)
        onChange()
    }

    /**
     * Drop the account, the cursor, the queues, the last-synced time and the
     * pending snapshot (the tokens are already gone). Local history, the
     * ledger, the own-day record, its seeded flag and the state stamps stay.
     */
    private fun clearSession(banner: Boolean) {
        for (k in listOf(K_ACCOUNT, K_CURSOR, K_OUTBOX, K_PENDING_DAYS, K_PENDING_STATE, K_LAST_SYNCED, K_SNAPSHOT_PENDING)) kv.put(k, null)
        kv.put(K_BANNER, if (banner) "1" else null)
        failures = 0
        onChange()
    }

    // ---- Profile and devices ----

    /** `PATCH /v1/me`. Null on success (the stored account updated), else the line to show. */
    suspend fun updateProfile(callsign: String?, displayName: String?): String? {
        val result = api.updateProfile(callsign, displayName)
        return when (result) {
            is AccountResult.Reply -> if (result.code in 200..299) {
                val me = AccountInfo.parse(result.body)
                val prior = account
                saveAccount(
                    AccountInfo(
                        id = me?.id ?: prior?.id ?: "unknown",
                        email = me?.email ?: prior?.email,
                        callsign = me?.callsign,
                        displayName = me?.displayName
                    )
                )
                onChange()
                null
            } else {
                AccountApi.reasonOf(result.code, result.body)
            }
            is AccountResult.SignedOut -> { forcedSignOut(); "" }
            is AccountResult.Offline -> result.message ?: "offline"
        }
    }

    /** `GET /v1/auth/devices`, or null when it failed. */
    suspend fun devices(): List<AccountDevice>? = when (val r = api.devices()) {
        is AccountResult.Reply -> if (r.code in 200..299) AccountDevice.parseList(r.body) else null
        is AccountResult.SignedOut -> { forcedSignOut(); null }
        is AccountResult.Offline -> null
    }

    /** `DELETE /v1/auth/devices/{id}`. True when the device is gone (204, or 404: already gone). */
    suspend fun revokeDevice(id: String): Boolean = when (val r = api.revokeDevice(id)) {
        is AccountResult.Reply -> r.code in 200..299 || r.code == 404
        is AccountResult.SignedOut -> { forcedSignOut(); false }
        is AccountResult.Offline -> false
    }

    private fun forcedSignOut() {
        if (account != null) clearSession(banner = true)
    }

    // ---- Sync ----

    /** Back to the first retry delay (on foreground). */
    fun resetBackoff() {
        failures = 0
    }

    /** Push what is queued, then pull (or take the pending snapshot). Never throws for a network failure. */
    suspend fun sync(): SyncOutcome = lock.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncOutcome {
        // Tokens gone underneath a stored account (a refresh refused outside a sync).
        if (account != null && !api.isSignedIn) {
            clearSession(banner = true)
            return SyncOutcome.SIGNED_OUT
        }
        if (!isSignedIn) return SyncOutcome.IDLE
        var outcome = pushSessions()
        if (outcome == SyncOutcome.OK) outcome = pushDays()
        if (outcome == SyncOutcome.OK) outcome = pushState()
        if (outcome == SyncOutcome.OK) outcome = if (snapshotPending) snapshot() else pull()
        // A pull carries no days, streak or stats: read them after it (the snapshot already has them).
        if (outcome == SyncOutcome.OK && !pulledFromSnapshot) outcome = fetchStats()
        pulledFromSnapshot = false
        when (outcome) {
            SyncOutcome.OK -> {
                failures = 0
                kv.put(K_LAST_SYNCED, host.now().toString())
            }
            SyncOutcome.FAILED -> failures++
            SyncOutcome.SIGNED_OUT -> forcedSignOut()
            SyncOutcome.IDLE -> {}
        }
        onChange()
        return outcome
    }

    /** Set by [snapshot], whose `stats` is already the full stats body. */
    private var pulledFromSnapshot = false

    /**
     * The full stats body (`GET /v1/me/stats` or the snapshot's `stats`):
     * aggregates (`merge.aggregates`), then `activity.days` into the displayed
     * ledger (`merge.ledger`), then the streak.
     */
    private fun adoptFullStats(stats: JSONObject) {
        host.saveTotals(SyncMerge.adoptAggregates(host.totals(), stats))
        val days = SyncWire.decodeDays(stats.optJSONObject("activity")?.opt("days"))
        if (days.isNotEmpty()) host.saveLedger(SyncMerge.mergeLedger(host.ledger(), days))
        if (stats.has("streak")) host.saveStreak(SyncMerge.adoptStreak(host.streak(), stats))
    }

    private suspend fun fetchStats(): SyncOutcome = when (val step = classify(api.stats(host.today()))) {
        is Step.Ok -> {
            adoptFullStats(step.body)
            SyncOutcome.OK
        }
        // A grant without stats:read: nothing to read, not a failure.
        Step.Drop -> SyncOutcome.OK
        Step.Retry -> SyncOutcome.FAILED
        Step.SignedOut -> SyncOutcome.SIGNED_OUT
    }

    /** What to do with one call's result. */
    private sealed class Step {
        class Ok(val body: JSONObject) : Step()
        /** Refused for good: do not send it again. */
        data object Drop : Step()
        data object Retry : Step()
        data object SignedOut : Step()
    }

    private fun classify(r: AccountResult): Step = when (r) {
        is AccountResult.Reply -> when (SyncRetry.action(r.code)) {
            SyncAction.DONE -> Step.Ok(r.body ?: JSONObject())
            SyncAction.DROP -> Step.Drop
            // AccountApi has already refreshed and retried a 401 once.
            SyncAction.REFRESH, SyncAction.BACKOFF -> Step.Retry
        }
        is AccountResult.SignedOut -> Step.SignedOut
        is AccountResult.Offline -> Step.Retry
    }

    private fun adoptStats(body: JSONObject) {
        val stats = body.optJSONObject("stats") ?: return
        host.saveTotals(SyncMerge.adoptAggregates(host.totals(), stats))
    }

    private suspend fun pushSessions(): SyncOutcome {
        while (true) {
            val batch = outbox.nextBatch()
            if (batch.isEmpty()) return SyncOutcome.OK
            val payloads = batch.mapNotNull { runCatching { JSONObject(it.payload) }.getOrNull() }
            val ids = batch.map { it.id.lowercase() }.toSet()
            when (val step = classify(api.pushEncodedSessions(payloads))) {
                is Step.Ok -> {
                    val before = outbox
                    val after = before.applyPushReply(step.body)
                    adoptStats(step.body)
                    // A 2xx naming none of the batch would loop forever: retry later instead.
                    if (after.ids.containsAll(before.ids.filter { it.lowercase() in ids })) return SyncOutcome.FAILED
                    setOutbox(after)
                }
                Step.Drop -> setOutbox(outbox.copy(entries = outbox.entries.filter { it.id.lowercase() !in ids }))
                Step.Retry -> return SyncOutcome.FAILED
                Step.SignedOut -> return SyncOutcome.SIGNED_OUT
            }
        }
    }

    private suspend fun pushDays(): SyncOutcome {
        val queued = pendingDays
        if (queued.isEmpty()) return SyncOutcome.OK
        val sent = SyncMerge.ownDaysToPush(ownDays, queued)
        if (sent.isNotEmpty()) {
            when (val step = classify(api.pushDays(sent))) {
                is Step.Ok -> {
                    val reply = SyncWire.decodeDays(step.body.opt("days"))
                    host.saveLedger(SyncMerge.mergeLedger(host.ledger(), reply))
                }
                Step.Drop -> {}
                Step.Retry -> return SyncOutcome.FAILED
                Step.SignedOut -> return SyncOutcome.SIGNED_OUT
            }
        }
        // A day that grew while the request was out goes again next time.
        val own = ownDays
        setPendingDays(pendingDays.filter { it !in queued || (it in sent && own[it] != sent[it]) })
        return SyncOutcome.OK
    }

    /**
     * One `PUT /v1/sync/state` per sync with EVERY key saved on this device at
     * its current stamp, changed or not (a never-stamped key at 0; a
     * never-saved key is left out). State comes back only in a state reply or
     * the snapshot, so this is how a device that changed nothing learns
     * another device's newer Journey or ladder position: each strictly newer
     * winner in the reply is applied.
     */
    private suspend fun pushState(): SyncOutcome {
        val queued = pendingState.filter { it in SyncState.ALL_KEYS }
        val sentStamps = LinkedHashMap(stamps)
        val entries = LinkedHashMap<String, StateEntry>()
        for (key in SyncState.ALL_KEYS) {
            val raw = host.stateValue(key) ?: continue
            // A setting goes only once stamped (a change made here, or a first
            // sign-in's non-default value), and always normalised.
            val value = if (SyncSettings.isSetting(key)) {
                if (sentStamps[key] == null) continue
                SyncSettings.normalize(key, raw) ?: continue
            } else raw
            val stamp = sentStamps[key] ?: Stamp(0L, value.toString()).also { sentStamps[key] = it }
            entries[key] = StateEntry(value, stamp.updatedAt)
        }
        setStamps(sentStamps)
        var reply: Map<String, StateEntry> = emptyMap()
        if (entries.isNotEmpty()) {
            when (val step = classify(api.putState(entries))) {
                is Step.Ok -> reply = SyncMerge.decodeStateEntries(step.body.optJSONObject("entries"))
                Step.Drop -> {}
                Step.Retry -> return SyncOutcome.FAILED
                Step.SignedOut -> return SyncOutcome.SIGNED_OUT
            }
        }
        // A key re-stamped while the request was out goes again next time.
        val current = stamps
        setPendingState(pendingState.filter { it !in queued || current[it]?.updatedAt != sentStamps[it]?.updatedAt })
        applyStateEntries(reply)
        return SyncOutcome.OK
    }

    /** Each received value strictly newer than the local stamp goes into the live store (`merge.state`). */
    private fun applyStateEntries(reply: Map<String, StateEntry>) {
        if (reply.isEmpty()) return
        val local = stamps.mapValues { (_, s) -> StateEntry(s.signature, s.updatedAt) }
        val merged = SyncMerge.mergeState(local, reply)
        val next = LinkedHashMap(stamps)
        for ((key, entry) in reply) {
            if (key !in SyncState.ALL_KEYS || merged[key] !== entry) continue
            // A setting from another platform is clamped into this app's
            // range; one it cannot read leaves the local value and stamp.
            val value: Any = if (SyncSettings.isSetting(key)) {
                SyncSettings.normalize(key, entry.value) ?: continue
            } else {
                entry.value as? JSONObject ?: continue
            }
            applying = true
            try {
                host.applyState(key, value)
            } finally {
                applying = false
            }
            next[key] = Stamp(entry.updatedAt, host.stateValue(key)?.toString() ?: value.toString())
        }
        setStamps(next)
    }

    /** Merge pulled rows and save them; false when saving failed (the cursor must then stay). */
    private fun mergeRows(rows: List<SessionRecord>): Boolean {
        if (rows.isEmpty()) return true
        return try {
            host.saveHistory(SyncMerge.mergeSessions(host.history(), rows))
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun pull(): SyncOutcome {
        var since = cursor
        while (true) {
            when (val step = classify(api.pullSessions(since, PULL_PAGE))) {
                is Step.Ok -> {
                    if (!mergeRows(SyncWire.decodeSessions(step.body.optJSONArray("sessions")))) return SyncOutcome.FAILED
                    adoptStats(step.body)
                    val next = step.body.optLong("nextSince", since)
                    if (next > since) setCursor(next)
                    if (!step.body.optBoolean("hasMore", false) || next <= since) return SyncOutcome.OK
                    since = next
                }
                // A refused pull would be refused again; the next sync tries afresh.
                Step.Drop -> return SyncOutcome.OK
                Step.Retry -> return SyncOutcome.FAILED
                Step.SignedOut -> return SyncOutcome.SIGNED_OUT
            }
        }
    }

    /** The restore after a sign-in (README §7 snapshot), once everything local has been pushed. */
    private suspend fun snapshot(): SyncOutcome {
        when (val step = classify(api.snapshot(host.today()))) {
            is Step.Ok -> {
                val b = step.body
                if (!mergeRows(SyncWire.decodeSessions(b.optJSONArray("sessions")))) return SyncOutcome.FAILED
                val days = SyncWire.decodeDays(b.opt("days"))
                if (days.isNotEmpty()) host.saveLedger(SyncMerge.mergeLedger(host.ledger(), days))
                // The snapshot's stats is the full /v1/me/stats body: totals, its ledger, the streak.
                b.optJSONObject("stats")?.let { adoptFullStats(it) }
                pulledFromSnapshot = true
                applyStateEntries(SyncMerge.decodeStateEntries(b.optJSONObject("state")))
                setCursor(maxOf(cursor, b.optLong("seq", 0L)))
                kv.put(K_SNAPSHOT_PENDING, null)
                return SyncOutcome.OK
            }
            Step.Drop -> {
                kv.put(K_SNAPSHOT_PENDING, null)
                return SyncOutcome.OK
            }
            Step.Retry -> return SyncOutcome.FAILED
            Step.SignedOut -> return SyncOutcome.SIGNED_OUT
        }
    }

    companion object {
        const val PULL_PAGE = 200

        const val K_ACCOUNT = "account"
        const val K_CURSOR = "pullCursor"
        const val K_LAST_SYNCED = "lastSynced"
        const val K_OUTBOX = "outbox"
        const val K_PENDING_DAYS = "pendingDays"
        const val K_PENDING_STATE = "pendingState"
        const val K_OWN_DAYS = "ownDays"
        const val K_OWN_SEEDED = "ownDaysSeeded"
        const val K_STAMPS = "stateStamps"
        const val K_SNAPSHOT_PENDING = "snapshotPending"
        const val K_BANNER = "signedOutBanner"
    }
}
