package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hear-it arcade games' one sound channel, pinned against
 * `fixtures/game-announcer.json` at the repo root - the same file the iOS
 * `MorseKitCheck` harness and the other Kotlin tree read. The expected plays
 * come from the fixture's documented rules, not from running either port.
 */
class GameAnnouncerTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("game-announcer.json")
        assertNotNull("fixtures/game-announcer.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun label(s: GameAnnouncer.Sound): String = when (s) {
        is GameAnnouncer.Sound.Arrival -> "arrival ${s.id} ${s.text}"
        is GameAnnouncer.Sound.Replay -> "replay ${s.text}"
    }

    @Test
    fun `the sound channel plays as the fixture says`() {
        val durations = fixture.getJSONObject("durations")
        val wordGap = fixture.getDouble("wordGap")
        val scenarios = fixture.getJSONArray("scenarios")
        assertTrue("fixture has no scenarios", scenarios.length() > 0)
        for (i in 0 until scenarios.length()) {
            val sc = scenarios.getJSONObject(i)
            val name = sc.getString("name")
            val announcer = GameAnnouncer()
            val played = mutableListOf<String>()
            val steps = sc.getJSONArray("steps")
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                val at = step.getDouble("at")
                when (val action = step.getString("do")) {
                    "arrive" -> announcer.arrived(step.getInt("id"), step.getString("text"))
                    "replay" -> announcer.replay(step.getString("text"))
                    "forget" -> announcer.forget(step.getInt("id"))
                    "reset" -> announcer.reset()
                    "tick" -> {}
                    else -> throw AssertionError("$name: unknown step '$action'")
                }
                val sound = announcer.next(at) ?: continue
                played += "$at ${label(sound)}"
                announcer.started(at, durations.getDouble(sound.text), wordGap)
            }
            val plays = sc.getJSONArray("plays")
            val expected = (0 until plays.length()).map {
                val p = plays.getJSONObject(it)
                "${p.getDouble("at")} ${p.getString("sound")}"
            }
            assertEquals(name, expected, played)
        }
    }
}
