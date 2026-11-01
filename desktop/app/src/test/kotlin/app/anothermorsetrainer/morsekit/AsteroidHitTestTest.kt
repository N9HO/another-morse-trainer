package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Copy-mode tap hit-testing (#350), against `fixtures/asteroid-hit-test.json`,
 * the same file the other two ports read.
 */
class AsteroidHitTestTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("asteroid-hit-test.json")
        assertNotNull("fixtures/asteroid-hit-test.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun field(): List<Asteroid> {
        val arr = fixture.getJSONArray("asteroids")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Asteroid(o.getInt("id"), o.getString("label"), o.getDouble("angleOverPi") * PI,
                o.getDouble("progress"), 10.0, isFragment = o.getBoolean("fragment"))
        }
    }

    private fun describe(t: AsteroidsTap): String = when (t) {
        is AsteroidsTap.Ship -> "ship"
        is AsteroidsTap.Rock -> "rock:${t.id}"
        is AsteroidsTap.Empty -> "none"
    }

    @Test
    fun constantsAndRadiiAreTheFixtures() {
        assertEquals(fixture.getDouble("shipReachDp"), AsteroidsHitTest.shipReachDp, 0.0)
        assertEquals(fixture.getDouble("reachPaddingDp"), AsteroidsHitTest.reachPaddingDp, 0.0)
        val arr = fixture.getJSONArray("asteroids")
        val f = field()
        for (i in f.indices) {
            assertEquals("radius of ${f[i].label}", arr.getJSONObject(i).getDouble("radiusDp"), AsteroidsHitTest.radiusDp(f[i]), 0.0)
        }
    }

    @Test
    fun asteroidsSitWhereTheFixtureSays() {
        val arr = fixture.getJSONArray("asteroids")
        val w = fixture.getDouble("width"); val h = fixture.getDouble("height")
        field().forEachIndexed { i, a ->
            val at = arr.getJSONObject(i).getJSONArray("at")
            assertEquals(at.getDouble(0), a.x * w, 1e-6)
            assertEquals(at.getDouble(1), a.y * h, 1e-6)
        }
    }

    @Test
    fun tapsLandWhereTheFixtureSays() {
        val taps = fixture.getJSONArray("taps")
        val f = field()
        assertTrue(taps.length() >= 20)
        for (i in 0 until taps.length()) {
            val t = taps.getJSONObject(i)
            val got = AsteroidsHitTest.target(
                t.getDouble("x"), t.getDouble("y"),
                fixture.getDouble("width"), fixture.getDouble("height"),
                t.getDouble("density"), f
            )
            assertEquals("tap ${i + 1} (${t.getString("note")})", t.getString("expect"), describe(got))
        }
    }

    @Test
    fun anEmptyFieldHasNothingToTap() {
        val e = fixture.getJSONObject("emptyField")
        val got = AsteroidsHitTest.target(
            e.getDouble("x"), e.getDouble("y"),
            fixture.getDouble("width"), fixture.getDouble("height"), e.getDouble("density"), emptyList()
        )
        assertEquals(e.getString("expect"), describe(got))
    }
}
