package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * CW Asteroids rules (#189), held to the same expectations as the Swift
 * MorseKitCheck "CW Asteroids" section through `fixtures/asteroids.json`, the
 * same file the Swift harness reads: the two engines are twins.
 */
class AsteroidsTest {

    private val pool = listOf('K', 'M', 'R', 'S')

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("asteroids.json")
        assertNotNull("fixtures/asteroids.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun difficulty(name: String): InvadersDifficulty = when (name) {
        "relaxed" -> InvadersDifficulty.RELAXED
        "normal" -> InvadersDifficulty.NORMAL
        "fast" -> InvadersDifficulty.FAST
        else -> throw IllegalArgumentException("unknown difficulty '$name' in the fixture")
    }

    private fun game(seed: Int = 1, input: AsteroidsInput = AsteroidsInput.SEND) =
        AsteroidsGame(AsteroidsGame.Config(characters = pool, input = input), rng = Random(seed))

    // Constants and tables, against fixtures/asteroids.json.

    @Test
    fun constantsAreTheFixtures() {
        val c = fixture.getJSONObject("derivation").getJSONObject("constants")
        assertEquals(c.getInt("pointsPerCharacter"), AsteroidsGame.pointsPerCharacter)
        assertEquals(c.getInt("lives"), AsteroidsGame.defaultLives)
        assertEquals(c.getInt("hitsPerWave"), AsteroidsGame.defaultHitsPerWave)
        assertEquals(c.getInt("maxOnField"), AsteroidsGame.maxOnField)
        assertEquals(c.getInt("sectors"), AsteroidsGame.sectors)
        assertEquals(c.getDouble("baseSpawnInterval"), AsteroidsGame.baseSpawnInterval, 0.0)
        assertEquals(c.getDouble("minSpawnInterval"), AsteroidsGame.minSpawnInterval, 0.0)
        assertEquals(c.getDouble("spawnTightening"), AsteroidsGame.spawnTightening, 0.0)
        assertEquals(c.getDouble("baseApproachTime"), AsteroidsGame.baseApproachTime, 0.0)
        assertEquals(c.getDouble("minApproachTime"), AsteroidsGame.minApproachTime, 0.0)
        assertEquals(c.getDouble("approachTightening"), AsteroidsGame.approachTightening, 0.0)
        assertEquals(c.getDouble("splitSpread"), AsteroidsGame.splitSpread, 0.0)
        assertEquals(c.getInt("wordWaveStart"), AsteroidsGame.wordWaveStart)
        assertEquals(c.getDouble("wordChancePerWave"), AsteroidsGame.wordChancePerWave, 0.0)
        assertEquals(c.getDouble("maxWordChance"), AsteroidsGame.maxWordChance, 0.0)
        assertEquals(c.getInt("wordRank"), AsteroidsGame.wordRank)
        assertEquals(c.getInt("minWordLength"), AsteroidsGame.minWordLength)
        assertEquals(c.getInt("maxWordLength"), AsteroidsGame.maxWordLength)
        assertEquals(c.getDouble("sendBufferTimeout"), AsteroidsGame.sendBufferTimeout, 0.0)
        val r = fixture.getJSONObject("derivation").getJSONObject("ramp")
        assertEquals(r.getDouble("minWpm"), AsteroidsGame.minWpm, 0.0)
        assertEquals(r.getDouble("startOffset"), AsteroidsGame.rampStartOffset, 0.0)
        assertEquals(r.getDouble("step"), AsteroidsGame.rampStep, 0.0)
        assertEquals(r.getInt("hitsPerStep"), AsteroidsGame.hitsPerRampStep)
        val scale = fixture.getJSONObject("derivation").getJSONObject("timeScale")
        assertEquals(scale.getDouble("relaxed"), InvadersDifficulty.RELAXED.timeScale, 0.0)
        assertEquals(scale.getDouble("normal"), InvadersDifficulty.NORMAL.timeScale, 0.0)
        assertEquals(scale.getDouble("fast"), InvadersDifficulty.FAST.timeScale, 0.0)
    }

    @Test
    fun timingTableMatchesTheFixture() {
        val table = fixture.getJSONArray("timingTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val d = difficulty(row.getString("difficulty"))
            val wave = row.getInt("wave")
            assertEquals("spawn interval, wave $wave ${row.getString("difficulty")}", row.getDouble("spawnInterval"), AsteroidsGame.spawnInterval(wave, d), 1e-6)
            assertEquals("approach time, wave $wave ${row.getString("difficulty")}", row.getDouble("approachTime"), AsteroidsGame.approachTime(wave, d), 1e-6)
        }
    }

    @Test
    fun multiplierAndWordChanceTablesMatchTheFixture() {
        val m = fixture.getJSONArray("multiplierTable")
        for (i in 0 until m.length()) {
            val row = m.getJSONObject(i)
            assertEquals("multiplier at combo ${row.getInt("combo")}", row.getInt("multiplier"), AsteroidsGame.multiplier(row.getInt("combo")))
        }
        val w = fixture.getJSONArray("wordChanceTable")
        for (i in 0 until w.length()) {
            val row = w.getJSONObject(i)
            assertEquals("word chance at wave ${row.getInt("wave")}", row.getDouble("chance"), AsteroidsGame.wordChance(row.getInt("wave")), 1e-9)
        }
    }

    @Test
    fun rampStartFollowsTheFixtureTable() {
        val table = fixture.getJSONArray("startTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val wpm = row.getDouble("characterWpm")
            assertEquals("start at $wpm WPM", row.getDouble("startWpm"), AsteroidsGame.rampStart(wpm), 1e-9)
            val config = AsteroidsGame.Config(characters = listOf('K'), input = AsteroidsInput.COPY, characterWpm = wpm)
            assertEquals("Config.startWpm at $wpm", row.getDouble("startWpm"), config.startWpm, 1e-9)
            assertEquals("Config.targetWpm at $wpm", row.getDouble("targetWpm"), config.targetWpm, 1e-9)
            assertEquals("a new game opens at the start", row.getDouble("startWpm"), AsteroidsGame(config, Random(1)).currentWpm, 1e-9)
        }
    }

    @Test
    fun wordPoolFilterKeepsTheFixturesWords() {
        val wf = fixture.getJSONObject("wordFilter")
        val words = wf.getJSONArray("words").let { arr -> List(arr.length()) { arr.getString(it) } }
        val kept = wf.getJSONArray("kept").let { arr -> List(arr.length()) { arr.getString(it) } }
        val pool = wf.getString("pool").toList()
        assertEquals(kept, AsteroidsGame.wordPool(words, pool))
        assertEquals(kept, AsteroidsGame(AsteroidsGame.Config(characters = pool, words = words), Random(1)).wordPool)
    }

    @Test
    fun aWordSplitsIntoFragmentsPerTheFixture() {
        val cases = fixture.getJSONObject("split").getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val sc = cases.getJSONObject(i)
            val g = AsteroidsGame(AsteroidsGame.Config(characters = listOf('K')), Random(1))
            val label = sc.getString("label")
            val parent = g.place(label, sc.getDouble("angle"), sc.getDouble("progress"))
            var shot = AsteroidsShot(AsteroidsOutcome.IGNORED)
            for (ch in label) shot = g.send(ch)
            assertTrue("$label hits", shot.isHit)
            assertEquals(parent.id, shot.asteroid!!.id)
            val expected = sc.getJSONArray("fragments")
            assertEquals("$label fragment count", expected.length(), shot.fragments.size)
            for (k in 0 until expected.length()) {
                val e = expected.getJSONObject(k)
                val f = shot.fragments[k]
                assertEquals("$label fragment $k label", e.getString("label"), f.label)
                assertEquals("$label fragment $k angle", e.getDouble("angle"), f.angle, 1e-9)
                assertEquals("$label fragment $k progress", sc.getDouble("progress"), f.progress, 1e-9)
                assertTrue(f.isFragment)
                assertEquals(g.approachTime, f.approachTime, 1e-9)
            }
            assertEquals(shot.fragments.map { it.id }, g.asteroids.map { it.id })
        }
    }

    @Test
    fun sendScenarioFollowsTheFixture() {
        val sc = fixture.getJSONObject("sendScenario")
        val g = AsteroidsGame(
            AsteroidsGame.Config(characters = listOf('K'), input = AsteroidsInput.SEND, hitsPerWave = sc.getInt("hitsPerWave"), characterWpm = sc.getDouble("characterWpm")),
            Random(9)
        )
        val events = sc.getJSONArray("events")
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            when (ev.getString("event")) {
                "place" -> g.place(ev.getString("label"), ev.getDouble("angle"), ev.getDouble("progress"))
                "send" -> {
                    val shot = g.send(ev.getString("char")[0])
                    assertEquals("event $i outcome", ev.getString("outcome"), shot.outcome.name.lowercase())
                    if (ev.has("points")) assertEquals("event $i points", ev.getInt("points"), shot.points)
                    if (ev.has("expected")) assertEquals("event $i expected", ev.getString("expected")[0], shot.expected)
                    if (ev.has("chosen")) assertEquals("event $i chosen", ev.getString("chosen")[0], shot.chosen)
                    if (ev.has("fragments")) {
                        val frags = ev.getJSONArray("fragments")
                        assertEquals("event $i fragment count", frags.length(), shot.fragments.size)
                        for (k in 0 until frags.length()) {
                            val e = frags.getJSONObject(k)
                            assertEquals("event $i fragment $k label", e.getString("label"), shot.fragments[k].label)
                            assertEquals("event $i fragment $k angle", e.getDouble("angle"), shot.fragments[k].angle, 1e-9)
                            if (e.has("progress")) assertEquals("event $i fragment $k progress", e.getDouble("progress"), shot.fragments[k].progress, 1e-9)
                        }
                    }
                }
                "advance" -> g.advance(ev.getDouble("seconds"))
                else -> fail("unknown event '${ev.getString("event")}' in the fixture")
            }
            if (ev.has("buffer")) assertEquals("event $i buffer", ev.getString("buffer"), g.sendBuffer)
        }
        val f = sc.getJSONObject("final")
        assertEquals("score", f.getInt("score"), g.score)
        assertEquals("hits", f.getInt("hits"), g.hits)
        assertEquals("misses", f.getInt("misses"), g.misses)
        assertEquals("combo", f.getInt("combo"), g.combo)
        assertEquals("bestCombo", f.getInt("bestCombo"), g.bestCombo)
        assertEquals("wave", f.getInt("wave"), g.wave)
        assertEquals("lives", f.getInt("lives"), g.lives)
        assertEquals("onField", f.getInt("onField"), g.asteroids.size)
    }

    @Test
    fun copyScenarioFollowsTheFixture() {
        val sc = fixture.getJSONObject("copyScenario")
        val g = AsteroidsGame(
            AsteroidsGame.Config(
                characters = listOf('K'), input = AsteroidsInput.COPY, lives = sc.getInt("lives"),
                hitsPerWave = sc.getInt("hitsPerWave"), characterWpm = sc.getDouble("characterWpm")
            ),
            Random(7)
        )
        assertEquals(sc.getDouble("startWpm"), g.currentWpm, 1e-9)
        assertEquals(sc.getDouble("targetWpm"), g.config.targetWpm, 1e-9)
        var angle = 0.0
        fun nextAngle(): Double { angle += 0.5; return angle }
        val events = sc.getJSONArray("events")
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val kind = ev.getString("event")
            repeat(ev.optInt("times", 1)) {
                when (kind) {
                    "hit" -> {
                        if (g.armed == null) g.place("K", nextAngle(), 0.0)
                        val armed = g.armed
                        assertNotNull("event $i: nothing armed", armed)
                        assertTrue("event $i: the armed asteroid hits", g.tap(armed!!.id).isHit)
                    }
                    "wrongTap" -> {
                        if (g.armed == null) g.place("K", nextAngle(), 0.0)
                        val m = g.place("M", nextAngle(), 0.0)
                        val shot = g.tap(m.id)
                        assertEquals("event $i: a wrong tap misses", AsteroidsOutcome.MISS, shot.outcome)
                        assertEquals('M', shot.chosen)
                    }
                    "strike" -> {
                        g.place("K", nextAngle(), 0.99)
                        var struck = 0
                        var guardSteps = 0
                        while (struck == 0 && guardSteps < 100) {
                            struck = g.advance(0.05).count { it is AsteroidsEvent.Struck }
                            guardSteps += 1
                        }
                        assertEquals("event $i: one strike at a time", 1, struck)
                    }
                    else -> fail("unknown event '$kind' in the fixture")
                }
            }
            assertEquals("after event $i ($kind)", ev.getDouble("currentWpm"), g.currentWpm, 1e-9)
        }
        val f = sc.getJSONObject("final")
        assertEquals("score", f.getInt("score"), g.score)
        assertEquals("hits", f.getInt("hits"), g.hits)
        assertEquals("misses", f.getInt("misses"), g.misses)
        assertEquals("bestCombo", f.getInt("bestCombo"), g.bestCombo)
        assertEquals("wave", f.getInt("wave"), g.wave)
        assertEquals("lives", f.getInt("lives"), g.lives)
        assertEquals("bestWpm", f.getDouble("bestWpm"), g.bestWpm, 1e-9)
        assertFalse(g.isOver)
    }

    // Rules the fixture states in prose, checked directly (the Swift harness
    // checks the same ones).

    @Test
    fun spawnAppearsAtTheConfiguredIntervalFromTheRim() {
        val g = game()
        val interval = g.spawnInterval
        assertEquals(AsteroidsGame.baseSpawnInterval, interval, 1e-9)
        assertTrue(g.advance(interval - 0.01).isEmpty())
        assertTrue(g.asteroids.isEmpty())
        val events = g.advance(0.02)
        assertEquals(1, events.size)
        val spawned = events[0] as AsteroidsEvent.Spawned
        assertEquals(1, spawned.asteroid.label.length)
        assertTrue(spawned.asteroid.label[0] in pool)
        assertEquals(0.01 / g.approachTime, spawned.asteroid.progress, 1e-9)
        assertEquals(AsteroidsGame.baseApproachTime, spawned.asteroid.approachTime, 1e-9)
        assertNull("send mode arms nothing", g.armed)
    }

    @Test
    fun spawnAnglesSitOnSectorCentresAndConsecutiveSpawnsDiffer() {
        val g = game(seed = 42)
        val angles = ArrayList<Double>()
        repeat(12) {
            g.advance(g.spawnInterval)
            g.asteroids.lastOrNull()?.let { angles.add(it.angle) }
            for (a in g.asteroids.toList()) for (ch in a.label) g.send(ch)
        }
        assertEquals(12, angles.size)
        val step = 2 * PI / AsteroidsGame.sectors
        for (a in angles) {
            val s = a / step - 0.5
            assertEquals("angle $a is a sector centre", s.roundToLong().toDouble(), s, 1e-9)
        }
        angles.zipWithNext().forEach { (a, b) -> assertTrue(abs(a - b) > 1e-9) }
    }

    @Test
    fun normalisedPositionRunsFromTheRimToTheShip() {
        val rim = Asteroid(1, "K", 0.0, 0.0, 10.0)
        val ship = Asteroid(2, "K", 1.0, 1.0, 10.0)
        assertEquals(1.0, rim.x, 1e-9)
        assertEquals(0.5, rim.y, 1e-9)
        assertEquals(0.5, ship.x, 1e-9)
        assertEquals(0.5, ship.y, 1e-9)
        assertEquals(PI, rim.heading, 1e-9)
    }

    @Test
    fun aFullFieldSkipsTheSpawn() {
        val g = game(seed = 3)
        repeat(AsteroidsGame.maxOnField) { g.place("K", it.toDouble(), 0.0) }
        assertTrue(g.advance(g.spawnInterval + 0.01).none { it is AsteroidsEvent.Spawned })
        assertEquals(AsteroidsGame.maxOnField, g.asteroids.size)
    }

    @Test
    fun threeStrikesEndTheGame() {
        val g = game(seed = 4)
        var over = false
        var strikes = 0
        var guardSteps = 0
        while (!over && guardSteps < 100_000) {
            val events = g.advance(0.1)
            strikes += events.count { it is AsteroidsEvent.Struck }
            over = events.any { it is AsteroidsEvent.GameOver }
            guardSteps += 1
        }
        assertTrue(over)
        assertTrue(g.isOver)
        assertEquals(0, g.lives)
        assertEquals(3, strikes)
        assertTrue(g.asteroids.isEmpty())
        assertTrue(g.advance(10.0).isEmpty())
        assertEquals(AsteroidsOutcome.IGNORED, g.send('K').outcome)
        assertEquals(AsteroidsOutcome.IGNORED, g.tap(1).outcome)
    }

    @Test
    fun copyModeArmsOneAsteroidAndTapsResolveAgainstIt() {
        val g = AsteroidsGame(AsteroidsGame.Config(characters = listOf('K', 'M'), input = AsteroidsInput.COPY), Random(5))
        val k = g.place("K", 0.0, 0.0)
        val m = g.place("M", 1.0, 0.0)
        assertEquals(k.id, g.armed?.id)
        assertEquals(AsteroidsOutcome.IGNORED, g.tap(99).outcome)
        assertEquals(0, g.misses)
        val wrong = g.tap(m.id)
        assertEquals(AsteroidsOutcome.MISS, wrong.outcome)
        assertEquals('K', wrong.expected)
        assertEquals('M', wrong.chosen)
        assertEquals(1, g.misses)
        assertEquals(2, g.asteroids.size)
        val right = g.tap(k.id)
        assertTrue(right.isHit)
        assertEquals(m.id, right.cued?.id)
        assertEquals(m.id, g.armed?.id)
        assertEquals(1, g.asteroids.size)
        val k2 = g.place("K", 2.0, 0.0)
        g.tap(m.id)
        assertEquals(k2.id, g.armed?.id)
        val k3 = g.place("K", 3.0, 0.0)
        assertEquals(k2.id, g.armed?.id)
        assertTrue("any asteroid carrying the armed label is a hit", g.tap(k3.id).isHit)
        assertEquals(k2.id, g.armed?.id)
    }

    @Test
    fun sameSeedGivesTheSameSpawnSequence() {
        fun sequence(seed: Int): List<String> {
            val g = game(seed = seed)
            val out = ArrayList<String>()
            repeat(10) {
                g.advance(g.spawnInterval)
                g.asteroids.lastOrNull()?.let { out.add("${it.label}@${it.angle}") }
                for (a in g.asteroids.toList()) for (ch in a.label) g.send(ch)
            }
            return out
        }
        assertEquals(sequence(42), sequence(42))
        assertEquals(10, sequence(42).size)
        assertTrue(sequence(42) != sequence(43))
    }
}
