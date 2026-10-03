package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Session Detail recognition chart's millisecond axis (#299), held to
 * `fixtures/chart-axis.json` — the same file the Swift harness's
 * "Recognition chart axis" section reads, so every port spaces its axis
 * labels by the same rule: a long outlier on a narrow chart widens the step
 * instead of crowding the labels together.
 */
class ChartAxisTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("chart-axis.json")
        assertNotNull("fixtures/chart-axis.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun `every fixture case gets its ceiling and step`() {
        val cases = fixture.getJSONArray("cases")
        assertTrue("the fixture has cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val maxMS = c.getInt("maxMS")
            val maxTicks = c.getInt("maxTicks")
            val scale = SessionRecord.axisScale(maxMS, maxTicks)
            assertEquals(
                "axis for ${maxMS}ms in $maxTicks ticks",
                AxisScale(c.getInt("ceilingMS"), c.getInt("stepMS")),
                scale
            )
        }
    }

    @Test
    fun `every axis fits its tick budget and starts at 0`() {
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val maxTicks = c.getInt("maxTicks")
            val ticks = SessionRecord.axisScale(c.getInt("maxMS"), maxTicks).ticks
            assertEquals(0, ticks.first())
            assertTrue("case $i: ${ticks.size - 1} intervals over $maxTicks", ticks.size - 1 <= maxOf(1, maxTicks))
        }
    }
}
