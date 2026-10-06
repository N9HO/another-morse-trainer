package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.SessionRecord
import app.anothermorsetrainer.morsekit.SyncAccountState
import app.anothermorsetrainer.morsekit.SyncMerge
import app.anothermorsetrainer.morsekit.SyncStateCodec
import app.anothermorsetrainer.morsekit.SyncStreak
import app.anothermorsetrainer.morsekit.SyncWire
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [SyncEngine] against a fake transport and an in-memory app: the order of a
 * first sign-in, the pull and its cursor, sign-out and a forced sign-out,
 * the own-day record, state adoption and the backoff. No network.
 */
class SyncEngineTest {

    // ---- Fakes ----

    /** Routes by "METHOD path-without-query" to a reply; records every request in order. */
    private class FakeTransport : AccountTransport {
        val sent = ArrayList<AccountRequest>()
        val routes = HashMap<String, (AccountRequest) -> AccountReply>()
        override suspend fun send(request: AccountRequest): AccountReply {
            sent.add(request)
            val key = request.method + " " + request.path.substringBefore('?')
            return routes[key]?.invoke(request) ?: AccountReply(404, """{"error":"not_found","message":"no route"}""")
        }
        fun calls(): List<String> = sent.map { it.method + " " + it.path.substringBefore('?') }
    }

    private class MemoryTokens(var tokens: AccountTokens?) : AccountTokenStore {
        override fun load() = tokens
        override fun save(tokens: AccountTokens) { this.tokens = tokens }
        override fun clear() { tokens = null }
    }

    private class MemoryStore(var saved: SyncAccountState = SyncAccountState()) : SyncStore {
        override fun load() = saved
        override fun save(state: SyncAccountState) { saved = state }
    }

    private class FakeLocal : SyncLocal {
        var history = ArrayList<SessionRecord>()
        var ledger = sortedMapOf<LocalDate, Int>()
        val values = LinkedHashMap<String, JSONObject>()
        val applied = LinkedHashMap<String, JSONObject>()
        var aggregates: SyncMerge.Aggregates? = null
        var streak: SyncStreak.Server? = null
        var failSaves = false
        val events = ArrayList<String>()

        override fun historyRecords() = history.toList()
        override fun ledgerDays(): Map<LocalDate, Int> = ledger
        override fun mergeSessions(pulled: List<SessionRecord>): Boolean {
            if (failSaves) return false
            history = ArrayList(SyncMerge.mergeSessions(history, pulled))
            events.add("merged ${pulled.size}")
            return true
        }
        override fun adoptAggregates(aggregates: SyncMerge.Aggregates) { this.aggregates = aggregates }
        override fun adoptDays(server: Map<LocalDate, Int>) {
            ledger = sortedMapOf<LocalDate, Int>().apply { putAll(SyncMerge.mergeLedger(ledger, server).days) }
        }
        override fun adoptStreak(server: SyncStreak.Server) { streak = server; events.add("streak") }
        override fun stateValue(key: String): JSONObject? = values[key]
        override fun applyState(key: String, value: JSONObject) {
            applied[key] = value
            values[key] = value
        }
    }

    private class Rig(state: SyncAccountState = SyncAccountState(), tokens: AccountTokens? = AccountTokens("A1", "R1")) {
        val transport = FakeTransport()
        val tokenStore = MemoryTokens(tokens)
        val store = MemoryStore(state)
        val local = FakeLocal()
        var clock = 1_791_240_000_000L
        val engine = SyncEngine(AccountClient(transport, tokenStore), store, local, { clock }, { LocalDate.of(2026, 10, 6) })
    }

    private fun ok(body: JSONObject) = AccountReply(200, body.toString())

    private fun record(id: Int, date: Long, seconds: Double? = 60.0) = SessionRecord(
        id = UUID.fromString("00000000-0000-4000-8000-%012d".format(id)), date = Instant.ofEpochMilli(date),
        mode = "Rapid Fire", characterWPM = 20, effectiveWPM = 15, attempts = 10, correct = 9,
        fastestTTR = 0.3, medianTTR = 0.5, durationSeconds = seconds,
        characters = emptyList(), activeCharacters = emptyList(), score = 9
    )

