package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The input-settings rules (parity rows 18-20), held to
 * `fixtures/gap-input-settings-rules.json` — the same file the Swift harness
 * reads. Expected values were derived from the stated rules, not captured from
 * either port.
 */
class InputRulesFixtureTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("gap-input-settings-rules.json")
        assertNotNull("fixtures/gap-input-settings-rules.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private val delta = 1e-12

    @Test
    fun sidetoneSliderShapeMatchesTheFixture() {
        val s = fixture.getJSONObject("sidetone")
        assertEquals(s.getDouble("min"), InputRules.SIDETONE_MIN, delta)
        assertEquals(s.getDouble("max"), InputRules.SIDETONE_MAX, delta)
        assertEquals(s.getDouble("step"), InputRules.SIDETONE_STEP, delta)
        assertEquals(s.getInt("composeSteps"), InputRules.SIDETONE_COMPOSE_STEPS)
    }

    @Test
    fun sidetoneIsClampedOnLoad() {
        for (c in fixture.getJSONObject("sidetone").getJSONArray("load").objects()) {
            assertEquals("stored ${c.get("stored")}", c.getDouble("value"), InputRules.clampSidetone(c.getDouble("stored")), delta)
        }
    }

    @Test
    fun toneSpreadSliderShapeMatchesTheFixture() {
        val s = fixture.getJSONObject("toneSpread")
        assertEquals(s.getDouble("min"), InputRules.TONE_SPREAD_MIN, delta)
        assertEquals(s.getDouble("max"), InputRules.TONE_SPREAD_MAX, delta)
        assertEquals(s.getDouble("step"), InputRules.TONE_SPREAD_STEP, delta)
        assertEquals(s.getInt("composeSteps"), InputRules.TONE_SPREAD_COMPOSE_STEPS)
    }

    @Test
    fun toneSpreadReadsZeroBeatBelowOneStep() {
        for (c in fixture.getJSONObject("toneSpread").getJSONArray("labels").objects()) {
            assertEquals("spread ${c.get("value")}", c.getBoolean("zeroBeat"), InputRules.isZeroBeat(c.getDouble("value")))
        }
    }

    @Test
    fun toneSpreadIsClampedOnLoad() {
        for (c in fixture.getJSONObject("toneSpread").getJSONArray("load").objects()) {
            assertEquals("stored ${c.get("stored")}", c.getDouble("value"), InputRules.clampToneSpread(c.getDouble("stored")), delta)
        }
    }

    @Test
    fun pileupRangesAreClampedEachToItsOwnRange() {
        val p = fixture.getJSONObject("pileupRanges")
        assertEquals(p.getJSONObject("speed").getDouble("min"), InputRules.CALLER_WPM_MIN, delta)
        assertEquals(p.getJSONObject("speed").getDouble("max"), InputRules.CALLER_WPM_MAX, delta)
        assertEquals(p.getJSONObject("minWait").getDouble("max"), InputRules.MIN_WAIT_MAX, delta)
        assertEquals(p.getJSONObject("maxWait").getDouble("max"), InputRules.MAX_WAIT_MAX, delta)
        for (c in p.getJSONArray("clamp").objects()) {
            val input = c.getDouble("input")
            val got = when (val f = c.getString("field")) {
                "minSpeed", "maxSpeed" -> InputRules.clampCallerWpm(input)
                "minWait" -> InputRules.clampMinWait(input)
                "maxWait" -> InputRules.clampMaxWait(input)
                else -> error("unknown field $f")
            }
            assertEquals("${c.getString("field")} $input", c.getDouble("value"), got, delta)
        }
    }

    @Test
    fun effectiveRangesFollowTheFixture() {
        val p = fixture.getJSONObject("pileupRanges")
        for (c in p.getJSONArray("effectiveSpeed").objects()) {
            val (lo, hi) = InputRules.effectiveSpeedRange(c.getDouble("min"), c.getDouble("max"))
            assertEquals(c.toString(), c.getDouble("lo"), lo, delta)
            assertEquals(c.toString(), c.getDouble("hi"), hi, delta)
        }
        for (c in p.getJSONArray("effectiveWait").objects()) {
            val (lo, hi) = InputRules.effectiveWaitRange(c.getDouble("min"), c.getDouble("max"))
            assertEquals(c.toString(), c.getDouble("lo"), lo, delta)
            assertEquals(c.toString(), c.getDouble("hi"), hi, delta)
        }
    }

    @Test
    fun callsignInputBecomesTheStoredValue() {
        val s = fixture.getJSONObject("callsign")
        assertEquals(s.getString("placeholder"), InputRules.CALLSIGN_PLACEHOLDER)
        assertEquals(s.getInt("maxLength"), InputRules.CALLSIGN_MAX_LENGTH)
        for (c in s.getJSONArray("cases").objects()) {
            assertEquals("input \"${c.getString("input")}\"", c.getString("stored"), InputRules.storedCallsign(c.getString("input")))
        }
    }
}
