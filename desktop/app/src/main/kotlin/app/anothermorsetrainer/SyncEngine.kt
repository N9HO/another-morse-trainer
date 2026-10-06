package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.SyncAccountState
import app.anothermorsetrainer.morsekit.SyncMerge
import app.anothermorsetrainer.morsekit.SyncOwnDays
import app.anothermorsetrainer.morsekit.SyncStateCodec
import app.anothermorsetrainer.morsekit.SyncStreak
import app.anothermorsetrainer.morsekit.SyncWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.LocalDate

/**
 * What the sync engine needs from the rest of the app: the local records it
 * pushes and the places it writes what the server sends back. The app's
 * implementation is [AppSyncLocal] (Stats and the progress stores); the JUnit
 * suite drives the engine with an in-memory one.
 */
interface SyncLocal {
    /** Every session row this device holds, any order. */
    fun historyRecords(): List<SessionRecord>

    /** The displayed ledger: whole seconds per local day. */
    fun ledgerDays(): Map<LocalDate, Int>

    /** Merge pulled or snapshot rows into local history and save. False when saving failed. */
    fun mergeSessions(pulled: List<SessionRecord>): Boolean

    /** Replace the lifetime counters with the server's. */
    fun adoptAggregates(aggregates: SyncMerge.Aggregates)

    /** Adopt the server's summed per-day figures into the displayed ledger. */
    fun adoptDays(server: Map<LocalDate, Int>)

    /** Adopt the account's streak (after its days), per [SyncStreak.adopt]. */
    fun adoptStreak(server: SyncStreak.Server)

    /** The wire value of a synced state key, or null when nothing was ever saved for it here. */
    fun stateValue(key: String): JSONObject?

    /** Write a winning wire value into the live store (no stamp, no enqueue). */
    fun applyState(key: String, value: JSONObject)
}

/** Where [SyncEngine] keeps [SyncAccountState]. */
interface SyncStore {
    fun load(): SyncAccountState
    fun save(state: SyncAccountState)
}

/** The app's store: one JSON document under `state` in the `amt_account` prefs file. */
class PrefsSyncStore(private val prefs: Prefs) : SyncStore {
    override fun load(): SyncAccountState = SyncAccountState.decode(prefs.getString(KEY, null))
    override fun save(state: SyncAccountState) {
        prefs.edit { putString(KEY, state.encode().toString()) }
    }

    private companion object {
        const val KEY = "state"
    }
}

/**
 * The account sync engine (Phase 2 of the accounts work; rules pinned by
 * `fixtures/sync-wire.json`'s `merge`, contract in the accounts README §7):
 * the outbox, the drain, the pull and the restore snapshot, and sign-out.
 *
 * Every local write is made locally first and only then noted here
 * ([sessionRecorded], [practiceDayMarked], [stateChanged]); the network never
 * gates it. [sync] drains the outbox (sessions, then days, then state) and
 * then takes the snapshot (once, after sign-in) or pulls. It never throws and
 * never shows a transient failure: it reports a [Outcome] and keeps a backoff
 * count ([retryDelaySeconds]) for the caller to schedule the retry.
 *
 * No Compose, no scheduler: [SyncCoordinator] owns the scope and the timing,
 * which keeps this class testable against a fake transport. State changes go
 * through one lock, so a hook running on another thread while a sync is
 * suspended on the network never loses an update.
 */