    private val stats = JSONObject()
        .put("totals", JSONObject().put("sessions", 412).put("answered", 9180).put("correct", 8433).put("practiceSeconds", 61234.5))
        .put("bestTtrMs", 182)
        .put("personalBests", JSONObject().put("rapidFire", 44))

    /** A push reply accepting every id sent. */
    private fun acceptAll(r: AccountRequest): AccountReply {
        val sent = JSONObject(r.body!!).getJSONArray("sessions")
        val ids = JSONArray()
        for (i in 0 until sent.length()) ids.put(sent.getJSONObject(i).getString("id"))
        return ok(JSONObject().put("accepted", ids).put("skipped", JSONArray()).put("rejected", JSONArray()).put("stats", stats))
    }

    /** A days reply that echoes the figures sent. */
    private fun echoDays(r: AccountRequest): AccountReply {
        val sent = JSONObject(r.body!!).getJSONArray("days")
        val out = JSONObject()
        for (i in 0 until sent.length()) out.put(sent.getJSONObject(i).getString("day"), sent.getJSONObject(i).getInt("seconds"))
        return ok(JSONObject().put("days", out))
    }

    private fun echoState(r: AccountRequest): AccountReply =
        ok(JSONObject().put("entries", JSONObject(r.body!!).getJSONObject("entries")))

    private val account = AccountInfo("acct", "learner@example.org", null, null)

    // ---- First sign-in ----

    @Test
    fun `first sign-in pushes history, then days, then state, then takes the snapshot and stores seq`() = runBlocking<Unit> {
        val rig = Rig()
        rig.local.history.addAll(listOf(record(2, 2_000), record(1, 1_000)))
        rig.local.ledger[LocalDate.of(2026, 10, 1)] = 300
        rig.local.ledger[LocalDate.of(2026, 10, 2)] = 60
        rig.local.values[SyncStateCodec.JOURNEY] = JSONObject().put("unlockedThrough", 3).put("currentLevel", 2).put("completed", JSONArray().put(1).put(2))
        val pulledRow = SyncWire.encodeSession(record(9, 5_000))
        rig.transport.routes["POST /v1/sync/sessions"] = ::acceptAll
        rig.transport.routes["POST /v1/sync/days"] = ::echoDays
        rig.transport.routes["PUT /v1/sync/state"] = ::echoState
        rig.transport.routes["GET /v1/sync/snapshot"] = {
            ok(
                JSONObject().put("stats", stats).put("sessions", JSONArray().put(pulledRow))
                    .put("days", JSONObject().put("2026-10-02", 260).put("2026-10-04", 900))
                    .put("state", JSONObject()).put("seq", 412)
                    .also { it.getJSONObject("stats").put("streak", JSONObject().put("current", 5).put("longest", 9).put("lastPractisedDay", "2026-10-04")) }
            )
        }

        rig.engine.signedIn(account)
        val s0 = rig.engine.state.value
        assertTrue(s0.isSignedIn && s0.needsSnapshot && s0.ownDaysSeeded)
        assertEquals(rig.local.ledger, s0.ownDays)
        assertEquals("journey queued; keys with nothing saved are not sent", setOf(SyncStateCodec.JOURNEY), s0.stateOutbox)
        assertEquals("never stamped goes out at 0", 0L, s0.stateStamps[SyncStateCodec.JOURNEY])

        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        assertEquals(
            listOf("POST /v1/sync/sessions", "POST /v1/sync/days", "PUT /v1/sync/state", "GET /v1/sync/snapshot"),
            rig.transport.calls()
        )
        val pushed = JSONObject(rig.transport.sent[0].body!!).getJSONArray("sessions")
        assertEquals("oldest first", record(1, 0).id.toString(), pushed.getJSONObject(0).getString("id"))
        assertEquals("today goes as the local day", "/v1/sync/snapshot?today=2026-10-06", rig.transport.sent[3].path)
        val s = rig.engine.state.value
        assertEquals(412L, s.cursor)
        assertFalse(s.needsSnapshot)
        assertTrue(s.sessionOutbox.isEmpty && s.dayOutbox.isEmpty() && s.stateOutbox.isEmpty())
        assertEquals(rig.clock, s.lastSyncedAt)
        assertEquals(3, rig.local.history.size)
        assertEquals(412, rig.local.aggregates!!.totalSessions)
        assertEquals("the snapshot's streak is adopted", SyncStreak.Server(5, 9, LocalDate.of(2026, 10, 4)), rig.local.streak)
        assertEquals(mapOf("Rapid Fire" to 44), rig.local.aggregates!!.bestScores)
        assertEquals(
            mapOf(LocalDate.of(2026, 10, 1) to 300, LocalDate.of(2026, 10, 2) to 260, LocalDate.of(2026, 10, 4) to 900),
            rig.local.ledger.toMap()
        )
        assertEquals("the own record is untouched by adoption", mapOf(LocalDate.of(2026, 10, 1) to 300, LocalDate.of(2026, 10, 2) to 60), s.ownDays)
    }

