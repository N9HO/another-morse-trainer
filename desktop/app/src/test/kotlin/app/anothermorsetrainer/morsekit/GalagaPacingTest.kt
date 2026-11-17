package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * CW Galaga's entries paced to the sound channel (#396), pinned against
 * `fixtures/galaga-pacing.json` at the repo root - the same file the iOS
 * `MorseKitCheck` harness and the other Kotlin tree read. The expected values
 * come from the fixture's documented rule, not from running either port.
 */
class GalagaPacingTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("galaga-pacing.json")
        assertNotNull("fixtures/galaga-pacing.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun `the channel is free when the fixture says`() {
        val channel = fixture.getJSONObject("channel")
        val durations = channel.getJSONObject("durations")
        val gap = channel.getDouble("gap")
        val steps = channel.getJSONArray("steps")
        assertTrue("fixture has no channel steps", steps.length() > 0)
        val announcer = GameAnnouncer()
        for (j in 0 until steps.length()) {
            val step = steps.getJSONObject(j)
            val at = step.getDouble("at")
            when (val action = step.getString("do")) {
                "arrive" -> announcer.arrived(step.getInt("id"), step.getString("text"))
                "replay" -> announcer.replay(step.getString("text"))
                "forget" -> announcer.forget(step.getInt("id"))
                "reset" -> announcer.reset()
                "next" -> {
                    val sound = announcer.next(at)
                    assertNotNull("channel step $j: nothing to start at $at", sound)
                    announcer.started(at, durations.getDouble(sound!!.text), gap)
                }
                "check" -> {}
                else -> throw AssertionError("channel step $j: unknown step '$action'")
            }
            assertEquals("channel step $j (${step.getString("do")} at $at): isFree", step.getBoolean("free"), announcer.isFree(at))
        }
    }

    @Test
    fun `galaga entries wait for the channel as the fixture says`() {
        val scenarios = fixture.getJSONArray("scenarios")
        assertTrue("fixture has no scenarios", scenarios.length() > 0)
        for (i in 0 until scenarios.length()) {
            val sc = scenarios.getJSONObject(i)
            val name = sc.getString("name")
            val g = GalagaGame(
                GalagaGame.Config(characters = listOf('K'), pacesEntriesToSound = sc.getBoolean("paced")),
                rng = Random(1)
            )
            val steps = sc.getJSONArray("steps")
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                val entered = g.advance(step.getDouble("advance"), channelFree = step.getBoolean("channelFree"))
                    .filterIsInstance<GalagaEvent.Entered>()
                val expected = step.getJSONArray("entered")
                assertEquals("$name, step $j: entries", expected.length(), entered.size)
                for (k in 0 until expected.length()) {
                    val e = expected.getJSONObject(k)
                    assertEquals("$name, step $j: entry $k id", e.getInt("id"), entered[k].enemy.id)
                    assertEquals("$name, step $j: entry $k progress", e.getDouble("progress"), entered[k].enemy.progress, 1e-9)
                }
                assertEquals("$name, step $j: released", step.getInt("released"), g.released)
            }
        }
    }
}
