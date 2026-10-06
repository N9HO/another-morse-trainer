package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.AccountRules
import app.anothermorsetrainer.morsekit.PracticeStreak
import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.SyncMerge
import app.anothermorsetrainer.morsekit.SyncState
import app.anothermorsetrainer.morsekit.SyncWire
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap
import java.util.UUID

/**
 * [SyncEngine] against a fake transport, a map for its preferences and an
 * in-memory stand-in for `Stats` and the progress stores: no network, no
 * Android. Pins the order a first sign-in runs in, that a pull saves rows
 * before it moves the cursor, what a sign-out keeps, the forced sign-out
 * banner, the backoff reset and `fixtures/sync-wire.json`'s `merge.deviceDays`
 * (the own-day record grows, days are pushed from it, adoption touches only
 * the displayed ledger).
 */
class SyncEngineTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("sync-wire.json")
        assertNotNull("fixtures/sync-wire.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    /** Replies queued per "METHOD path" (query dropped), and every call it saw, in order. */
    private class FakeTransport : AccountTransport {
        val replies = HashMap<String, ArrayDeque<() -> AccountReply>>()
        val calls = ArrayList<String>()
        val bodies = ArrayList<JSONObject?>()
        val paths = ArrayList<String>()

        fun on(method: String, path: String, code: Int, body: JSONObject? = null) {
            replies.getOrPut("$method $path") { ArrayDeque() }.addLast { AccountReply(code, body) }
        }

        /** A reply that runs [during] first: a local change landing while the request is out. */
        fun onWhile(method: String, path: String, code: Int, body: JSONObject?, during: () -> Unit) {
            replies.getOrPut("$method $path") { ArrayDeque() }.addLast { during(); AccountReply(code, body) }
        }

        fun offline(method: String, path: String) {
            replies.getOrPut("$method $path") { ArrayDeque() }.addLast { throw IOException("offline") }
        }

        override suspend fun send(method: String, path: String, body: JSONObject?, bearer: String?): AccountReply {
            val key = "$method ${path.substringBefore('?')}"
            calls.add(key)
            paths.add(path)
            bodies.add(body)
            val next = replies[key]?.removeFirstOrNull() ?: error("no reply queued for $key")
            return next()
        }

        fun bodyOf(key: String): JSONObject = bodies[calls.indexOf(key)]!!
    }

    private class MemoryTokens(override var access: String? = null, override var refresh: String? = null) : AccountTokenStore {
        override fun saveRefresh(token: String) { refresh = token }
        override fun saveAccess(token: String) { access = token }
        override fun clear() { access = null; refresh = null }
    }

    private class MapKeyValues : SyncKeyValues {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    /** `Stats` and the stores, in memory. [engine] is set so a store save can call the hook the way the app's do. */
    private class FakeHost : SyncHost {
        var engine: SyncEngine? = null
        var history: List<SessionRecord> = emptyList()
        var ledger: Map<LocalDate, Int> = emptyMap()
        var totals = SyncMerge.LifetimeTotals(0, 0, 0, 0.0, null, emptyMap())
        var streak = PracticeStreak()
        val state = LinkedHashMap<String, JSONObject>()
        var failSave = false
        var clock = 1_000L

        override fun history() = history
        override fun saveHistory(merged: List<SessionRecord>) {
            if (failSave) throw IllegalStateException("disk full")
            history = merged
        }
        override fun ledger() = ledger
        override fun saveLedger(days: Map<LocalDate, Int>) { ledger = TreeMap(days) }
        override fun totals() = totals
        override fun saveTotals(totals: SyncMerge.LifetimeTotals) { this.totals = totals }
        override fun streak() = streak
        override fun saveStreak(streak: PracticeStreak) { this.streak = streak }
        override fun stateValue(key: String): JSONObject? = state[key]
        override fun applyState(key: String, value: JSONObject) {
            state[key] = value
            engine?.stateChanged(key)   // the store's save hook fires, as in the app
        }
        override fun today(): LocalDate = LocalDate.of(2026, 10, 5)
        override fun now(): Long = clock

        /** A local session, as `Stats.record` makes it: history, ledger, then the hook. */
        fun record(r: SessionRecord, day: LocalDate, seconds: Int) {
            history = listOf(r) + history
            val l = TreeMap(ledger)
            l[day] = (l[day] ?: 0) + seconds
            ledger = l
            engine?.sessionRecorded(r, day, seconds)
        }
    }

    private fun record(id: String, epochMs: Long, mode: String = "Characters") = SessionRecord(
        id = UUID.fromString(id),
        date = Instant.ofEpochMilli(epochMs),
        mode = mode,
        characterWPM = 20,
        effectiveWPM = 15,
        attempts = 10,
        correct = 9,
        fastestTTR = 0.5,
        medianTTR = 0.8,
        durationSeconds = 60.0,
        characters = emptyList(),
        activeCharacters = listOf("K", "M"),
        score = null
    )

    private val idA = "00000000-0000-4000-8000-00000000000a"
    private val idB = "00000000-0000-4000-8000-00000000000b"
    private val idR = "00000000-0000-4000-8000-0000000000ff"

    private class Rig(signedIn: Boolean) {
        val t = FakeTransport()
        val tokens = if (signedIn) MemoryTokens("a1", "r1") else MemoryTokens()
        val kv = MapKeyValues()
        val host = FakeHost()
        val engine = SyncEngine(AccountApi(t, tokens, "amt-android"), kv, host).also { host.engine = it }

        init {
            if (signedIn) {
                kv.put(SyncEngine.K_ACCOUNT, JSONObject().put("id", "acc-1").put("email", "learner@example.org").toString())
                kv.put(SyncEngine.K_OWN_SEEDED, "1")
            }
        }

        /** An empty pull page, and the stats read that follows every pull. */
        fun emptyPull(nextSince: Long = 0) {
            t.on("GET", "/v1/sync/sessions", 200, JSONObject().put("sessions", JSONArray()).put("nextSince", nextSince).put("hasMore", false))
            t.on("GET", "/v1/me/stats", 200, JSONObject())
        }
    }

    private fun days(o: JSONObject): Map<LocalDate, Int> = SyncWire.decodeDays(o)

    private fun stats(sessions: Int) = JSONObject()
        .put("totals", JSONObject().put("sessions", sessions).put("answered", 90).put("correct", 81).put("practiceSeconds", 1234.5))
        .put("bestTtrMs", 412)
        .put("personalBests", JSONObject().put("contest", 1240))

    private fun accepted(vararg ids: String) = JSONObject().put("accepted", JSONArray(ids.toList()))

    // ---- merge.deviceDays ----

    @Test
    fun `days are pushed from the device's own record and adopted only into the displayed ledger`() = runBlocking {
        val f = fixture.getJSONObject("merge").getJSONObject("deviceDays")
        val rig = Rig(signedIn = true)
        rig.kv.put(SyncEngine.K_OWN_DAYS, f.getJSONObject("ownBefore").toString())
        rig.host.ledger = days(f.getJSONObject("seedFromLedgerOnFirstSignIn"))

        val local = f.getJSONArray("localSessions")
        val ids = ArrayList<String>()
        for (i in 0 until local.length()) {
            val s = local.getJSONObject(i)
            val day = LocalDate.parse(s.getString("day"))
            val id = "00000000-0000-4000-8000-00000000010$i"
            ids.add(id)
            rig.host.record(record(id, 1_791_000_000_000L + i), day, s.getInt("seconds"))
        }
        assertEquals("own record after local sessions", days(f.getJSONObject("ownAfter")), rig.engine.ownDays)

        rig.t.on("POST", "/v1/sync/sessions", 200, accepted(*ids.toTypedArray()))
        rig.t.on("POST", "/v1/sync/days", 200, JSONObject().put("days", f.getJSONObject("serverReply")))
        rig.emptyPull()
        assertEquals(SyncOutcome.OK, rig.engine.sync())

        fun pairs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).let { d -> d.getString("day") to d.getInt("seconds") } }
        val pushed = rig.t.bodyOf("POST /v1/sync/days").getJSONArray("days")
        assertEquals("push body, earliest first", pairs(f.getJSONObject("expectedPushBody").getJSONArray("days")), pairs(pushed))
        assertEquals("own record untouched by adoption", days(f.getJSONObject("expectedOwnAfterAdoption")), rig.engine.ownDays)
        assertEquals("displayed ledger", days(f.getJSONObject("expectedDisplayedAfterAdoption")), rig.host.ledger)
        assertTrue(rig.engine.pendingDays.isEmpty())
    }

    @Test
    fun `the first sign-in seeds the own record from the ledger once`() = runBlocking {
        val f = fixture.getJSONObject("merge").getJSONObject("deviceDays")
        val rig = Rig(signedIn = false)
        rig.host.ledger = days(f.getJSONObject("seedFromLedgerOnFirstSignIn"))
        rig.tokens.refresh = "r1"; rig.tokens.access = "a1"
        rig.t.on("POST", "/v1/sync/days", 200, JSONObject().put("days", JSONObject()))
        rig.t.offline("GET", "/v1/sync/snapshot")

        rig.engine.signedIn(AccountInfo("acc-1", "learner@example.org", null, null))

        assertEquals(days(f.getJSONObject("ownBefore")), rig.engine.ownDays)
        assertTrue(rig.engine.ownDaysSeeded)

        // A later sign-in does not seed again over what the device has grown since,
        // even once the displayed ledger holds other devices' seconds.
        rig.engine.practiceDay(LocalDate.of(2026, 10, 3))
        rig.host.ledger = mapOf(LocalDate.of(2026, 10, 1) to 9_000)
        rig.t.on("POST", "/v1/sync/days", 200, JSONObject().put("days", JSONObject()))
        rig.t.offline("GET", "/v1/sync/snapshot")
        rig.engine.signedIn(AccountInfo("acc-1", "learner@example.org", null, null))
        val expected = TreeMap(days(f.getJSONObject("ownBefore")))
        expected[LocalDate.of(2026, 10, 3)] = 0
        assertEquals(expected, rig.engine.ownDays)
    }

    // ---- First sign-in ----

    @Test
    fun `first sign-in pushes history, then days, then state, then takes the snapshot and stores its seq`() = runBlocking {
        val rig = Rig(signedIn = false)
        val h = rig.host
        h.history = listOf(record(idB, 2_000_000), record(idA, 1_000_000))   // newest first, as Stats keeps it
        h.ledger = mapOf(LocalDate.of(2026, 10, 4) to 60)
        h.state[SyncState.JOURNEY] = JSONObject().put("unlockedThrough", 2).put("currentLevel", 2).put("completed", JSONArray(listOf(1)))
        h.state[SyncState.STORY_BOOKMARKS] = JSONObject().put("fables", 1)
        h.clock = 5_000
        rig.tokens.refresh = "r1"; rig.tokens.access = "a1"

        rig.t.on("POST", "/v1/sync/sessions", 200, accepted(idA, idB).put("stats", stats(2)))
        rig.t.on("POST", "/v1/sync/days", 200, JSONObject().put("days", JSONObject().put("2026-10-04", 60)))
        rig.t.on("PUT", "/v1/sync/state", 200, JSONObject().put("entries", JSONObject()))
        val remote = SyncWire.encodeSession(record(idR, 3_000_000, "Contest"))
        val newerJourney = JSONObject().put("unlockedThrough", 6).put("currentLevel", 6).put("completed", JSONArray(listOf(1, 2, 3, 4, 5)))
        rig.t.on(
            "GET", "/v1/sync/snapshot", 200,
            JSONObject()
                .put("stats", stats(3).put("streak", JSONObject().put("current", 4).put("longest", 9).put("lastPractisedDay", "2026-10-05")))
                .put("sessions", JSONArray().put(remote))
                .put("days", JSONObject().put("2026-10-04", 360).put("2026-10-05", 30))
                .put("state", JSONObject().put(SyncState.JOURNEY, JSONObject().put("value", newerJourney).put("updatedAt", 9_000)))
                .put("seq", 42)
        )

        val outcome = rig.engine.signedIn(AccountInfo("acc-1", "learner@example.org", null, null))

        assertEquals(SyncOutcome.OK, outcome)
        assertEquals(
            listOf("POST /v1/sync/sessions", "POST /v1/sync/days", "PUT /v1/sync/state", "GET /v1/sync/snapshot"),
            rig.t.calls
        )
        val pushed = rig.t.bodyOf("POST /v1/sync/sessions").getJSONArray("sessions")
        assertEquals("oldest first", listOf(idA, idB), (0 until pushed.length()).map { pushed.getJSONObject(it).getString("id") })
        val sentState = rig.t.bodyOf("PUT /v1/sync/state").getJSONObject("entries")
        assertEquals("a never-stamped key goes out at 0", 0L, sentState.getJSONObject(SyncState.JOURNEY).getLong("updatedAt"))
        assertFalse("a never-saved key is not sent", sentState.has(SyncState.CHARACTERS))
        assertTrue(rig.t.paths.last().endsWith("today=2026-10-05"))

        assertEquals(42L, rig.engine.cursor)
        assertFalse(rig.engine.snapshotPending)
        assertEquals(listOf(idR, idB, idA), h.history.map { it.id.toString() })
        assertEquals("Contest", h.history.first().mode)
        assertEquals(3, h.totals.totalSessions)
        assertEquals(mapOf("Contest" to 1240), h.totals.bestScores)
        assertEquals(mapOf(LocalDate.of(2026, 10, 4) to 360, LocalDate.of(2026, 10, 5) to 30), h.ledger)
        assertEquals("own record keeps this device's figure", mapOf(LocalDate.of(2026, 10, 4) to 60), rig.engine.ownDays)
        assertEquals(PracticeStreak(4, 9, LocalDate.of(2026, 10, 5)), h.streak)
        assertEquals("no stats read after a snapshot", 0, rig.t.calls.count { it == "GET /v1/me/stats" })
        assertEquals(6, h.state[SyncState.JOURNEY]!!.getInt("currentLevel"))
        assertEquals("applying is not a local change", 9_000L, rig.engine.stamps[SyncState.JOURNEY]!!.updatedAt)
        assertTrue(rig.engine.outbox.isEmpty)
        assertTrue(rig.engine.pendingDays.isEmpty())
        assertTrue(rig.engine.pendingState.isEmpty())
        assertEquals(5_000L, rig.engine.lastSynced)
    }

    @Test
    fun `a failed drain leaves the snapshot for the next sync`() = runBlocking {
        val rig = Rig(signedIn = false)
        rig.host.history = listOf(record(idA, 1_000_000))
        rig.tokens.refresh = "r1"; rig.tokens.access = "a1"
        rig.t.offline("POST", "/v1/sync/sessions")

        assertEquals(SyncOutcome.FAILED, rig.engine.signedIn(AccountInfo("acc-1", null, null, null)))
        assertTrue(rig.engine.snapshotPending)
        assertEquals(listOf(idA), rig.engine.outbox.ids)

        rig.t.on("POST", "/v1/sync/sessions", 200, accepted(idA))
        rig.t.on("GET", "/v1/sync/snapshot", 200, JSONObject().put("seq", 7))
        assertEquals(SyncOutcome.OK, rig.engine.sync())
        assertEquals(7L, rig.engine.cursor)
        assertFalse(rig.engine.snapshotPending)
    }

    // ---- Pull ----

    @Test
    fun `a pull merges each page and then advances the cursor`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.kv.put(SyncEngine.K_CURSOR, "10")
        rig.host.history = listOf(record(idA, 1_000_000))
        rig.t.on("GET", "/v1/sync/sessions", 200,
            JSONObject().put("sessions", JSONArray().put(SyncWire.encodeSession(record(idB, 2_000_000)))).put("nextSince", 11).put("hasMore", true))
        rig.t.on("GET", "/v1/sync/sessions", 200,
            JSONObject().put("sessions", JSONArray().put(SyncWire.encodeSession(record(idR, 3_000_000)))).put("nextSince", 12).put("hasMore", false)
                .put("stats", stats(3)))
        rig.t.on("GET", "/v1/me/stats", 200, JSONObject())

        assertEquals(SyncOutcome.OK, rig.engine.sync())

        assertEquals(
            listOf("/v1/sync/sessions?since=10&limit=200", "/v1/sync/sessions?since=11&limit=200", "/v1/me/stats?today=2026-10-05"),
            rig.t.paths
        )
        assertEquals(12L, rig.engine.cursor)
        assertEquals(listOf(idR, idB, idA), rig.host.history.map { it.id.toString() })
        assertEquals(3, rig.host.totals.totalSessions)
    }

    @Test
    fun `a pull whose rows could not be saved leaves the cursor where it was`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.kv.put(SyncEngine.K_CURSOR, "10")
        rig.host.failSave = true
        rig.t.on("GET", "/v1/sync/sessions", 200,
            JSONObject().put("sessions", JSONArray().put(SyncWire.encodeSession(record(idB, 2_000_000)))).put("nextSince", 11).put("hasMore", false))

        assertEquals(SyncOutcome.FAILED, rig.engine.sync())
        assertEquals(10L, rig.engine.cursor)
        assertNull(rig.engine.lastSynced)
    }

    // ---- Queues ----

    @Test
    fun `signed out, a session grows the own record but queues nothing`() {
        val rig = Rig(signedIn = false)
        rig.host.record(record(idA, 1_000_000), LocalDate.of(2026, 10, 5), 45)
        rig.engine.practiceDay(LocalDate.of(2026, 10, 6))
        rig.engine.stateChanged(SyncState.JOURNEY)

        assertEquals(mapOf(LocalDate.of(2026, 10, 5) to 45, LocalDate.of(2026, 10, 6) to 0), rig.engine.ownDays)
        assertTrue(rig.engine.outbox.isEmpty)
        assertTrue(rig.engine.pendingDays.isEmpty())
        assertTrue(rig.engine.pendingState.isEmpty())
    }

    @Test
    fun `a state key is stamped only when its value changes`() {
        val rig = Rig(signedIn = true)
        rig.host.state[SyncState.CHARACTERS] = JSONObject().put("stage", "singles")
        rig.host.clock = 100
        rig.engine.stateChanged(SyncState.CHARACTERS)
        rig.host.clock = 200
        rig.engine.stateChanged(SyncState.CHARACTERS)   // saved again, same ladder
        assertEquals(100L, rig.engine.stamps[SyncState.CHARACTERS]!!.updatedAt)

        rig.host.state[SyncState.CHARACTERS] = JSONObject().put("stage", "pairs")
        rig.engine.stateChanged(SyncState.CHARACTERS)
        assertEquals(200L, rig.engine.stamps[SyncState.CHARACTERS]!!.updatedAt)
        assertEquals(setOf(SyncState.CHARACTERS), rig.engine.pendingState)
    }

    @Test
    fun `a batch the server refuses with a 400 is dropped, not retried`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.host.record(record(idA, 1_000_000), LocalDate.of(2026, 10, 5), 0)
        rig.t.on("POST", "/v1/sync/sessions", 400, JSONObject().put("error", "invalid_request"))
        rig.t.on("POST", "/v1/sync/days", 200, JSONObject())
        rig.emptyPull()

        assertEquals(SyncOutcome.OK, rig.engine.sync())
        assertTrue(rig.engine.outbox.isEmpty)
        assertEquals(1, rig.t.calls.count { it == "POST /v1/sync/sessions" })
    }

    // ---- Sign-out ----

    @Test
    fun `sign-out keeps local data and the own record but clears the queues and cursor`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.kv.put(SyncEngine.K_CURSOR, "12")
        rig.kv.put(SyncEngine.K_LAST_SYNCED, "99")
        rig.host.record(record(idA, 1_000_000), LocalDate.of(2026, 10, 5), 45)
        rig.t.on("POST", "/v1/auth/logout", 204)

        rig.engine.signOut()

        assertFalse(rig.engine.isSignedIn)
        assertNull(rig.engine.account)
        assertNull(rig.tokens.refresh)
        assertEquals(0L, rig.engine.cursor)
        assertNull(rig.engine.lastSynced)
        assertTrue(rig.engine.outbox.isEmpty)
        assertTrue(rig.engine.pendingDays.isEmpty())
        assertFalse(rig.engine.signedOutBanner)
        assertEquals(listOf(idA), rig.host.history.map { it.id.toString() })
        assertEquals(mapOf(LocalDate.of(2026, 10, 5) to 45), rig.host.ledger)
        assertEquals(mapOf(LocalDate.of(2026, 10, 5) to 45), rig.engine.ownDays)
        assertTrue(rig.engine.ownDaysSeeded)
    }

    @Test
    fun `a refused refresh signs out and raises the banner`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.kv.put(SyncEngine.K_CURSOR, "12")
        rig.engine.practiceDay(LocalDate.of(2026, 10, 5))
        rig.t.on("POST", "/v1/sync/days", 401)
        rig.t.on("POST", "/v1/auth/token/refresh", 401)

        assertEquals(SyncOutcome.SIGNED_OUT, rig.engine.sync())

        assertTrue(rig.engine.signedOutBanner)
        assertNull(rig.engine.account)
        assertEquals(0L, rig.engine.cursor)
        assertTrue(rig.engine.pendingDays.isEmpty())
        assertEquals(mapOf(LocalDate.of(2026, 10, 5) to 0), rig.engine.ownDays)

        rig.engine.dismissBanner()
        assertFalse(rig.engine.signedOutBanner)
    }

    @Test
    fun `delete account clears the account state on 204 and keeps local data`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.host.record(record(idA, 1_000_000), LocalDate.of(2026, 10, 5), 45)
        rig.t.on("DELETE", "/v1/me", 204)

        val result = rig.engine.deleteAccount()

        assertEquals(204, (result as AccountResult.Reply).code)
        assertNull(rig.engine.account)
        assertNull(rig.tokens.refresh)
        assertTrue(rig.engine.outbox.isEmpty)
        assertEquals(1, rig.host.history.size)
        assertFalse(rig.engine.signedOutBanner)
    }

    // ---- Backoff ----

    @Test
    fun `failures count up and a success resets them`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.t.offline("GET", "/v1/sync/sessions")
        rig.t.on("GET", "/v1/sync/sessions", 503)
        assertEquals(SyncOutcome.FAILED, rig.engine.sync())
        assertEquals(SyncOutcome.FAILED, rig.engine.sync())
        assertEquals(2, rig.engine.failures)

        rig.emptyPull()
        assertEquals(SyncOutcome.OK, rig.engine.sync())
        assertEquals(0, rig.engine.failures)
        assertEquals(1_000L, rig.engine.lastSynced)
    }

    // ---- Profile ----

    @Test
    fun `a profile update stores the server's values and shows its refusal`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.t.on("PATCH", "/v1/me", 200, JSONObject().put("id", "acc-1").put("callsign", "W1AW").put("displayName", "Hiram"))
        assertNull(rig.engine.updateProfile("W1AW", "Hiram"))
        assertEquals(AccountInfo("acc-1", "learner@example.org", "W1AW", "Hiram"), rig.engine.account)

        rig.t.on("PATCH", "/v1/me", 400, JSONObject().put("error", "invalid_request").put("message", "callsign must be 3-16 of A-Z 0-9 /"))
        assertEquals("callsign must be 3-16 of A-Z 0-9 /", rig.engine.updateProfile("W", null))
    }

    @Test
    fun `profile fields follow the server's rules`() {
        assertEquals("W1AW/P", AccountRules.normalizeCallsign(" w1aw/p "))
        assertNull(AccountRules.normalizeCallsign("W1"))
        assertNull(AccountRules.normalizeCallsign("W1-AW"))
        assertNull(AccountRules.normalizeCallsign("A".repeat(17)))
        assertEquals("Hiram", AccountRules.normalizeDisplayName("  Hiram "))
        assertNull(AccountRules.normalizeDisplayName("H"))
        assertNull(AccountRules.normalizeDisplayName("x".repeat(25)))
        assertNull(AccountRules.normalizeDisplayName("Hi\u0007ram"))
        assertTrue(AccountRules.isValidEmail(" learner@example.org "))
        assertFalse(AccountRules.isValidEmail("learner@example"))
    }

    // ---- Stats after a pull ----

    @Test
    fun `after a pull the stats read adopts totals, the displayed ledger and the streak`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.kv.put(SyncEngine.K_OWN_DAYS, JSONObject().put("2026-10-04", 60).toString())
        rig.host.ledger = mapOf(LocalDate.of(2026, 10, 3) to 30, LocalDate.of(2026, 10, 4) to 60)
        rig.host.streak = PracticeStreak(1, 20, LocalDate.of(2026, 10, 4))
        rig.t.on("GET", "/v1/sync/sessions", 200, JSONObject().put("sessions", JSONArray()).put("nextSince", 0).put("hasMore", false))
        rig.t.on(
            "GET", "/v1/me/stats", 200,
            stats(7)
                .put("streak", JSONObject().put("current", 3).put("longest", 5).put("lastPractisedDay", "2026-10-05"))
                .put("activity", JSONObject().put("days", JSONObject().put("2026-10-04", 400).put("2026-10-05", 90)))
        )

        assertEquals(SyncOutcome.OK, rig.engine.sync())

        assertTrue(rig.t.paths.last().endsWith("/v1/me/stats?today=2026-10-05"))
        assertEquals(7, rig.host.totals.totalSessions)
        assertEquals(
            mapOf(LocalDate.of(2026, 10, 3) to 30, LocalDate.of(2026, 10, 4) to 400, LocalDate.of(2026, 10, 5) to 90),
            rig.host.ledger
        )
        assertEquals("own record untouched", mapOf(LocalDate.of(2026, 10, 4) to 60), rig.engine.ownDays)
        assertEquals("server run adopted, local longest kept", PracticeStreak(3, 20, LocalDate.of(2026, 10, 5)), rig.host.streak)
    }

    @Test
    fun `a stats read the grant does not cover is not a failure`() = runBlocking {
        val rig = Rig(signedIn = true)
        rig.t.on("GET", "/v1/sync/sessions", 200, JSONObject().put("sessions", JSONArray()).put("nextSince", 0).put("hasMore", false))
        rig.t.on("GET", "/v1/me/stats", 403, JSONObject().put("error", "insufficient_scope"))
        assertEquals(SyncOutcome.OK, rig.engine.sync())

        rig.t.on("GET", "/v1/sync/sessions", 200, JSONObject().put("sessions", JSONArray()).put("nextSince", 0).put("hasMore", false))
        rig.t.offline("GET", "/v1/me/stats")
        assertEquals(SyncOutcome.FAILED, rig.engine.sync())
    }

    @Test
    fun `the streak merge never lowers the local longest and keeps an unpushed local run`() {
        fun server(current: Int, longest: Int, last: String?) =
            JSONObject().put("streak", JSONObject().put("current", current).put("longest", longest).put("lastPractisedDay", last ?: JSONObject.NULL))
        val d4 = LocalDate.of(2026, 10, 4)
        val d5 = LocalDate.of(2026, 10, 5)
        // Fresh install: the account's streak.
        assertEquals(PracticeStreak(12, 40, d5), SyncMerge.adoptStreak(PracticeStreak(), server(12, 40, "2026-10-05")))
        // Longer local longest survives.
        assertEquals(PracticeStreak(2, 50, d5), SyncMerge.adoptStreak(PracticeStreak(1, 50, d4), server(2, 7, "2026-10-05")))
        // Local practice newer than the server knows: the local run stays, the longest still merges.
        assertEquals(PracticeStreak(3, 9, d5), SyncMerge.adoptStreak(PracticeStreak(3, 4, d5), server(2, 9, "2026-10-04")))
        // No server streak, or no last day: local as it was.
        val local = PracticeStreak(3, 4, d5)
        assertEquals(local, SyncMerge.adoptStreak(local, JSONObject()))
        assertEquals(PracticeStreak(3, 4, d5), SyncMerge.adoptStreak(local, server(0, 0, null)))
    }

    // ---- Refused pushes and changes in flight ----

    @Test
    fun `a 4xx drops the queued days and keys, but one changed in flight stays queued`() = runBlocking {
        val rig = Rig(signedIn = true)
        val d4 = LocalDate.of(2026, 10, 4)
        val d5 = LocalDate.of(2026, 10, 5)
        rig.engine.practiceDay(d4)
        rig.engine.practiceDay(d5)
        rig.host.state[SyncState.JOURNEY] = JSONObject().put("currentLevel", 1)
        rig.host.state[SyncState.FIRST_FOUR] = JSONObject().put("passed", JSONArray())
        rig.engine.stateChanged(SyncState.JOURNEY)
        rig.engine.stateChanged(SyncState.FIRST_FOUR)
        // While the days request is out, a session lands on the 5th.
        rig.t.onWhile("POST", "/v1/sync/days", 400, JSONObject()) {
            rig.host.record(record(idA, 1_000_000), d5, 30)
        }
        // While the state request is out, the Journey moves on.
        rig.t.onWhile("PUT", "/v1/sync/state", 422, JSONObject()) {
            rig.host.clock = 2_000
            rig.host.state[SyncState.JOURNEY] = JSONObject().put("currentLevel", 2)
            rig.engine.stateChanged(SyncState.JOURNEY)
        }
        rig.emptyPull()

        assertEquals(SyncOutcome.OK, rig.engine.sync())
        assertEquals("changed in flight", setOf(d5), rig.engine.pendingDays)
        assertEquals("re-stamped in flight", setOf(SyncState.JOURNEY), rig.engine.pendingState)
    }

    @Test
    fun `at first sign-in an unstamped key goes out at 0 and a stamped one keeps its stamp`() = runBlocking {
        val rig = Rig(signedIn = false)
        val h = rig.host
        h.clock = 7_000
        h.state[SyncState.JOURNEY] = JSONObject().put("currentLevel", 3)
        h.state[SyncState.FIRST_FOUR] = JSONObject().put("passed", JSONArray(listOf("call")))
        // Stamped during an earlier sign-in on this device.
        rig.kv.put(
            SyncEngine.K_STAMPS,
            JSONObject().put(SyncState.FIRST_FOUR, JSONObject().put("updatedAt", 4_000).put("sig", "old")).toString()
        )
        rig.tokens.refresh = "r1"; rig.tokens.access = "a1"
        rig.t.on("PUT", "/v1/sync/state", 200, JSONObject().put("entries", JSONObject()))
        rig.t.offline("GET", "/v1/sync/snapshot")

        rig.engine.signedIn(AccountInfo("acc-1", null, null, null))

        val sent = rig.t.bodyOf("PUT /v1/sync/state").getJSONObject("entries")
        assertEquals(0L, sent.getJSONObject(SyncState.JOURNEY).getLong("updatedAt"))
        assertEquals(4_000L, sent.getJSONObject(SyncState.FIRST_FOUR).getLong("updatedAt"))
        assertEquals(setOf(SyncState.JOURNEY, SyncState.FIRST_FOUR), sent.keys().asSequence().toSet())
        assertEquals(0L, rig.engine.stamps[SyncState.JOURNEY]!!.updatedAt)
    }

    @Test
    fun `the account's value wins over an unstamped key at first sign-in`() = runBlocking {
        val rig = Rig(signedIn = false)
        val h = rig.host
        h.state[SyncState.JOURNEY] = JSONObject().put("currentLevel", 1)   // onboarding default
        rig.tokens.refresh = "r1"; rig.tokens.access = "a1"
        val accountJourney = JSONObject().put("currentLevel", 8)
        rig.t.on("PUT", "/v1/sync/state", 200,
            JSONObject().put("entries", JSONObject().put(SyncState.JOURNEY, JSONObject().put("value", accountJourney).put("updatedAt", 1_500))))
        rig.t.on("GET", "/v1/sync/snapshot", 200, JSONObject().put("seq", 1))

        assertEquals(SyncOutcome.OK, rig.engine.signedIn(AccountInfo("acc-1", null, null, null)))
        assertEquals(8, h.state[SyncState.JOURNEY]!!.getInt("currentLevel"))
        assertEquals(1_500L, rig.engine.stamps[SyncState.JOURNEY]!!.updatedAt)
    }

    @Test
    fun `a sync with no local changes still puts every saved key and applies a newer winner`() = runBlocking {
        val rig = Rig(signedIn = true)
        val h = rig.host
        h.state[SyncState.JOURNEY] = JSONObject().put("currentLevel", 3)
        h.state[SyncState.STORY_BOOKMARKS] = JSONObject().put("fables", 2)
        // Journey stamped at the last sync; the bookmarks were never stamped; Characters never saved.
        rig.kv.put(
            SyncEngine.K_STAMPS,
            JSONObject().put(SyncState.JOURNEY, JSONObject().put("updatedAt", 4_000).put("sig", h.state[SyncState.JOURNEY].toString())).toString()
        )
        assertTrue("nothing queued", rig.engine.pendingState.isEmpty())
        val otherDevice = JSONObject().put("currentLevel", 7)
        rig.t.on("PUT", "/v1/sync/state", 200,
            JSONObject().put("entries", JSONObject()
                .put(SyncState.JOURNEY, JSONObject().put("value", otherDevice).put("updatedAt", 9_000))
                .put(SyncState.STORY_BOOKMARKS, JSONObject().put("value", h.state[SyncState.STORY_BOOKMARKS]).put("updatedAt", 0))))
        rig.emptyPull()

        assertEquals(SyncOutcome.OK, rig.engine.sync())

        assertEquals(1, rig.t.calls.count { it == "PUT /v1/sync/state" })
        val sent = rig.t.bodyOf("PUT /v1/sync/state").getJSONObject("entries")
        assertEquals(setOf(SyncState.JOURNEY, SyncState.STORY_BOOKMARKS), sent.keys().asSequence().toSet())
        assertEquals(4_000L, sent.getJSONObject(SyncState.JOURNEY).getLong("updatedAt"))
        assertEquals(0L, sent.getJSONObject(SyncState.STORY_BOOKMARKS).getLong("updatedAt"))
        assertEquals("the other device's newer Journey applied", 7, h.state[SyncState.JOURNEY]!!.getInt("currentLevel"))
        assertEquals(9_000L, rig.engine.stamps[SyncState.JOURNEY]!!.updatedAt)
        assertTrue(rig.engine.pendingState.isEmpty())
    }
}