    @Test
    fun `at first sign-in an unstamped key goes at 0 and a stamped one keeps its stamp`() = runBlocking<Unit> {
        org.junit.Assert.fail("negative control")
        val rig = Rig(SyncAccountState(stateStamps = mapOf(SyncStateCodec.FIRST_FOUR to 1_791_000_000_000L)))
        rig.local.values[SyncStateCodec.JOURNEY] = JSONObject().put("unlockedThrough", 1).put("currentLevel", 1).put("completed", JSONArray())
        rig.local.values[SyncStateCodec.FIRST_FOUR] = JSONObject().put("passed", JSONArray()).put("copyPassed", JSONArray()).put("cleanRuns", JSONObject())
        val accountJourney = JSONObject().put("unlockedThrough", 9).put("currentLevel", 9).put("completed", JSONArray().put(1))
        rig.transport.routes["PUT /v1/sync/state"] = { r ->
            val sent = JSONObject(r.body!!).getJSONObject("entries")
            assertEquals(0L, sent.getJSONObject("journey").getLong("updatedAt"))
            assertEquals(1_791_000_000_000L, sent.getJSONObject("firstFour").getLong("updatedAt"))
            // The account's real journey (stamped) wins over the device's unstamped one.
            ok(JSONObject().put("entries", JSONObject(sent.toString()).put("journey", JSONObject().put("value", accountJourney).put("updatedAt", 1_790_000_000_000L))))
        }
        rig.transport.routes["GET /v1/sync/snapshot"] = { ok(JSONObject().put("state", JSONObject()).put("seq", 1)) }
        rig.engine.signedIn(account)
        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        assertTrue(accountJourney.similar(rig.local.applied["journey"]))
        assertEquals(setOf("journey"), rig.local.applied.keys)
        assertEquals(1_790_000_000_000L, rig.engine.state.value.stateStamps["journey"])
    }

    @Test
    fun `the snapshot waits until the outbox has drained`() = runBlocking<Unit> {
        val rig = Rig()
        rig.local.history.add(record(1, 1_000))
        rig.transport.routes["POST /v1/sync/sessions"] = { AccountReply(503, null) }
        rig.engine.signedIn(account)
        assertEquals(SyncEngine.Outcome.BACKOFF, rig.engine.sync())
        assertEquals(listOf("POST /v1/sync/sessions"), rig.transport.calls())
        assertTrue(rig.engine.state.value.needsSnapshot)
        assertEquals(1, rig.engine.state.value.sessionOutbox.ids.size)
    }

    // ---- Pull ----

    private fun page(rows: List<SessionRecord>, next: Long, more: Boolean) = ok(
        JSONObject().put("sessions", JSONArray().apply { rows.forEach { put(SyncWire.encodeSession(it)) } })
            .put("nextSince", next).put("hasMore", more)
    )

