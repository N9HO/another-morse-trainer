package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Morse Defender rules (#188), held to the same expectations as the Swift
 * MorseKitCheck "Morse Defender" section: the two engines are twins. The
 * difficulty table, scoring, ramp, callsign shapes and a scripted scenario are
 * pinned by `fixtures/defender.json`, the same file the Swift harness reads.
 */
class DefenderTest {

    private val pool = listOf('K', 'M', 'R', 'S', 'U')

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("defender.json")
        assertNotNull("fixtures/defender.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun difficultyOf(name: String): InvadersDifficulty = when (name) {
        "relaxed" -> InvadersDifficulty.RELAXED
        "normal" -> InvadersDifficulty.NORMAL
        "fast" -> InvadersDifficulty.FAST
        else -> throw AssertionError("unknown difficulty '$name' in the fixture")
    }

    @Test
    fun constantsAreTheFixtures() {
        val d = fixture.getJSONObject("derivation")
        assertEquals(d.getInt("pointsPerHit"), DefenderGame.pointsPerHit)
        assertEquals(d.getDouble("baseSpawnInterval"), DefenderGame.baseSpawnInterval, 0.0)
        assertEquals(d.getDouble("minSpawnInterval"), DefenderGame.minSpawnInterval, 0.0)
        assertEquals(d.getDouble("spawnDecay"), DefenderGame.spawnDecay, 0.0)
        assertEquals(d.getDouble("baseTravelTime"), DefenderGame.baseTravelTime, 0.0)
        assertEquals(d.getDouble("minTravelTime"), DefenderGame.minTravelTime, 0.0)
        assertEquals(d.getDouble("travelDecay"), DefenderGame.travelDecay, 0.0)
        assertEquals(d.getInt("maxConcurrent"), DefenderGame.maxConcurrent)
        assertEquals(d.getInt("startAssets"), DefenderGame.defaultStartAssets)
        assertEquals(d.getInt("maxAssets"), DefenderGame.defaultMaxAssets)
        assertEquals(d.getInt("hitsPerWave"), DefenderGame.defaultHitsPerWave)
        assertEquals(d.getInt("columns"), DefenderGame.defaultColumns)
        assertEquals(d.getDouble("minWpm"), DefenderGame.minWpm, 0.0)
        assertEquals(d.getDouble("startOffset"), DefenderGame.rampStartOffset, 0.0)
        assertEquals(d.getDouble("step"), DefenderGame.rampStep, 0.0)
        assertEquals(d.getInt("hitsPerStep"), DefenderGame.hitsPerRampStep)
    }

    @Test
    fun timingsFollowTheDifficultyTable() {
        val rows = fixture.getJSONObject("difficultyTable").getJSONArray("rows")
        assertTrue(rows.length() > 0)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val wave = row.getInt("wave")
            val difficulty = difficultyOf(row.getString("difficulty"))
            val label = "wave $wave ${row.getString("difficulty")}"
            assertEquals("$label spawn", row.getDouble("spawnInterval"), DefenderGame.spawnInterval(wave, difficulty), 1e-5)
            assertEquals("$label travel", row.getDouble("travelTime"), DefenderGame.travelTime(wave, difficulty), 1e-5)
            assertEquals("$label concurrent", row.getInt("concurrent"), DefenderGame.concurrent(wave))
        }
    }

    @Test
    fun comboMultiplierFollowsTheFixtureTable() {
        val rows = fixture.getJSONArray("multiplierTable")
        assertTrue(rows.length() > 0)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            assertEquals("combo ${row.getInt("combo")}", row.getInt("multiplier"), DefenderGame.multiplier(row.getInt("combo")))
        }
    }

    @Test
    fun rampStartFollowsTheFixtureTable() {
        val table = fixture.getJSONArray("rampStartTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val wpm = row.getDouble("characterWpm")
            assertEquals("start at $wpm WPM", row.getDouble("startWpm"), DefenderGame.rampStart(wpm), 1e-9)
            val config = DefenderGame.Config(characters = listOf('K', 'M'), characterWpm = wpm)
            assertEquals("Config.startWpm at $wpm", row.getDouble("startWpm"), config.startWpm, 1e-9)
            assertEquals("Config.targetWpm at $wpm", row.getDouble("targetWpm"), config.targetWpm, 1e-9)
            assertEquals("a new game opens at the start", row.getDouble("startWpm"), DefenderGame(config, Random(1)).currentWpm, 1e-9)
        }
    }

    @Test
    fun sendTimingHonoursFarnsworth() {
        val cases = fixture.getJSONArray("sendTiming")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val farnsworth = if (c.isNull("farnsworthWpm")) null else c.getDouble("farnsworthWpm")
            val t = DefenderGame.sendTiming(c.getDouble("wpm"), farnsworth)
            assertEquals(c.getString("name"), c.getDouble("characterWpm"), t.wpm, 1e-9)
            assertEquals(c.getString("name"), c.getDouble("effectiveWpm"), t.effectiveWpm, 1e-9)
        }
    }

    @Test
    fun syntheticCallsignsFollowTheShapeRules() {
        val cases = fixture.getJSONObject("callsigns").getJSONArray("synthetic")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val chars = c.getString("pool").toList()
            val alphabet = c.getString("alphabet")
            assertEquals(name, alphabet, DefenderGame.callsignAlphabet(chars).joinToString(""))
            val digitAt = c.getInt("digitAt")
            val hasLetters = alphabet.any { it.isLetter() }
            val rng = Random(11)
            repeat(50) {
                val token = DefenderGame.syntheticCallsign(chars, rng)
                assertEquals("$name: '$token' length", c.getInt("length"), token.length)
                assertTrue("$name: '$token' spelled from the alphabet", token.all { it in alphabet })
                token.forEachIndexed { idx, ch ->
                    when {
                        idx == digitAt -> assertTrue("$name: '$token' digit at $idx", ch.isDigit())
                        digitAt >= 0 && hasLetters -> assertTrue("$name: '$token' letter at $idx", ch.isLetter())
                        digitAt < 0 -> assertFalse("$name: '$token' no digit at $idx", ch.isDigit())
                    }
                }
                if (c.has("token")) assertEquals(name, c.getString("token"), token)
            }
        }
    }

    @Test
    fun realCallsignsAreTheEverydayShapes() {
        val real = fixture.getJSONObject("callsigns").getJSONObject("real")
        val indexes = real.getJSONArray("digitIndexes").let { arr -> List(arr.length()) { arr.getInt(it) } }
        val rng = Random(5)
        repeat(real.getInt("draws")) {
            val call = DefenderGame.realCallsign(rng)
            assertTrue("'$call' length", call.length in real.getInt("minLength")..real.getInt("maxLength"))
            val digits = call.withIndex().filter { it.value.isDigit() }
            assertEquals("'$call' digits", real.getInt("digitCount"), digits.size)
            assertTrue("'$call' digit position", digits.all { it.index in indexes })
            assertTrue("'$call' alphanumeric", call.all { it.isLetterOrDigit() })
        }
    }

    @Test
    fun aNewGamePlacesTheStartingAssetsAndLaunchesOnTheInterval() {
        val d = fixture.getJSONObject("derivation")
        val g = DefenderGame(DefenderGame.Config(characters = pool), Random(3))
        assertEquals(d.getInt("startAssets"), g.assets.size)
        assertEquals(d.getInt("startAssets"), g.liveAssets)
        assertEquals(g.assets.size, g.assets.map { it.callsign }.toSet().size)
        assertTrue(g.attackers.isEmpty())

        val interval = g.spawnInterval
        assertTrue(g.advance(interval - 0.01).isEmpty())
        assertTrue(g.attackers.isEmpty())
        val events = g.advance(0.02)
        assertEquals(1, events.size)
        assertTrue(events[0] is DefenderEvent.Launched)
        assertEquals(1, g.attackers.size)
        val a = g.attackers[0]
        assertTrue(g.assets.any { it.id == a.targetId && it.callsign == a.callsign })
        // Born a hair down: the step overshot the launch time by 0.01 s.
        assertEquals(0.01 / g.travelTime, a.progress, 1e-9)
        // Wave 1 holds one attacker in flight.
        assertTrue(g.advance(interval * 2).none { it is DefenderEvent.Launched })
        assertEquals(1, g.attackers.size)
    }

    @Test
    fun gameFollowsTheScriptedScenario() {
        val sc = fixture.getJSONObject("scenario")
        val g = DefenderGame(
            DefenderGame.Config(characters = pool, characterWpm = sc.getDouble("characterWpm")),
            Random(7)
        )
        assertEquals(sc.getDouble("startWpm"), g.currentWpm, 1e-9)
        assertEquals(sc.getDouble("targetWpm"), g.config.targetWpm, 1e-9)
        val events = sc.getJSONArray("events")
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val kind = ev.getString("event")
            repeat(ev.optInt("times", 1)) {
                when (kind) {
                    "hit" -> {
                        while (g.attackers.isEmpty()) g.advance(0.05)
                        val nearest = g.nearestArrival ?: throw AssertionError("event $i: no attacker to route against")
                        assertTrue("event $i: the route hit", g.route(nearest.targetId).isHit)
                    }
                    "wrongRoute" -> {
                        val open = g.assets.firstOrNull { it.isAlive && !g.isTargeted(it.id) }
                            ?: throw AssertionError("event $i: no untargeted asset to waste a shot on")
                        assertFalse("event $i: the wasted shot missed", g.route(open.id).isHit)
                    }
                    "strike" -> {
                        var struck = 0
                        while (struck == 0) struck = g.advance(0.05).count { it is DefenderEvent.Struck }
                        assertEquals("event $i: one strike at a time", 1, struck)
                    }
                    else -> fail("unknown event '$kind' in the fixture")
                }
            }
            val label = "after event $i ($kind)"
            assertEquals("$label score", ev.getInt("score"), g.score)
            assertEquals("$label combo", ev.getInt("combo"), g.combo)
            assertEquals("$label wave", ev.getInt("wave"), g.wave)
            assertEquals("$label assets", ev.getInt("assets"), g.assets.size)
            assertEquals("$label live", ev.getInt("live"), g.liveAssets)
            assertEquals("$label currentWpm", ev.getDouble("currentWpm"), g.currentWpm, 1e-9)
        }
        assertFalse(g.isOver)
        assertEquals(sc.getInt("hits"), g.hits)
        assertEquals(sc.getInt("misses"), g.misses)
        assertEquals(sc.getInt("bestCombo"), g.bestCombo)
        assertEquals(sc.getDouble("bestWpm"), g.bestWpm, 1e-9)
        assertEquals("every callsign placed is distinct", g.assets.size, g.assets.map { it.callsign }.toSet().size)
    }

    @Test
    fun typedCallsignRoutesLikeATap() {
        val g = DefenderGame(DefenderGame.Config(characters = listOf('K', 'M')), Random(9))
        while (g.attackers.isEmpty()) g.advance(0.05)
        val aimed = g.nearestArrival!!
        assertFalse(g.route("ZZZZ").isHit)
        assertEquals(1, g.misses)
        assertEquals(1, g.attackers.size)
        val hit = g.route(aimed.callsign.lowercase())
        assertTrue(hit.isHit)
        assertEquals(aimed.id, hit.attacker!!.id)
        assertEquals(1, g.hits)
        assertEquals(listOf('K', 'M'), g.keyboardPool)
        assertEquals(36, DefenderGame(DefenderGame.Config(characters = emptyList(), callsigns = InvadersCharacterSet.FULL), Random(1)).keyboardPool.size)
    }

    @Test
    fun losingEveryAssetEndsTheGame() {
        val strikesToLose = fixture.getJSONObject("loss").getInt("strikesToLose")
        val g = DefenderGame(DefenderGame.Config(characters = listOf('K', 'M', 'R')), Random(4))
        var strikes = 0
        var over = false
        var guardSteps = 0
        while (!over && guardSteps < 200_000) {
            for (e in g.advance(0.1)) {
                if (e is DefenderEvent.Struck) strikes += 1
                if (e is DefenderEvent.GameOver) over = true
            }
            guardSteps += 1
        }
        assertTrue(over)
        assertTrue(g.isOver)
        assertEquals(strikesToLose, strikes)
        assertEquals(0, g.liveAssets)
        assertTrue(g.attackers.isEmpty())
        // A finished game ignores time and routes.
        assertTrue(g.advance(10.0).isEmpty())
        assertFalse(g.route(g.assets[0].id).isHit)
        assertEquals(strikesToLose, g.misses)
    }
}
