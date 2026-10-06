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
import java.util.UUID

/**
 * Account sync's pure half ([SyncWire], [SyncModes], [SyncState],
 * [SyncMerge], [SyncOutbox], [SyncRetry], [Pkce]) held to
 * `fixtures/sync-wire.json`, the file the Swift harness's "Account sync"
 * section and the desktop suite read too, so every port pins the same wire
 * format and merge rules.
 */

/** Android's platform org.json (first on the unit-test compile classpath) has no keySet(). */
private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

class SyncWireTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("sync-wire.json")
        assertNotNull("fixtures/sync-wire.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun nullableDouble(o: JSONObject, key: String): Double? = if (o.isNull(key)) null else o.getDouble(key)

    /** The fixture's `local` record as this port holds it: seconds, the Kotlin mode string. */
    private fun localRecord(o: JSONObject): SessionRecord {
        val chars = o.getJSONArray("characters")
        return SessionRecord(
            id = UUID.fromString(o.getString("id")),
            date = Instant.ofEpochMilli(o.getLong("dateEpochMs")),
            mode = o.getJSONObject("localMode").getString("kotlin"),
            characterWPM = o.getInt("characterWpm"),
            effectiveWPM = o.getInt("effectiveWpm"),
            attempts = o.getInt("attempts"),
            correct = o.getInt("correct"),
            fastestTTR = nullableDouble(o, "fastestTtrSeconds"),
            medianTTR = nullableDouble(o, "medianTtrSeconds"),
            durationSeconds = nullableDouble(o, "durationSeconds"),
            characters = (0 until chars.length()).map { i ->
                val c = chars.getJSONObject(i)
                SessionRecord.CharResult(c.getString("character"), c.getInt("attempts"), c.getInt("correct"), nullableDouble(c, "medianTtrSeconds"))
            },
            activeCharacters = strings(o.getJSONArray("activeCharacters")),
            score = if (o.isNull("score")) null else o.getInt("score")
        )
    }

    /** JSON equality, key order free, numbers by value (300.5 == 300.5, 212 == 212L). */
    private fun assertJsonEquals(message: String, expected: Any?, actual: Any?) {
        when {
            expected is JSONObject && actual is JSONObject -> {
                assertEquals("$message: keys", expected.keyNames(), actual.keyNames())
                for (k in expected.keyNames()) assertJsonEquals("$message.$k", expected.get(k), actual.get(k))
            }
            expected is JSONArray && actual is JSONArray -> {
                assertEquals("$message: length", expected.length(), actual.length())
                for (i in 0 until expected.length()) assertJsonEquals("$message[$i]", expected.get(i), actual.get(i))
            }
            expected is Number && actual is Number ->
                assertEquals(message, expected.toDouble(), actual.toDouble(), 0.0)
            else -> assertEquals(message, expected, actual)
        }
    }

    // ---- Sessions ----

    private fun checkSession(name: String) {
        val f = fixture.getJSONObject(name)
        val local = localRecord(f.getJSONObject("local"))
        val wire = f.getJSONObject("wire")
        assertJsonEquals("$name encode", wire, SyncWire.encodeSession(local))
        val decoded = SyncWire.decodeSession(wire)
        // The wire keeps whole milliseconds, so a TTR comes back as round(s * 1000) / 1000:
        // 0.2124 s goes out as 212 and returns as 0.212. Everything else returns exactly.
        fun ms(s: Double?) = s?.let { Math.round(it * 1000) / 1000.0 }
        val expected = local.copy(
            fastestTTR = ms(local.fastestTTR),
            medianTTR = ms(local.medianTTR),
            characters = local.characters.map { it.copy(medianTTR = ms(it.medianTTR)) }
        )
        assertEquals("$name decode", expected, decoded)
        assertJsonEquals("$name re-encode", wire, SyncWire.encodeSession(decoded))
        assertEquals("$name id is lowercase", local.id.toString().lowercase(), decoded.id.toString())
    }

    @Test
    fun `a full session encodes to the wire and back`() = checkSession("session")

    @Test
    fun `a legacy session carries nulls and empty lists, never -1`() = checkSession("legacySession")

    // ---- Mode ids ----

    @Test
    fun `all 25 mode strings map to their canonical ids and back`() {
        val ids = fixture.getJSONObject("modeIds")
        val table = ids.getJSONObject("kotlin")
        val canonical = strings(ids.getJSONArray("canonical"))
        assertEquals(25, table.length())
        assertEquals(table.keyNames() + "control", SyncModes.wireIds.keys)
        for (local in table.keyNames()) {
            val wire = table.getString(local)
            assertEquals(local, wire, SyncModes.wireId(local))
            assertEquals(wire, local, SyncModes.localMode(wire))
        }
        assertEquals(canonical.toSet(), SyncModes.wireIds.values.toSet())
    }

    @Test
    fun `an unknown wire mode id decodes to itself`() {
        val unknown = fixture.getJSONObject("modeIds").getString("unknownWireId")
        assertEquals(unknown, SyncModes.localMode(unknown))
    }

    @Test
    fun `the passive modes are the passive canonical ids`() {
        val passive = strings(fixture.getJSONObject("modeIds").getJSONArray("passiveCanonical"))
        assertEquals(passive.toSet(), SessionRecord.PASSIVE_MODES.map { SyncModes.wireId(it) }.toSet())
    }

    // ---- Days ----

    @Test
    fun `the day batch encodes as the fixture`() {
        val f = fixture.getJSONObject("days")
        val local = f.getJSONArray("local")
        val days = (0 until local.length()).associate { i ->
            val o = local.getJSONObject(i)
            LocalDate.parse(o.getString("day")) to o.getInt("seconds")
        }
        assertJsonEquals("days", f.getJSONObject("wire"), SyncWire.encodeDays(days))
        assertEquals(days, SyncWire.decodeDays(f.getJSONObject("wire").getJSONArray("days")))
    }

    // ---- State ----

    private val stateWire: JSONObject get() = fixture.getJSONObject("state").getJSONObject("wire").getJSONObject("entries")
    private val stateKotlin: JSONObject get() = fixture.getJSONObject("state").getJSONObject("kotlin")
    private fun wireValue(key: String): JSONObject = stateWire.getJSONObject(key).getJSONObject("value")

    @Test
    fun `the five state keys are the fixture's`() {
        assertEquals(strings(fixture.getJSONObject("state").getJSONArray("keys")), SyncState.KEYS)
    }

    @Test
    fun `journey state both ways`() {
        val k = stateKotlin.getJSONObject("journey")
        // JourneyStore's stored form: the completed levels as a string set.
        val stored = JourneyProgress(
            unlockedThrough = k.getInt("unlockedThrough"),
            currentLevel = k.getInt("currentLevel"),
            completed = strings(k.getJSONArray("completed")).map { it.toInt() }.toMutableSet()
        )
        assertJsonEquals("journey", wireValue("journey"), SyncState.journeyToWire(stored))
        assertEquals(stored, SyncState.journeyFromWire(wireValue("journey")))
    }

    @Test
    fun `characters state both ways, keeping local stats and confusions`() {
        val k = stateKotlin.getJSONObject("characters")
        val stats = listOf(CharacterStats('K', listOf(CharacterStats.Attempt(true, 0.4))))
        val confusions = mapOf("K>R" to 2)
        val stored = ProgressiveCharacters.Snapshot(
            engine = TrainerEngine.Snapshot(
                activeCharacters = k.getString("active").toList(),
                stats = stats,
                confusions = confusions,
                exposedCharacters = k.getString("exposed").toSet()
            ),
            stage = ProgressiveCharacters.Stage.valueOf(k.getString("stage")),
            pinnedStage = if (k.isNull("pin")) null else ProgressiveCharacters.Stage.valueOf(k.getString("pin"))
        )
        assertJsonEquals("characters", wireValue("characters"), SyncState.charactersToWire(stored))

        // Applied over a local track with other stats: the ladder moves, the stats stay.
        val local = ProgressiveCharacters.Snapshot(
            engine = TrainerEngine.Snapshot(listOf('K', 'M'), stats, confusions, setOf('K')),
            stage = ProgressiveCharacters.Stage.Singles
        )
        val applied = SyncState.applyCharacters(local, wireValue("characters"))
        assertEquals(stored, applied)
        assertEquals(stats, applied.engine.stats)
        assertEquals(confusions, applied.engine.confusions)
    }

    @Test
    fun `first four state both ways`() {
        val p = SyncState.firstFourFromWire(wireValue("firstFour"))
        assertJsonEquals("firstFour", wireValue("firstFour"), SyncState.firstFourToWire(p))
        assertEquals(setOf(FirstFourStage.CALL, FirstFourStage.STATE), p.passed)
        assertEquals(mapOf(FirstFourStage.QUESTION to 1), p.cleanRunsByStage)
    }

    @Test
    fun `operating procedure state both ways`() {
        val p = SyncState.operatingProcedureFromWire(wireValue("operatingProcedure"))
        assertJsonEquals("operatingProcedure", wireValue("operatingProcedure"), SyncState.operatingProcedureToWire(p))
        assertEquals(setOf(OpLesson.OFFSET, OpLesson.SIGNALS), p.passed)
    }

    @Test
    fun `story bookmarks state both ways`() {
        val stored = stateKotlin.getString("storyBookmarks")
        assertJsonEquals("storyBookmarks", wireValue("storyBookmarks"), SyncState.storyBookmarksToWire(stored))
        assertEquals(stored, SyncState.storyBookmarksFromWire(wireValue("storyBookmarks")))
    }

    // ---- Merge ----

    private fun mergeFixture(name: String) = fixture.getJSONObject("merge").getJSONObject(name)

    private fun stub(o: JSONObject) = SessionRecord(
        id = UUID.fromString(o.getString("id")), date = Instant.ofEpochMilli(o.getLong("date")),
        mode = "Characters", characterWPM = 20, effectiveWPM = 20, attempts = o.getInt("attempts"), correct = 0,
        fastestTTR = null, medianTTR = null, durationSeconds = null, characters = emptyList(), activeCharacters = emptyList()
    )

    @Test
    fun `pulled sessions join without replacing local ones, newest kept`() {
        val f = mergeFixture("sessions")
        fun list(key: String) = f.getJSONArray(key).let { a -> (0 until a.length()).map { stub(a.getJSONObject(it)) } }
        val merged = SyncMerge.mergeSessions(list("local"), list("pulled"), f.getInt("limit"))
        assertEquals(strings(f.getJSONArray("expectedIds")).map { it.lowercase() }, merged.map { it.id.toString() })
        val id3 = merged.first { it.id.toString().endsWith("000000000003") }
        assertEquals(f.getInt("expectedAttemptsOfId3"), id3.attempts)
    }

    private fun totals(o: JSONObject): SyncMerge.LifetimeTotals {
        val bests = o.getJSONObject("bestScores")
        return SyncMerge.LifetimeTotals(
            totalSessions = o.getInt("totalSessions"),
            totalAnswered = o.getInt("totalAnswered"),
            totalCorrect = o.getInt("totalCorrect"),
            totalPracticeSeconds = o.getDouble("totalPracticeSeconds"),
            bestTtrSeconds = nullableDouble(o, "bestTtrSeconds"),
            // The fixture keys bests by canonical id; this port keys them by its own mode string.
            bestScores = bests.keyNames().associate { SyncModes.localMode(it) to bests.getInt(it) }
        )
    }

    @Test
    fun `server aggregates replace the local lifetime counters`() {
        val f = mergeFixture("aggregates")
        val adopted = SyncMerge.adoptAggregates(totals(f.getJSONObject("local")), f.getJSONObject("serverStats"))
        val expected = totals(f.getJSONObject("expected"))
        assertEquals(expected.copy(bestTtrSeconds = null), adopted.copy(bestTtrSeconds = null))
        assertEquals(expected.bestTtrSeconds!!, adopted.bestTtrSeconds!!, 1e-9)
        assertEquals(mapOf("Rapid Fire" to 44, "Morse Invaders" to 8810), adopted.bestScores)
    }

    @Test
    fun `a null server best TTR clears the local one`() {
        val f = mergeFixture("aggregates")
        val server = JSONObject(f.getJSONObject("serverStats").toString())
        for (k in f.getJSONObject("serverWithNoBestTtr").keyNames()) server.put(k, f.getJSONObject("serverWithNoBestTtr").get(k))
        val adopted = SyncMerge.adoptAggregates(totals(f.getJSONObject("local")), server)
        assertTrue(f.isNull("expectedBestTtrWhenServerNull"))
        assertNull(adopted.bestTtrSeconds)
    }

    @Test
    fun `server ledger days are adopted, unmentioned local days kept`() {
        val f = mergeFixture("ledger")
        val merged = SyncMerge.mergeLedger(SyncWire.decodeDays(f.getJSONObject("local")), SyncWire.decodeDays(f.getJSONObject("server")))
        assertEquals(SyncWire.decodeDays(f.getJSONObject("expected")), merged)
    }

    @Test
    fun `state is last writer wins, strictly newer only`() {
        val f = mergeFixture("state")
        val merged = SyncMerge.mergeState(
            SyncMerge.decodeStateEntries(f.getJSONObject("local")),
            SyncMerge.decodeStateEntries(f.getJSONObject("reply"))
        )
        val expected = f.getJSONObject("expectedUpdatedAt")
        assertEquals(expected.keyNames(), merged.keys)
        for (k in expected.keyNames()) assertEquals(k, expected.getLong(k), merged.getValue(k).updatedAt)
        val journey = SyncState.journeyFromWire(merged.getValue("journey").value as JSONObject)
        assertEquals(f.getInt("expectedJourneyCurrentLevel"), journey.currentLevel)
        val chars = (merged.getValue("characters").value as JSONObject).getJSONArray("activeCharacters")
        assertEquals(strings(f.getJSONArray("expectedCharactersActive")), strings(chars))
    }

    @Test
    fun `a push reply drains accepted, skipped and rejected ids only`() {
        val f = mergeFixture("outbox")
        var box = SyncOutbox()
        for (id in strings(f.getJSONArray("pending"))) box = box.enqueue(SyncOutbox.Entry(id, "{}"))
        assertEquals(strings(f.getJSONArray("expectedRemaining")), box.applyPushReply(f.getJSONObject("reply")).ids)
        assertEquals(f.getInt("batchSize"), SyncOutbox.BATCH_SIZE)
    }

    @Test
    fun `the outbox drops its oldest past the cap`() {
        val cap = mergeFixture("outbox").getJSONObject("cap")
        var box = SyncOutbox(max = cap.getInt("max"))
        for (id in strings(cap.getJSONArray("enqueue"))) box = box.enqueue(SyncOutbox.Entry(id, "{}"))
        assertEquals(strings(cap.getJSONArray("expected")), box.ids)
        assertEquals(cap.getInt("appMax"), SyncOutbox.APP_MAX)
        assertEquals(box, SyncOutbox.decode(box.encode(), cap.getInt("max")))
    }

    @Test
    fun `backoff doubles from 2 s and caps at 10 minutes`() {
        val f = mergeFixture("backoff")
        val delays = f.getJSONArray("delaysSeconds")
        for (i in 0 until delays.length()) assertEquals("retry ${i + 1}", delays.getLong(i), SyncRetry.backoffSeconds(i + 1))
        assertEquals(f.getLong("capSeconds"), SyncRetry.CAP_SECONDS)
        assertEquals(SyncRetry.CAP_SECONDS, SyncRetry.backoffSeconds(1000))
    }

    @Test
    fun `each HTTP outcome maps to the fixture's action`() {
        val outcomes = mergeFixture("retry").getJSONArray("outcomes")
        for (i in 0 until outcomes.length()) {
            val o = outcomes.getJSONObject(i)
            val status = o.get("status").let { if (it == "network") null else (it as Number).toInt() }
            assertEquals("status $status", o.getString("action"), SyncRetry.action(status).name.lowercase())
        }
    }

    // ---- PKCE ----

    @Test
    fun `the PKCE challenge is RFC 7636's`() {
        val f = fixture.getJSONObject("pkce")
        assertEquals(f.getString("challenge"), Pkce.challenge(f.getString("verifier")))
    }

    @Test
    fun `a fresh PKCE verifier is 32 bytes as 43 base64url characters`() {
        val f = fixture.getJSONObject("pkce")
        val v = Pkce.newVerifier()
        assertEquals(f.getInt("verifierLength"), v.length)
        assertEquals(f.getInt("verifierBytes") * 4 / 3 + 1, v.length)
        assertTrue(v, v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(43, Pkce.challenge(v).length)
    }
}