    @Test
    fun `a pull merges each page and then advances the cursor`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct", cursor = 5, ownDaysSeeded = true))
        rig.transport.routes["GET /v1/sync/sessions"] = { r ->
            when {
                r.path.contains("since=5&") -> page(listOf(record(1, 1_000), record(2, 2_000)), 7, true)
                r.path.contains("since=7&") -> page(listOf(record(3, 3_000)), 9, false)
                else -> AccountReply(500, null)
            }
        }
        rig.transport.routes["GET /v1/me/stats"] = {
            ok(
                JSONObject(stats.toString())
                    .put("activity", JSONObject().put("days", JSONObject().put("2026-10-05", 1900)))
                    .put("streak", JSONObject().put("current", 12).put("longest", 40).put("lastPractisedDay", "2026-10-05"))
            )
        }
        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        assertEquals(listOf("GET /v1/sync/sessions", "GET /v1/sync/sessions", "GET /v1/me/stats"), rig.transport.calls())
        assertEquals("rows merged, then the streak adopted after the days", listOf("merged 2", "merged 1", "streak"), rig.local.events)
        assertEquals(9L, rig.engine.state.value.cursor)
        assertEquals("/v1/me/stats?today=2026-10-06", rig.transport.sent[2].path)
        assertEquals(412, rig.local.aggregates!!.totalSessions)
        assertEquals(1900, rig.local.ledger[LocalDate.of(2026, 10, 5)])
        assertEquals(SyncStreak.Server(12, 40, LocalDate.of(2026, 10, 5)), rig.local.streak)
    }

    @Test
    fun `a page that cannot be saved leaves the cursor where it was`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct", cursor = 5))
        rig.local.failSaves = true
        rig.transport.routes["GET /v1/sync/sessions"] = { page(listOf(record(1, 1_000)), 7, false) }
        assertEquals(SyncEngine.Outcome.BACKOFF, rig.engine.sync())
        assertEquals(5L, rig.engine.state.value.cursor)
        assertNull(rig.engine.state.value.lastSyncedAt)
    }

    @Test
    fun `an empty pull still fetches the stats, days and streak`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct", cursor = 5))
        rig.transport.routes["GET /v1/sync/sessions"] = { page(emptyList(), 5, false) }
        rig.transport.routes["GET /v1/me/stats"] = {
            ok(JSONObject(stats.toString()).put("activity", JSONObject().put("days", JSONObject().put("2026-10-04", 600)))
                .put("streak", JSONObject().put("current", 3).put("longest", 3).put("lastPractisedDay", "2026-10-04")))
        }
        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        assertEquals(listOf("GET /v1/sync/sessions", "GET /v1/me/stats"), rig.transport.calls())
        assertEquals("another device's day arrives on a plain pull", 600, rig.local.ledger[LocalDate.of(2026, 10, 4)])
        assertEquals(3, rig.local.streak!!.current)
    }

    @Test
    fun `a failed stats fetch after a pull backs off`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct", cursor = 5))
        rig.transport.routes["GET /v1/sync/sessions"] = { page(emptyList(), 5, false) }
        rig.transport.routes["GET /v1/me/stats"] = { AccountReply(503, null) }
        assertEquals(SyncEngine.Outcome.BACKOFF, rig.engine.sync())
    }

    // ---- Hooks, own days, state ----

    @Test
    fun `signed out, a session grows the own record and queues nothing`() {
        val rig = Rig(SyncAccountState(), tokens = null)
        rig.engine.sessionRecorded(record(1, 1_000), LocalDate.of(2026, 10, 2), 45)
        rig.engine.practiceDayMarked(LocalDate.of(2026, 10, 3))
        rig.engine.stateChanged(SyncStateCodec.JOURNEY)
        val s = rig.engine.state.value
        assertEquals(mapOf(LocalDate.of(2026, 10, 2) to 45, LocalDate.of(2026, 10, 3) to 0), s.ownDays)
        assertTrue(s.sessionOutbox.isEmpty && s.dayOutbox.isEmpty() && s.stateOutbox.isEmpty() && s.stateStamps.isEmpty())
    }

    @Test
    fun `days push the own figures and adopt the summed reply into the displayed ledger only`() = runBlocking<Unit> {
        val day = LocalDate.of(2026, 10, 2)
        val rig = Rig(SyncAccountState(accountId = "acct", ownDays = mapOf(day to 60), ownDaysSeeded = true))
        rig.local.ledger[day] = 60
        rig.transport.routes["POST /v1/sync/sessions"] = ::acceptAll
        rig.transport.routes["POST /v1/sync/days"] = { ok(JSONObject().put("days", JSONObject().put("2026-10-02", 405))) }
        rig.transport.routes["GET /v1/sync/sessions"] = { page(emptyList(), 0, false) }
        rig.engine.sessionRecorded(record(1, 1_000), day, 45)
        rig.local.ledger[day] = 105   // Stats records the same seconds on the same day
        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        val daysBody = JSONObject(rig.transport.sent.first { it.path == "/v1/sync/days" }.body!!)
        assertEquals(105, daysBody.getJSONArray("days").getJSONObject(0).getInt("seconds"))
        assertEquals(405, rig.local.ledger[day])
        assertEquals(mapOf(day to 105), rig.engine.state.value.ownDays)
        assertTrue(rig.engine.state.value.dayOutbox.isEmpty())
    }

    @Test
    fun `a state winner is applied only when strictly newer`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct", stateStamps = mapOf("journey" to 100L, "firstFour" to 100L)))
        val mine = JSONObject().put("passed", JSONArray()).put("copyPassed", JSONArray()).put("cleanRuns", JSONObject())
        rig.local.values["journey"] = JSONObject().put("unlockedThrough", 1).put("currentLevel", 1).put("completed", JSONArray())
        rig.local.values["firstFour"] = mine
        rig.clock = 200L
        rig.engine.stateChanged("journey")
        rig.engine.stateChanged("firstFour")
        val theirsJourney = JSONObject().put("unlockedThrough", 9).put("currentLevel", 9).put("completed", JSONArray().put(1))
        rig.transport.routes["PUT /v1/sync/state"] = {
            ok(
                JSONObject().put(
                    "entries", JSONObject()
                        .put("journey", JSONObject().put("value", theirsJourney).put("updatedAt", 300L))
                        .put("firstFour", JSONObject().put("value", JSONObject().put("passed", JSONArray().put("x"))).put("updatedAt", 200L))
                )
            )
        }
        rig.transport.routes["GET /v1/sync/sessions"] = { page(emptyList(), 0, false) }
        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        val sent = JSONObject(rig.transport.sent.first { it.method == "PUT" }.body!!).getJSONObject("entries")
        assertEquals(200L, sent.getJSONObject("journey").getLong("updatedAt"))
        assertEquals(setOf("journey"), rig.local.applied.keys)
        assertTrue(theirsJourney.similar(rig.local.applied["journey"]))
        assertEquals(300L, rig.engine.state.value.stateStamps["journey"])
        assertEquals("a tie keeps local", 200L, rig.engine.state.value.stateStamps["firstFour"])
        assertTrue(rig.engine.state.value.stateOutbox.isEmpty())
    }

    @Test
    fun `a sync with no local changes still puts the saved keys and applies a newer server value`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct", cursor = 3, stateStamps = mapOf("journey" to 100L)))
        rig.local.values["journey"] = JSONObject().put("unlockedThrough", 2).put("currentLevel", 2).put("completed", JSONArray().put(1))
        rig.local.values["characters"] = JSONObject().put("activeCharacters", JSONArray().put("K").put("M")).put("exposedCharacters", JSONArray()).put("stage", "singles").put("pinnedStage", JSONObject.NULL)
        val newer = JSONObject().put("unlockedThrough", 7).put("currentLevel", 7).put("completed", JSONArray().put(1).put(2))
        rig.transport.routes["PUT /v1/sync/state"] = { r ->
            val sent = JSONObject(r.body!!).getJSONObject("entries")
            assertEquals(setOf("journey", "characters"), sent.keys().asSequence().toSet())
            assertEquals(100L, sent.getJSONObject("journey").getLong("updatedAt"))
            assertEquals("never stamped goes at 0", 0L, sent.getJSONObject("characters").getLong("updatedAt"))
            ok(JSONObject().put("entries", JSONObject(sent.toString()).put("journey", JSONObject().put("value", newer).put("updatedAt", 500L))))
        }
        rig.transport.routes["GET /v1/sync/sessions"] = { page(emptyList(), 3, false) }
        assertTrue(rig.engine.state.value.stateOutbox.isEmpty())
        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        assertEquals(1, rig.transport.calls().count { it == "PUT /v1/sync/state" })
        assertEquals(listOf("PUT /v1/sync/state", "GET /v1/sync/sessions"), rig.transport.calls().take(2))
        assertTrue(newer.similar(rig.local.applied["journey"]))
        assertEquals(setOf("journey"), rig.local.applied.keys)
        assertEquals(500L, rig.engine.state.value.stateStamps["journey"])
    }

    // ---- Sign-out ----

    @Test
    fun `sign-out keeps local data and the own record but clears outbox and cursor`() = runBlocking<Unit> {
        val day = LocalDate.of(2026, 10, 2)
        val rig = Rig(SyncAccountState(accountId = "acct", email = "e@example.org", cursor = 9, lastSyncedAt = 5, ownDays = mapOf(day to 60), ownDaysSeeded = true))
        rig.local.history.add(record(1, 1_000))
        rig.engine.sessionRecorded(record(2, 2_000), day, 30)
        rig.transport.routes["POST /v1/auth/logout"] = { AccountReply(204, null) }
        rig.engine.signOut()
        val s = rig.engine.state.value
        assertEquals(listOf("POST /v1/auth/logout"), rig.transport.calls())
        assertFalse(s.isSignedIn)
        assertNull(rig.tokenStore.tokens)
        assertEquals(0L, s.cursor)
        assertNull(s.lastSyncedAt)
        assertTrue(s.sessionOutbox.isEmpty && s.dayOutbox.isEmpty())
        assertEquals(mapOf(day to 90), s.ownDays)
        assertTrue(s.ownDaysSeeded)
        assertFalse("a chosen sign-out shows no banner", s.signedOutBanner)
        assertEquals(1, rig.local.history.size)
        assertEquals(s, rig.store.saved)
    }

    @Test
    fun `a refused refresh signs out and raises the banner`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct", cursor = 3, ownDaysSeeded = true))
        rig.transport.routes["GET /v1/sync/sessions"] = { AccountReply(401, """{"error":"unauthorized","message":"expired"}""") }
        rig.transport.routes["POST /v1/auth/token/refresh"] = { AccountReply(401, """{"error":"unauthorized","message":"revoked"}""") }
        assertEquals(SyncEngine.Outcome.SIGNED_OUT, rig.engine.sync())
        val s = rig.engine.state.value
        assertFalse(s.isSignedIn)
        assertTrue(s.signedOutBanner)
        assertEquals(0L, s.cursor)
        assertNull(rig.tokenStore.tokens)
        rig.engine.dismissBanner()
        assertFalse(rig.engine.state.value.signedOutBanner)
    }

    @Test
    fun `delete account signs out on 204 and keeps the own record`() = runBlocking<Unit> {
        val day = LocalDate.of(2026, 10, 2)
        val rig = Rig(SyncAccountState(accountId = "acct", ownDays = mapOf(day to 60), ownDaysSeeded = true))
        rig.transport.routes["DELETE /v1/me"] = { AccountReply(204, null) }
        assertTrue(rig.engine.deleteAccount() is AccountResult.Ok)
        assertFalse(rig.engine.state.value.isSignedIn)
        assertFalse(rig.engine.state.value.signedOutBanner)
        assertEquals(mapOf(day to 60), rig.engine.state.value.ownDays)
        assertNull(rig.tokenStore.tokens)
    }

    // ---- Backoff ----

    @Test
    fun `backoff grows on failure and resets on success`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct"))
        var fail = true
        rig.transport.routes["GET /v1/sync/sessions"] = { if (fail) AccountReply(503, null) else page(emptyList(), 0, false) }
        assertNull(rig.engine.retryDelaySeconds())
        assertEquals(SyncEngine.Outcome.BACKOFF, rig.engine.sync())
        assertEquals(2L, rig.engine.retryDelaySeconds())
        assertEquals(SyncEngine.Outcome.BACKOFF, rig.engine.sync())
        assertEquals(4L, rig.engine.retryDelaySeconds())
        fail = false
        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        assertNull(rig.engine.retryDelaySeconds())
        assertEquals(0, rig.engine.failures)
    }

    @Test
    fun `a 4xx on a push drops the batch instead of retrying it`() = runBlocking<Unit> {
        val rig = Rig(SyncAccountState(accountId = "acct"))
        rig.engine.sessionRecorded(record(1, 1_000), LocalDate.of(2026, 10, 2), 10)
        rig.transport.routes["POST /v1/sync/sessions"] = { AccountReply(400, """{"error":"invalid_request","message":"bad"}""") }
        rig.transport.routes["POST /v1/sync/days"] = ::echoDays
        rig.transport.routes["GET /v1/sync/sessions"] = { page(emptyList(), 0, false) }
        assertEquals(SyncEngine.Outcome.DONE, rig.engine.sync())
        assertTrue(rig.engine.state.value.sessionOutbox.isEmpty)
    }
}