class SyncEngine(
    private val client: AccountClient,
    private val store: SyncStore,
    private val local: SyncLocal,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now
) {
    /** How a [sync] ended. */
    enum class Outcome {
        /** Everything sent and pulled (or nothing to do). */
        DONE,
        /** Not signed in. */
        IDLE,
        /** A transient failure: retry after [retryDelaySeconds]. */
        BACKOFF,
        /** The server refused the tokens; the device is signed out and the banner is up. */
        SIGNED_OUT
    }

    private companion object {
        /** `updatedAt` for a value never changed while signed in: any stamped server value wins over it. */
        const val UNSTAMPED = 0L
    }

    private val lock = Any()
    private val _state = MutableStateFlow(store.load())

    /** The persisted state, observable. */
    val state: StateFlow<SyncAccountState> = _state.asStateFlow()

    private val current: SyncAccountState get() = _state.value

    /** One [sync] at a time. */
    private val running = Mutex()

    /** Consecutive failed syncs; 0 after a success. */
    @Volatile var failures: Int = 0
        private set

    /** Seconds before the next retry, or null when the last sync did not fail. */
    fun retryDelaySeconds(): Long? = if (failures == 0) null else SyncMerge.backoffSeconds(failures)

    /** Back to retry 1 (the app came to the foreground). */
    fun resetBackoff() { failures = 0 }

    private fun mutate(change: (SyncAccountState) -> SyncAccountState) {
        synchronized(lock) {
            val next = change(_state.value)
            if (next == _state.value) return
            _state.value = next
            store.save(next)
        }
    }

    // ---- Hooks (local writes, already made) ----

    /**
     * A session finished and is in local history and the ledger under [day]
     * with [seconds]. The own-day record grows whether or not anyone is signed
     * in; the record and the day are queued only while signed in.
     */
    fun sessionRecorded(record: SessionRecord, day: LocalDate, seconds: Int) {
        val encoded = SyncWire.encodeSession(record).toString()
        mutate { s ->
            val grown = s.copy(ownDays = SyncOwnDays.record(s.ownDays, day, seconds))
            if (!grown.isSignedIn) grown
            else grown.enqueueSession(record.id.toString(), encoded).copy(dayOutbox = grown.dayOutbox + day)
        }
    }

    /** A practice day was marked with no session (a Daily Dit guess): the own record gains the day at +0. */
    fun practiceDayMarked(day: LocalDate) {
        mutate { s ->
            val grown = s.copy(ownDays = SyncOwnDays.record(s.ownDays, day, 0))
            if (!grown.isSignedIn) grown else grown.copy(dayOutbox = grown.dayOutbox + day)
        }
    }

    /** A synced state key's value changed locally: stamped now and queued, while signed in. */
    fun stateChanged(key: String) {
        if (key !in SyncStateCodec.keys) return
        mutate { s ->
            if (!s.isSignedIn) s
            else s.copy(stateStamps = s.stateStamps + (key to now()), stateOutbox = s.stateOutbox + key)
        }
    }

    /** The user closed the "you were signed out" banner. */
    fun dismissBanner() = mutate { it.copy(signedOutBanner = false) }

    // ---- Sign-in, sign-out ----

    /**
     * The sign-in link was confirmed and the tokens are stored. Queues
     * everything this device holds so nothing local is lost: the own-day
     * record (seeded from the ledger if it never was), the whole history
     * oldest first, every own day, and each state key that has a local value
     * (`updatedAt` 0 if it was never stamped, else its stamp: an unstamped
     * value, such as one saved at onboarding before sign-in, only fills a
     * key the account lacks and never beats its real progress; the fixture's
     * `merge.state`). The restore snapshot is
     * then owed; the caller runs [sync], which drains first and takes the
     * snapshot after.
     *
     * A key with nothing saved locally is not sent: its default would win
     * over real progress from another device, and the snapshot fills it.
     */
    fun signedIn(account: AccountInfo) {
        val history = local.historyRecords().sortedBy { it.date }
        val encoded = history.map { it.id.toString() to SyncWire.encodeSession(it).toString() }
        val values = SyncStateCodec.keys.filter { local.stateValue(it) != null }
        val ledger = local.ledgerDays()
        mutate { before ->
            var s = before.copy(
                accountId = account.id,
                email = account.email,
                callsign = account.callsign,
                displayName = account.displayName,
                cursor = 0,
                lastSyncedAt = null,
                needsSnapshot = true,
                signedOutBanner = false
            )
            if (!s.ownDaysSeeded) s = s.copy(ownDays = SyncOwnDays.seed(ledger), ownDaysSeeded = true)
            for ((id, json) in encoded) s = s.enqueueSession(id, json)
            s = s.copy(dayOutbox = s.dayOutbox + s.ownDays.keys)
            var stamps = s.stateStamps
            for (key in values) if (key !in stamps) stamps = stamps + (key to UNSTAMPED)
            s.copy(stateStamps = stamps, stateOutbox = s.stateOutbox + values)
        }
        failures = 0
    }

    /** `POST /v1/auth/logout`, then tokens, cursor, outbox and last-synced dropped. Local data stays. */
    suspend fun signOut() {
        client.logout()
        mutate { it.signedOut(banner = false) }
    }

    /**
     * `DELETE /v1/me`. On success the account and its synced data are gone
     * from the server and this device is signed out as for [signOut]; its
     * local data stays. A failure is returned for the caller to show.
     */
    suspend fun deleteAccount(): AccountResult<Unit> {
        val r = client.deleteAccount()
        when (r) {
            is AccountResult.Ok -> mutate { it.signedOut(banner = false) }
            AccountResult.SignedOut -> forcedSignOut()
            is AccountResult.Failed -> {}
        }
        return r
    }

    /** The server refused the tokens (the client has already cleared them). */
    private fun forcedSignOut() {
        mutate { if (it.isSignedIn) it.signedOut(banner = true) else it }
    }

    // ---- Account calls the Settings section makes ----

    /** `PATCH /v1/me`; the stored callsign and name follow the server's reply. */
    suspend fun updateProfile(callsign: String?, displayName: String?): AccountResult<AccountInfo> {
        val r = client.updateProfile(callsign, displayName)
        when (r) {
            is AccountResult.Ok -> mutate { it.copy(callsign = r.value.callsign, displayName = r.value.displayName) }
            AccountResult.SignedOut -> forcedSignOut()
            is AccountResult.Failed -> {}
        }
        return r
    }

    suspend fun devices(): AccountResult<List<AccountDevice>> =
        client.devices().also { if (it == AccountResult.SignedOut) forcedSignOut() }

    suspend fun revokeDevice(id: String): AccountResult<Unit> =
        client.revokeDevice(id).also { if (it == AccountResult.SignedOut) forcedSignOut() }

    // ---- Sync ----

    /**
     * Drain the outbox, then take the snapshot (owed after sign-in) or pull.
     * On [Outcome.DONE] the last-synced time moves and the backoff resets; on
     * [Outcome.BACKOFF] the backoff count grows. Calls made while one is
     * running wait for it.
     */
    suspend fun sync(): Outcome = running.withLock {
        if (!current.isSignedIn) return@withLock Outcome.IDLE
        var outcome = drain()
        if (outcome == Outcome.DONE) outcome = if (current.needsSnapshot) snapshot() else pull()
        when (outcome) {
            Outcome.DONE -> {
                failures = 0
                mutate { if (it.isSignedIn) it.copy(lastSyncedAt = now()) else it }
            }
            Outcome.BACKOFF -> failures += 1
            Outcome.SIGNED_OUT, Outcome.IDLE -> failures = 0
        }
        outcome
    }

    /** Sessions (oldest first, batches of 200), then days, then state. */
    private suspend fun drain(): Outcome {
        drainSessions().let { if (it != Outcome.DONE) return it }
        drainDays().let { if (it != Outcome.DONE) return it }
        return drainState()
    }

    private suspend fun drainSessions(): Outcome {
        while (!current.sessionOutbox.isEmpty) {
            val s = current
            val batch = s.sessionOutbox.nextBatch()
            val records = ArrayList<JSONObject>(batch.size)
            val unreadable = HashSet<String>()
            for (id in batch) {
                val o = s.sessionPayloads[id]?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (o == null) unreadable.add(id) else records.add(o)
            }
            if (unreadable.isNotEmpty()) {
                mutate { it.withSessionOutbox(SyncMerge.Outbox(it.sessionOutbox.ids.filter { id -> id !in unreadable })) }
            }
            if (records.isEmpty()) continue
            when (val r = client.pushEncodedSessions(records)) {
                is AccountResult.Ok -> {
                    val sizeBefore = current.sessionOutbox.ids.size
                    mutate { it.withSessionOutbox(it.sessionOutbox.afterPush(r.value)) }
                    adoptStats(r.value.stats)
                    // A 2xx naming none of the batch would loop forever; leave the rest for next time.
                    if (current.sessionOutbox.ids.size >= sizeBefore) return Outcome.DONE
                }
                is AccountResult.Failed -> {
                    if (r.action != SyncMerge.Action.DROP) return Outcome.BACKOFF
                    // A 4xx: resending this batch cannot help, so it leaves the outbox (it stays in history).
                    val sent = batch.toHashSet()
                    mutate { it.withSessionOutbox(SyncMerge.Outbox(it.sessionOutbox.ids.filter { id -> id !in sent })) }
                }
                AccountResult.SignedOut -> { forcedSignOut(); return Outcome.SIGNED_OUT }
            }
        }
        return Outcome.DONE
    }

    /** The own figures for the queued days; the reply's summed figures go into the displayed ledger. */
    private suspend fun drainDays(): Outcome {
        while (current.dayOutbox.isNotEmpty()) {
            val s = current
            val queued = s.dayOutbox.sorted().take(SyncOwnDays.BATCH_SIZE)
            val body = SyncOwnDays.pushBody(s.ownDays, queued)
            val missing = queued.filter { it !in body }
            if (missing.isNotEmpty()) mutate { it.copy(dayOutbox = it.dayOutbox - missing.toSet()) }
            if (body.isEmpty()) continue
            when (val r = client.pushDays(body)) {
                is AccountResult.Ok -> {
                    // A day whose own figure grew while the request was out stays queued.
                    mutate { st -> st.copy(dayOutbox = st.dayOutbox.filterTo(LinkedHashSet()) { d -> body[d] == null || st.ownDays[d] != body[d] }) }
                    local.adoptDays(r.value)
                }
                is AccountResult.Failed -> {
                    if (r.action != SyncMerge.Action.DROP) return Outcome.BACKOFF
                    mutate { it.copy(dayOutbox = it.dayOutbox - body.keys) }
                }
                AccountResult.SignedOut -> { forcedSignOut(); return Outcome.SIGNED_OUT }
            }
        }
        return Outcome.DONE
    }

    /**
     * One `PUT /v1/sync/state` per sync, after sessions and days: every SAVED
     * key with its current stamp (0 when never stamped), not only the queued
     * ones, and each winner strictly newer than local is applied. State comes
     * back only in a state reply or the snapshot, so without this a device
     * that changes nothing would never learn another device's newer Journey
     * or ladder position. Keys never saved here are omitted.
     */
    private suspend fun drainState(): Outcome {
        val s = current
        val entries = LinkedHashMap<String, SyncWire.StateEntry>()
        for (key in SyncStateCodec.keys) {
            val value = local.stateValue(key) ?: continue
            entries[key] = SyncWire.StateEntry(value, s.stateStamps[key] ?: UNSTAMPED)
        }
        val empty = s.stateOutbox.filter { it !in entries }
        if (empty.isNotEmpty()) mutate { it.copy(stateOutbox = it.stateOutbox - empty.toSet()) }
        if (entries.isEmpty()) return Outcome.DONE
        return when (val r = client.putState(entries)) {
            is AccountResult.Ok -> {
                // A key changed again while the request was out keeps a newer stamp and stays queued.
                mutate { st ->
                    st.copy(stateOutbox = st.stateOutbox.filterTo(LinkedHashSet()) { k ->
                        val sent = entries[k] ?: return@filterTo true
                        st.stateStamps[k] != sent.updatedAt
                    })
                }
                applyState(r.value)
                Outcome.DONE
            }
            is AccountResult.Failed -> {
                if (r.action != SyncMerge.Action.DROP) return Outcome.BACKOFF
                mutate { it.copy(stateOutbox = it.stateOutbox - entries.keys) }
                Outcome.DONE
            }
            AccountResult.SignedOut -> { forcedSignOut(); Outcome.SIGNED_OUT }
        }
    }

    /**
     * `GET /v1/sync/snapshot`: merge its sessions, adopt its aggregates and
     * summed ledger, apply each strictly newer state entry, and only then
     * store `seq` as the cursor.
     */
    private suspend fun snapshot(): Outcome {
        return when (val r = client.snapshot(today())) {
            is AccountResult.Ok -> {
                val snap = r.value
                if (snap.sessions.isNotEmpty() && !local.mergeSessions(snap.sessions)) return Outcome.BACKOFF
                adoptStats(snap.stats)
                if (snap.days.isNotEmpty()) local.adoptDays(snap.days)
                SyncStreak.parse(snap.stats)?.let { local.adoptStreak(it) }
                applyState(snap.state)
                mutate { if (it.isSignedIn) it.copy(cursor = snap.seq, needsSnapshot = false) else it }
                Outcome.DONE
            }
            is AccountResult.Failed -> {
                if (r.action != SyncMerge.Action.DROP) return Outcome.BACKOFF
                // Refused outright: fall back to pulling from the start.
                mutate { it.copy(needsSnapshot = false) }
                Outcome.DONE
            }
            AccountResult.SignedOut -> { forcedSignOut(); Outcome.SIGNED_OUT }
        }
    }

    /**
     * `GET /v1/sync/sessions?since=<cursor>` page by page: each page's rows
     * are merged and saved, THEN the cursor advances. A page that cannot be
     * saved leaves the cursor where it was, so it is fetched again.
     */
    private suspend fun pull(): Outcome {
        while (true) {
            val since = current.cursor
            when (val r = client.pullSessions(since)) {
                is AccountResult.Ok -> {
                    val page = r.value
                    if (page.sessions.isNotEmpty() && !local.mergeSessions(page.sessions)) return Outcome.BACKOFF
                    val next = maxOf(since, page.nextSince)
                    mutate { if (it.isSignedIn) it.copy(cursor = next) else it }
                    if (!page.hasMore || next == since) break
                }
                is AccountResult.Failed -> {
                    if (r.action != SyncMerge.Action.DROP) return Outcome.BACKOFF
                    break
                }
                AccountResult.SignedOut -> { forcedSignOut(); return Outcome.SIGNED_OUT }
            }
        }
        // A pull carries no days or stats: after every pull, fetch the
        // account's aggregates, summed ledger and streak, so another device's
        // practice days arrive here too, not only at sign-in.
        return when (val r = client.stats(today())) {
            is AccountResult.Ok -> {
                adoptStats(r.value)
                r.value.optJSONObject("activity")?.optJSONObject("days")?.let { days ->
                    val map = SyncWire.decodeDayMap(days)
                    if (map.isNotEmpty()) local.adoptDays(map)
                }
                SyncStreak.parse(r.value)?.let { local.adoptStreak(it) }
                Outcome.DONE
            }
            AccountResult.SignedOut -> { forcedSignOut(); Outcome.SIGNED_OUT }
            is AccountResult.Failed -> if (r.action == SyncMerge.Action.DROP) Outcome.DONE else Outcome.BACKOFF
        }
    }

    private fun adoptStats(stats: JSONObject?) {
        SyncMerge.adoptAggregates(stats)?.let { local.adoptAggregates(it) }
    }

    /**
     * Each entry strictly newer than the local stamp (or with no local stamp)
     * is written into the live store and its stamp taken; a tie or an older
     * one keeps local (`merge.state`). Keys this build does not know are
     * ignored.
     */
    private fun applyState(reply: Map<String, SyncWire.StateEntry>) {
        if (reply.isEmpty()) return
        val localStamps = current.stateStamps
        val winners = LinkedHashMap<String, SyncWire.StateEntry>()
        for ((key, theirs) in reply) {
            if (key !in SyncStateCodec.keys) continue
            // A saved but never-stamped value counts as 0: a tie at 0 keeps it.
            val mine = localStamps[key] ?: (if (local.stateValue(key) != null) UNSTAMPED else null)
            if (mine == null || theirs.updatedAt > mine) winners[key] = theirs
        }
        if (winners.isEmpty()) return
        for ((key, e) in winners) {
            val value = e.value as? JSONObject ?: continue
            runCatching { local.applyState(key, value) }
        }
        mutate { s ->
            var stamps = s.stateStamps
            for ((key, e) in winners) {
                val mine = stamps[key]
                if (mine == null || e.updatedAt > mine) stamps = stamps + (key to e.updatedAt)
            }
            s.copy(stateStamps = stamps)
        }
    }
}
