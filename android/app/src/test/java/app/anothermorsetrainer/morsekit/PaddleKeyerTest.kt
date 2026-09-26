package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-screen paddle keyer (#233), pinned against
 * `fixtures/paddle-keyer.json` at the repo root — the same file the iOS
 * `MorseKitCheck` harness reads. Each case scripts paddle presses and gives,
 * per keyer mode, the elements that must be sent; the values were worked out
 * by hand from the fixture's `derivation`, not captured from either port.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class PaddleKeyerTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("paddle-keyer.json")
        assertNotNull("fixtures/paddle-keyer.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val cases: List<JSONObject> by lazy {
        val a = fixture.getJSONArray("cases")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** Pair each key-down with the key-up after it, as "dit 0-60". */
    private fun describe(edges: List<PaddleKeyer.Edge>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < edges.size) {
            val d = edges[i]
            val u = edges.getOrNull(i + 1)
            if (d.isDown && u != null && !u.isDown && u.element == d.element) {
                out += "${d.element.id} ${d.atMs.toInt()}-${u.atMs.toInt()}"
                i += 2
            } else {
                out += "unpaired ${if (d.isDown) "down" else "up"} ${d.element.id} ${d.atMs.toInt()}"
                i += 1
            }
        }
        return out
    }

    private fun expected(rows: JSONArray): List<String> = (0 until rows.length()).map {
        val r = rows.getJSONArray(it)
        "${r.getString(0)} ${r.getDouble(1).toInt() + 1}-${r.getDouble(2).toInt()}"
    }

    private fun run(case: JSONObject, mode: PaddleKeyer.Mode): Pair<List<String>, PaddleKeyer> {
        val keyer = PaddleKeyer(mode, case.getDouble("wpm"))
        val edges = mutableListOf<PaddleKeyer.Edge>()
        val events = case.getJSONArray("events")
        for (i in 0 until events.length()) {
            val e = events.getJSONArray(i)
            val element = if (e.getString(0) == "dah") PaddleKeyer.Element.DAH else PaddleKeyer.Element.DIT
            edges += keyer.paddle(element, e.getString(1) == "down", e.getDouble(2))
        }
        edges += keyer.advance(case.getDouble("untilMs"))
        return describe(edges) to keyer
    }

    private fun checkMode(mode: PaddleKeyer.Mode) {
        assertTrue("the fixture carries cases", cases.isNotEmpty())
        for (case in cases) {
            val name = case.getString("name")
            val want = expected(case.getJSONObject("expected").getJSONArray(mode.id))
            val (got, keyer) = run(case, mode)
            assertEquals("${mode.id}: $name", want, got)
            assertFalse("${mode.id}: $name — idle afterwards", keyer.isBusy)
        }
    }

    @Test
    fun iambicAMatchesTheFixture() = checkMode(PaddleKeyer.Mode.IAMBIC_A)

    @Test
    fun iambicBMatchesTheFixture() = checkMode(PaddleKeyer.Mode.IAMBIC_B)

    @Test
    fun ultimaticMatchesTheFixture() = checkMode(PaddleKeyer.Mode.ULTIMATIC)

    @Test
    fun oneUnitIs1200OverWpm() {
        assertEquals(60.0, PaddleKeyer(PaddleKeyer.Mode.IAMBIC_A, 20.0).unitMs, 0.0)
    }

    @Test
    fun modeIdsAreTheFixturesKeys() {
        assertEquals(listOf("iambicA", "iambicB", "ultimatic"), PaddleKeyer.Mode.entries.map { it.id })
        assertEquals(PaddleKeyer.Mode.IAMBIC_A, PaddleKeyer.Mode.fromId("nonsense"))
    }

    /** A screen closing mid-tone cuts it rather than leaving the key down. */
    @Test
    fun releaseAllCutsASoundingTone() {
        val k = PaddleKeyer(PaddleKeyer.Mode.IAMBIC_A, 20.0)
        k.paddle(PaddleKeyer.Element.DAH, true, 0.0)
        assertEquals(listOf(PaddleKeyer.Edge(false, 50.0, PaddleKeyer.Element.DAH)), k.releaseAll(50.0))
        assertFalse(k.isBusy)
        assertTrue(k.advance(5000.0).isEmpty())
    }
}
