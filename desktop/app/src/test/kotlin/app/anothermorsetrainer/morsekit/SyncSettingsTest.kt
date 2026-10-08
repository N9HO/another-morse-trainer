package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncSettings] held to `fixtures/sync-wire.json` `settings`, the section the
 * Swift harness and the desktop suite read too: every key, its kind and
 * bounds, its default, every worked normalising case and the default test.
 */
class SyncSettingsTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("sync-wire.json")
        assertNotNull("fixtures/sync-wire.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText()).getJSONObject("settings")
    }

    private fun keyNames(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    /** A [SyncSettings.Kind] in the fixture's words. */
    private fun kindJson(kind: SyncSettings.Kind): JSONObject = when (kind) {
        SyncSettings.Kind.Bool -> JSONObject().put("kind", "bool")
        is SyncSettings.Kind.IntRange -> JSONObject().put("kind", "int").put("min", kind.min).put("max", kind.max)
        is SyncSettings.Kind.Tenths -> JSONObject().put("kind", "number").put("min", kind.min).put("max", kind.max).put("step", 0.1)
        is SyncSettings.Kind.Enum -> JSONObject().put("kind", "enum").put("values", JSONArray(kind.values))
        is SyncSettings.Kind.IntChoice -> JSONObject().put("kind", "intChoice").put("values", JSONArray(kind.values))
        is SyncSettings.Kind.Members -> JSONObject().put("kind", "members").put("values", JSONArray(kind.values)).put("minCount", kind.minCount)
        is SyncSettings.Kind.Text -> JSONObject().put("kind", "text").put("maxLength", kind.maxLength).put("transform", kind.transform)
        is SyncSettings.Kind.Words -> JSONObject().put("kind", "words").put("maxCount", kind.maxCount).put("maxLength", kind.maxLength)
        is SyncSettings.Kind.Range -> JSONObject().put("kind", "range")
            .put("min", JSONArray(listOf(kind.min.start, kind.min.endInclusive)))
            .put("max", JSONArray(listOf(kind.max.start, kind.max.endInclusive)))
            .put("whole", kind.whole)
        SyncSettings.Kind.Speed -> JSONObject().put("kind", "speed")
    }

    @Test
    fun `the settings keys are the fixture's, each a state key with the prefix`() {
        val table = fixture.getJSONObject("keys")
        assertEquals(keyNames(table), SyncSettings.KEYS.toSet())
        assertEquals(table.length(), SyncSettings.KEYS.size)
        assertEquals(fixture.getString("keyPrefix"), SyncSettings.PREFIX)
        assertTrue(SyncSettings.KEYS.all { SyncSettings.isSetting(it) && it in SyncStateCodec.allKeys })
        assertTrue(SyncStateCodec.keys.none { SyncSettings.isSetting(it) })
        assertEquals(SyncStateCodec.keys + SyncSettings.KEYS, SyncStateCodec.allKeys)
    }

    @Test
    fun `each key's kind, bounds and default are the fixture's`() {
        val table = fixture.getJSONObject("keys")
        for (spec in SyncSettings.specs) {
            val expected = JSONObject(table.getJSONObject(spec.key).toString())
            val default = expected.remove("default")
            expected.remove("kotlin")
            assertTrue("${spec.key} kind ${kindJson(spec.kind)} != $expected", SyncSettings.jsonEquals(kindJson(spec.kind), expected))
            assertTrue("${spec.key} default ${spec.default} != $default", SyncSettings.jsonEquals(spec.default, default))
            assertTrue("${spec.key} default is normalised", SyncSettings.jsonEquals(SyncSettings.normalize(spec.key, spec.default), spec.default))
        }
    }

    @Test
    fun `normalising gives every worked case's result`() {
        val cases = fixture.getJSONObject("normalize").getJSONArray("cases")
        assertTrue(cases.length() > 40)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val key = c.getString("key")
            val got = SyncSettings.normalize(key, c.get("in"))
            val want = if (c.isNull("out")) null else c.get("out")
            assertTrue("$key ${c.get("in")} -> $got, want $want", SyncSettings.jsonEquals(got, want))
        }
    }

    @Test
    fun `the default test agrees with the fixture`() {
        val cases = fixture.getJSONObject("isDefault").getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.toString(), c.getBoolean("default"), SyncSettings.isDefault(c.getString("key"), c.get("value")))
        }
    }

    @Test
    fun `the morsekit enums' codes are the wire values, in order`() {
        val table = fixture.getJSONObject("keys")
        fun values(key: String): List<String> = table.getJSONObject(key).getJSONArray("values").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(values("setting.answerEntry"), AnswerEntryMode.entries.map { it.id })
        assertEquals(values("setting.cw77Style"), Cw77Style.entries.map { it.id })
        assertEquals(values("setting.examSpeed"), ExamSpeed.entries.map { it.code })
        assertEquals(values("setting.contestType"), ContestType.entries.map { it.code })
        assertEquals(values("setting.contestLength"), ContestLength.entries.map { it.code })
        assertEquals(values("setting.pileupMode"), QSOContestMode.entries.map { it.code })
        assertEquals(values("setting.pileupBust"), BustBehavior.entries.map { it.code })
        assertEquals(values("setting.pileupMissedCallerFeedback"), MissedCallerFeedback.entries.map { it.code })
        assertEquals(values("setting.rapidFireFormats"), CallsignFormat.entries.map { it.code })
        assertEquals(values("setting.pileupFormats"), CallsignFormat.entries.map { it.code })
        assertEquals(values("setting.punctuation"), MorseCode.pickablePunctuation.map { it.toString() })
        assertEquals(values("setting.pileupCutDigits"), CutNumbers.cuttableDigits.map { it.toString() })
    }
}
