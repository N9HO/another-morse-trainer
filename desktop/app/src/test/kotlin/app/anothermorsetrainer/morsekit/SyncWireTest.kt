package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * Account sync's wire format and merge rules, held to `fixtures/sync-wire.json`
 * — the same file the Swift harness and the Android tree read, so every port
 * sends the same bytes and folds a reply the same way. Expected values come
 * from the accounts README, not from any port's encoder.
 */
class SyncWireTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("sync-wire.json")
        assertNotNull("fixtures/sync-wire.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun assertSimilar(message: String, expected: Any, actual: Any) {
        val same = when (expected) {
            is JSONObject -> expected.similar(actual)
            is JSONArray -> expected.similar(actual)
            else -> expected == actual
        }
        assertTrue("$message\nexpected: $expected\nactual:   $actual", same)
    }

    // ---- Sessions ----

    /** The fixture's `local` block as this port's record. */
    private fun localRecord(o: JSONObject): SessionRecord {
        fun seconds(key: String, from: JSONObject = o) = if (from.isNull(key)) null else from.getDouble(key)
        val chars = o.getJSONArray("characters")
        return SessionRecord(
            id = UUID.fromString(o.getString("id")),
            date = Instant.ofEpochMilli(o.getLong("dateEpochMs")),
            mode = o.getJSONObject("localMode").getString("kotlin"),
            characterWPM = o.getInt("characterWpm"),
            effectiveWPM = o.getInt("effectiveWpm"),
            attempts = o.getInt("attempts"),
            correct = o.getInt("correct"),
            fastestTTR = seconds("fastestTtrSeconds"),
            medianTTR = seconds("medianTtrSeconds"),
            durationSeconds = seconds("durationSeconds"),
            characters = (0 until chars.length()).map { i ->
                val c = chars.getJSONObject(i)
                SessionRecord.CharResult(c.getString("character"), c.getInt("attempts"), c.getInt("correct"), seconds("medianTtrSeconds", c))
            },
            activeCharacters = strings(o.getJSONArray("activeCharacters")),
            score = if (o.isNull("score")) null else o.getInt("score")
        )
    }

    private fun checkSession(name: String) {
        val block = fixture.getJSONObject(name)
        val local = localRecord(block.getJSONObject("local"))
        val wire = block.getJSONObject("wire")
        assertSimilar("$name encodes to the fixture's wire form", wire, SyncWire.encodeSession(local))
        // The wire keeps whole milliseconds, so decoding gives back the local
        // record with its TTRs at millisecond precision (0.2124 s → 0.212 s).
        fun ms(s: Double?) = SyncWire.toSeconds(SyncWire.toMs(s))
        val expected = local.copy(
            fastestTTR = ms(local.fastestTTR),
            medianTTR = ms(local.medianTTR),
            characters = local.characters.map { it.copy(medianTTR = ms(it.medianTTR)) }
        )
        assertEquals("$name decodes back to the local record", expected, SyncWire.decodeSession(wire))
        assertEquals(local.id.toString().lowercase(), SyncWire.encodeSession(local).getString("id"))
    }

    @Test
    fun `a full session encodes and decodes per the fixture`() = checkSession("session")

    @Test
    fun `a legacy session carries nulls and empty lists`() = checkSession("legacySession")

    // ---- Mode ids ----

    @Test
    fun `every recorded mode string maps to its canonical id and back`() {
        val ids = fixture.getJSONObject("modeIds")
        val table = ids.getJSONObject("kotlin")
        val canonical = strings(ids.getJSONArray("canonical"))
        assertEquals(26, table.length())
        assertEquals(canonical.toSet(), SyncWire.modeIds.values.toSet())
        assertEquals(table.keySet(), SyncWire.modeIds.keys)
        for (title in table.keys()) {
            val id = table.getString(title)
            assertEquals("wire id of $title", id, SyncWire.wireMode(title))
            assertEquals("local mode of $id", title, SyncWire.localMode(id))
        }
    }

    @Test
    fun `an unknown wire id decodes to itself`() {
        val unknown = fixture.getJSONObject("modeIds").getString("unknownWireId")
        assertEquals(unknown, SyncWire.localMode(unknown))
    }

    @Test
    fun `the passive canonical ids are this port's passive modes`() {
        val passive = strings(fixture.getJSONObject("modeIds").getJSONArray("passiveCanonical"))
        assertEquals(passive.toSet(), SessionRecord.PASSIVE_MODES.map { SyncWire.wireMode(it) }.toSet())
    }

    @Test
    fun `the desktop client id matches the fixture`() {
        assertEquals(fixture.getJSONObject("client").getString("desktop"), SyncWire.CLIENT)
    }

    // ---- Days ----

    @Test
    fun `the day batch encodes per the fixture`() {
        val block = fixture.getJSONObject("days")
        val local = block.getJSONArray("local")
        val days = (0 until local.length()).associate { i ->
            val d = local.getJSONObject(i)
            LocalDate.parse(d.getString("day")) to d.getInt("seconds")
        }
        assertSimilar("days body", block.getJSONObject("wire"), SyncWire.encodeDays(days))
    }

    // ---- State codecs ----

    private val stateWire: JSONObject get() = fixture.getJSONObject("state").getJSONObject("wire").getJSONObject("entries")
    private fun wireValue(key: String): JSONObject = stateWire.getJSONObject(key).getJSONObject("value")
    private val stateKotlin: JSONObject get() = fixture.getJSONObject("state").getJSONObject("kotlin")

    @Test
    fun `the five state keys are the fixture's`() {
        assertEquals(strings(fixture.getJSONObject("state").getJSONArray("keys")), SyncStateCodec.keys)
        assertEquals(stateWire.keySet(), SyncStateCodec.keys.toSet())
    }

    @Test
    fun `journey state round-trips from the stored form`() {
        val stored = stateKotlin.getJSONObject("journey")
        // As JourneyStore.load reads it: the completed set is strings.
        val progress = JourneyProgress(
            unlockedThrough = stored.getInt("unlockedThrough"),
            currentLevel = stored.getInt("currentLevel"),
            completed = strings(stored.getJSONArray("completed")).map { it.toInt() }.toMutableSet()
        )
        assertSimilar("journey to wire", wireValue("journey"), SyncStateCodec.journeyToWire(progress))
        val back = SyncStateCodec.journeyFromWire(wireValue("journey"))
        assertEquals(progress, back)
        assertEquals(strings(stored.getJSONArray("completed")).toSet(), back.completed.map { it.toString() }.toSet())
    }

    /** EngineStore's saved JSON with the fixture's position and some stats and confusions that must survive. */
    private fun engineJson(stored: JSONObject): String {
        val o = JSONObject()
            .put("active", stored.getString("active"))
            .put("exposed", stored.getString("exposed"))
            .put("stats", JSONArray().put(JSONObject().put("c", "K").put("a", JSONArray().put(JSONArray().put(1).put(0.4)))))
            .put("conf", JSONObject().put("K>R", 2))
            .put("stage", stored.getString("stage"))
        if (!stored.isNull("pin")) o.put("pin", stored.getString("pin"))
        return o.toString()
    }

    @Test
    fun `characters state round-trips and keeps local stats`() {
        val stored = stateKotlin.getJSONObject("characters")
        val json = engineJson(stored)
        assertSimilar("characters to wire", wireValue("characters"), SyncStateCodec.charactersToWire(json))

        // Apply onto a different local position: the position moves, the stats and confusions stay.
        val other = JSONObject(json).put("active", "KM").put("exposed", "").put("stage", "Triples").put("pin", "Singles")
        val applied = JSONObject(SyncStateCodec.applyCharacters(other.toString(), wireValue("characters")))
        assertEquals(stored.getString("active"), applied.getString("active"))
        assertEquals(stored.getString("exposed"), applied.getString("exposed"))
        assertEquals(stored.getString("stage"), applied.getString("stage"))
        assertTrue("a null pinnedStage removes the pin", stored.isNull("pin") && !applied.has("pin"))
        assertSimilar("stats kept", JSONObject(json).getJSONArray("stats"), applied.getJSONArray("stats"))
        assertSimilar("confusions kept", JSONObject(json).getJSONObject("conf"), applied.getJSONObject("conf"))
        assertSimilar("re-encoded", wireValue("characters"), SyncStateCodec.charactersToWire(applied.toString()))
    }

    @Test
    fun `characters state applied with nothing saved starts empty stats`() {
        val applied = JSONObject(SyncStateCodec.applyCharacters(null, wireValue("characters")))
        assertEquals(0, applied.getJSONArray("stats").length())
        assertEquals(0, applied.getJSONObject("conf").length())
        assertSimilar("re-encoded", wireValue("characters"), SyncStateCodec.charactersToWire(applied.toString()))
    }

    @Test
    fun `first four state round-trips`() {
        val progress = SyncStateCodec.firstFourFromWire(wireValue("firstFour"))
        assertEquals(setOf(FirstFourStage.CALL, FirstFourStage.STATE), progress.passed)
        assertEquals(setOf(FirstFourStage.CALL), progress.copyPassed)
        assertEquals(mapOf(FirstFourStage.QUESTION to 1), progress.cleanRunsByStage)
        assertSimilar("first four to wire", wireValue("firstFour"), SyncStateCodec.firstFourToWire(progress))
    }

    @Test
    fun `operating procedure state round-trips`() {
        val progress = SyncStateCodec.operatingProcedureFromWire(wireValue("operatingProcedure"))
        assertEquals(setOf(OpLesson.OFFSET, OpLesson.SIGNALS), progress.passed)
        assertEquals(setOf(OpLesson.WHEN), progress.cleanRuns)
        assertEquals(wireValue("operatingProcedure").getBoolean("drillPassed"), progress.drillPassed)
        assertSimilar("operating procedure to wire", wireValue("operatingProcedure"), SyncStateCodec.operatingProcedureToWire(progress))
    }

    @Test
    fun `story bookmarks round-trip from the stored string`() {
        val stored = stateKotlin.getString("storyBookmarks")
        assertSimilar("bookmarks to wire", wireValue("storyBookmarks"), SyncStateCodec.storyBookmarksToWire(stored))
        assertEquals(stored, SyncStateCodec.storyBookmarksFromWire(wireValue("storyBookmarks")))
    }

    @Test
    fun `the state body wraps each value with its time`() {
        val entries = SyncStateCodec.keys.associateWith { key ->
            val e = stateWire.getJSONObject(key)
            SyncWire.StateEntry(e.getJSONObject("value"), e.getLong("updatedAt"))
        }
        assertSimilar("state body", fixture.getJSONObject("state").getJSONObject("wire"), SyncWire.encodeState(entries))
        val decoded = SyncWire.decodeState(stateWire)
        assertEquals(SyncStateCodec.keys.toSet(), decoded.keys)
        assertEquals(1791230000000L, decoded.getValue("journey").updatedAt)
    }

    // ---- Merge: sessions ----

    private fun mergeBlock(name: String): JSONObject = fixture.getJSONObject("merge").getJSONObject(name)

    private fun stub(o: JSONObject) = SessionRecord(
        id = UUID.fromString(o.getString("id")), date = Instant.ofEpochMilli(o.getLong("date")), mode = "Characters",
        characterWPM = 20, effectiveWPM = 20, attempts = o.getInt("attempts"), correct = 0,
        fastestTTR = null, medianTTR = null, durationSeconds = null,
        characters = emptyList(), activeCharacters = emptyList()
    )

    @Test
    fun `pulled sessions merge by id, newest first, capped`() {
        val m = mergeBlock("sessions")
        fun records(key: String) = m.getJSONArray(key).let { a -> (0 until a.length()).map { stub(a.getJSONObject(it)) } }
        val merged = SyncMerge.mergeSessions(records("local"), records("pulled"), m.getInt("limit"))
        assertEquals(strings(m.getJSONArray("expectedIds")).map { it.lowercase() }, merged.map { it.id.toString() })
        val id3 = merged.first { it.id.toString().endsWith("000000000003") }
        assertEquals("a local record is never replaced", m.getInt("expectedAttemptsOfId3"), id3.attempts)
    }

    // ---- Merge: aggregates ----

    @Test
    fun `server aggregates replace the local counters`() {
        val m = mergeBlock("aggregates")
        val adopted = SyncMerge.adoptAggregates(m.getJSONObject("serverStats"))!!
        val e = m.getJSONObject("expected")
        assertEquals(e.getInt("totalSessions"), adopted.totalSessions)
        assertEquals(e.getInt("totalAnswered"), adopted.totalAnswered)
        assertEquals(e.getInt("totalCorrect"), adopted.totalCorrect)
        assertEquals(e.getDouble("totalPracticeSeconds"), adopted.totalPracticeSeconds, 0.0)
        assertEquals(e.getDouble("bestTtrSeconds"), adopted.bestTtrMs!! / 1000.0, 0.0)
        // The fixture keys bests by canonical id; this port keys Stats.bestScores by its mode strings.
        val bests = e.getJSONObject("bestScores")
        assertEquals(bests.keySet().associate { SyncWire.localMode(it) to bests.getInt(it) }, adopted.bestScores)
        assertTrue("local bests the server lacks are not kept", "Contest" !in adopted.bestScores)
    }

    @Test
    fun `a null server bestTtr adopts as null`() {
        val m = mergeBlock("aggregates")
        val stats = JSONObject(m.getJSONObject("serverStats").toString())
        stats.put("bestTtrMs", m.getJSONObject("serverWithNoBestTtr").get("bestTtrMs"))
        assertTrue(m.isNull("expectedBestTtrWhenServerNull"))
        assertNull(SyncMerge.adoptAggregates(stats)!!.bestTtrMs)
    }

    // ---- Merge: ledger ----

    @Test
    fun `server days are adopted day by day`() {
        val m = mergeBlock("ledger")
        val merged = SyncMerge.mergeLedger(SyncWire.decodeDayMap(m.getJSONObject("local")), SyncWire.decodeDayMap(m.getJSONObject("server")))
        assertEquals(SyncWire.decodeDayMap(m.getJSONObject("expected")), merged.days)
    }

    @Test
    fun `the merged ledger keeps its cap`() {
        val start = LocalDate.of(2025, 1, 1)
        val local = (0 until ActivityLedger.CAP_DAYS).associate { start.plusDays(it.toLong()) to 60 }
        val server = mapOf(start.plusDays(ActivityLedger.CAP_DAYS.toLong()) to 120)
        val merged = SyncMerge.mergeLedger(local, server)
        assertEquals(ActivityLedger.CAP_DAYS, merged.dayCount)
        assertEquals(start.plusDays(1), merged.recordedDays.first())
    }

    // ---- Merge: state ----

    @Test
    fun `state replies win only when strictly newer`() {
        val m = mergeBlock("state")
        val merged = SyncMerge.mergeState(SyncWire.decodeState(m.getJSONObject("local")), SyncWire.decodeState(m.getJSONObject("reply")))
        val expected = m.getJSONObject("expectedUpdatedAt")
        assertEquals(expected.keySet().associateWith { expected.getLong(it) }, merged.mapValues { it.value.updatedAt })
        assertEquals(m.getInt("expectedJourneyCurrentLevel"), (merged.getValue("journey").value as JSONObject).getInt("currentLevel"))
        assertEquals(
            strings(m.getJSONArray("expectedCharactersActive")),
            strings((merged.getValue("characters").value as JSONObject).getJSONArray("activeCharacters"))
        )
    }

    // ---- Outbox ----

    @Test
    fun `a push reply drains accepted, skipped and rejected ids`() {
        val m = mergeBlock("outbox")
        val outbox = SyncMerge.Outbox(strings(m.getJSONArray("pending")))
        val after = outbox.afterPush(SyncMerge.PushReply.parse(m.getJSONObject("reply")))
        assertEquals(strings(m.getJSONArray("expectedRemaining")), after.ids)
        assertEquals(m.getInt("batchSize"), SyncMerge.Outbox.BATCH_SIZE)
    }

    @Test
    fun `the outbox cap drops the oldest`() {
        val cap = mergeBlock("outbox").getJSONObject("cap")
        var outbox = SyncMerge.Outbox(max = cap.getInt("max"))
        for (id in strings(cap.getJSONArray("enqueue"))) outbox = outbox.enqueue(id)
        assertEquals(strings(cap.getJSONArray("expected")), outbox.ids)
        assertEquals(cap.getInt("appMax"), SyncMerge.Outbox.APP_MAX)
    }

    @Test
    fun `batches are oldest first and at most the batch size`() {
        var outbox = SyncMerge.Outbox()
        repeat(450) { outbox = outbox.enqueue("id$it") }
        assertEquals((0 until 200).map { "id$it" }, outbox.nextBatch())
    }

    // ---- Backoff and retry ----

    @Test
    fun `backoff doubles to the cap`() {
        val m = mergeBlock("backoff")
        val delays = m.getJSONArray("delaysSeconds")
        for (i in 0 until delays.length()) {
            assertEquals("retry ${i + 1}", delays.getLong(i), SyncMerge.backoffSeconds(i + 1))
        }
        assertEquals(m.getLong("capSeconds"), SyncMerge.BACKOFF_CAP_SECONDS)
        assertEquals(m.getLong("capSeconds"), SyncMerge.backoffSeconds(64))
    }

    @Test
    fun `every HTTP outcome has the fixture's action`() {
        val outcomes = mergeBlock("retry").getJSONArray("outcomes")
        for (i in 0 until outcomes.length()) {
            val o = outcomes.getJSONObject(i)
            val status = o.get("status").let { if (it == "network") null else (it as Number).toInt() }
            assertEquals("status ${o.get("status")}", o.getString("action"), SyncMerge.retryAction(status).raw)
        }
    }

    // ---- PKCE ----

    @Test
    fun `the PKCE challenge matches RFC 7636 appendix B`() {
        val p = fixture.getJSONObject("pkce")
        assertEquals(p.getString("challenge"), Pkce.challenge(p.getString("verifier")))
    }

    @Test
    fun `a fresh verifier is 32 bytes of base64url without padding`() {
        val p = fixture.getJSONObject("pkce")
        val v = Pkce.verifier()
        assertEquals(p.getInt("verifierLength"), v.length)
        assertTrue("base64url alphabet only", v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(p.getInt("verifierBytes"), Base64.getUrlDecoder().decode(v).size)
        assertEquals(43, Pkce.challenge(v).length)
    }
}
