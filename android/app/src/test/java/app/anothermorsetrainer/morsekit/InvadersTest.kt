package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Morse Invaders rules (#170), held to the same expectations as the Swift
 * MorseKitCheck "Morse Invaders" section: the two engines are twins. The
 * speed ramp and the on-screen keyboard (#178) are pinned by
 * `fixtures/invaders-ramp.json`, the same file the Swift harness reads.
 */
class InvadersTest {

    private val pool = listOf('K', 'M', 'R', 'S')

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("invaders-ramp.json")
        assertNotNull("fixtures/invaders-ramp.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun game(
        difficulty: InvadersDifficulty = InvadersDifficulty.NORMAL,
        hitsPerWave: Int = 10,
        seed: Int = 1
    ) = InvadersGame(
        InvadersGame.Config(characters = pool, difficulty = difficulty, hitsPerWave = hitsPerWave),
        rng = Random(seed)
    )

    @Test
    fun spawnAppearsAtTheConfiguredInterval() {
        val g = game()
        val interval = g.spawnInterval
        assertEquals(InvadersGame.baseSpawnInterval, interval, 1e-9)
        assertTrue(g.advance(interval - 0.01).isEmpty())
        assertTrue(g.invaders.isEmpty())
        val events = g.advance(0.02)
        assertEquals(1, events.size)
        assertTrue(events[0] is InvadersEvent.Spawned)
        assertEquals(1, g.invaders.size)
        assertTrue(g.invaders[0].character in pool)
        // Born a hair past the top: the step overshot the spawn time by 0.01 s.
        assertEquals(0.01 / g.fallTime, g.invaders[0].progress, 1e-9)
        // The second one arrives one interval later, not before.
        assertTrue(g.advance(interval - 0.02).none { it is InvadersEvent.Spawned })
        assertEquals(1, g.advance(0.02).count { it is InvadersEvent.Spawned })
        assertEquals(2, g.invaders.size)
    }

    @Test
    fun correctAnswerRemovesTheLowestMatchingInvaderAndScores() {
        // A one-character pool so every invader carries the same letter.
        val g = InvadersGame(InvadersGame.Config(characters = listOf('K')), rng = Random(3))
        g.advance(g.spawnInterval)          // first invader
        g.advance(g.spawnInterval)          // second, one interval higher
        assertEquals(2, g.invaders.size)
        val lowest = g.invaders.maxByOrNull { it.progress }!!
        val shot = g.shoot('k')             // case-insensitive
        assertTrue(shot.isHit)
        assertEquals(lowest.id, shot.invader!!.id)
        assertEquals(InvadersGame.pointsPerHit, shot.points)
        assertEquals(InvadersGame.pointsPerHit, g.score)
        assertEquals(1, g.hits)
        assertEquals(1, g.combo)
        assertEquals(1, g.invaders.size)
        assertTrue(g.invaders[0].progress < lowest.progress)
    }

    @Test
    fun comboMultiplierGrows() {
        assertEquals(1, InvadersGame.multiplier(1))
        assertEquals(1, InvadersGame.multiplier(3))
        assertEquals(2, InvadersGame.multiplier(4))
        assertEquals(3, InvadersGame.multiplier(7))
        assertEquals(4, InvadersGame.multiplier(10))
        assertEquals(4, InvadersGame.multiplier(40))
    }

    @Test
    fun wrongAnswerBreaksTheComboAndCountsAMiss() {
        val g = InvadersGame(InvadersGame.Config(characters = listOf('K')), rng = Random(3))
        g.advance(g.spawnInterval)
        assertTrue(g.shoot('K').isHit)
        g.advance(g.spawnInterval)
        assertTrue(g.shoot('K').isHit)
        assertEquals(2, g.combo)
        g.advance(g.spawnInterval)
        val shot = g.shoot('Z')             // nothing on the field carries Z
        assertFalse(shot.isHit)
        assertNull(shot.invader)
        assertEquals(0, shot.points)
        assertEquals(0, g.combo)
        assertEquals(2, g.bestCombo)
        assertEquals(1, g.misses)
        assertEquals(2, g.hits)
        assertEquals(1, g.invaders.size)    // the miss shoots nothing down
        assertEquals(2.0 / 3.0, g.accuracy, 1e-9)
    }

    @Test
    fun invaderReachingTheGroundCostsALife() {
        val g = game()
        g.advance(g.spawnInterval)
        assertEquals(3, g.lives)
        val fall = g.invaders[0].fallTime
        assertEquals(InvadersGame.baseFallTime, fall, 1e-9)
        // Shoot down everything that spawns meanwhile except the first one, so
        // only it can reach the ground.
        val first = g.invaders[0].id
        var escaped: InvadersEvent.Escaped? = null
        var t = 0.0
        while (t + 0.25 < fall - 1e-9) {
            val events = g.advance(0.25)
            t += 0.25
            escaped = events.filterIsInstance<InvadersEvent.Escaped>().firstOrNull() ?: escaped
            g.invaders.filter { it.id != first }.forEach { g.shoot(it.character) }
        }
        assertNull(escaped)
        assertEquals(3, g.lives)
        val events = g.advance(0.25)
        val e = events.filterIsInstance<InvadersEvent.Escaped>().single()
        assertEquals(first, e.invader.id)
        assertEquals(1.0, e.invader.progress, 1e-9)
        assertEquals(2, g.lives)
        assertEquals(0, g.combo)
        assertEquals(1, g.misses)
        assertFalse(g.isOver)
    }

    @Test
    fun thirdLostLifeEndsTheGame() {
        // Never shoot: every invader lands. Three landings end it.
        val g = game()
        var over = false
        var escapes = 0
        var guardSteps = 0
        while (!over && guardSteps < 100_000) {
            val events = g.advance(0.1)
            escapes += events.count { it is InvadersEvent.Escaped }
            over = events.any { it is InvadersEvent.GameOver }
            guardSteps += 1
        }
        assertTrue(over)
        assertTrue(g.isOver)
        assertEquals(0, g.lives)
        assertEquals(3, escapes)
        assertTrue(g.invaders.isEmpty())
        // A finished game ignores time and shots.
        assertTrue(g.advance(10.0).isEmpty())
        assertFalse(g.shoot('K').isHit)
        assertEquals(3, g.misses)
    }

    @Test
    fun waveAdvanceTightensTheInterval() {
        val g = InvadersGame(InvadersGame.Config(characters = listOf('K'), hitsPerWave = 3), rng = Random(5))
        val wave1 = g.spawnInterval
        val fall1 = g.fallTime
        repeat(2) {
            g.advance(g.spawnInterval)
            assertFalse(g.shoot('K').waveCleared)
        }
        assertEquals(1, g.wave)
        g.advance(g.spawnInterval)
        assertTrue(g.shoot('K').waveCleared)
        assertEquals(2, g.wave)
        assertTrue(g.spawnInterval < wave1)
        assertTrue(g.fallTime < fall1)
        assertEquals(wave1 * 0.88, g.spawnInterval, 1e-9)
        assertEquals(fall1 * 0.9, g.fallTime, 1e-9)
        // Floors: a very high wave never goes below the minimums.
        assertEquals(InvadersGame.minSpawnInterval, InvadersGame.spawnInterval(60, InvadersDifficulty.NORMAL), 1e-9)
        assertEquals(InvadersGame.minFallTime, InvadersGame.fallTime(60, InvadersDifficulty.NORMAL), 1e-9)
    }

    @Test
    fun difficultyScalesTheTimings() {
        val normal = InvadersGame.spawnInterval(1, InvadersDifficulty.NORMAL)
        assertEquals(normal * 1.35, InvadersGame.spawnInterval(1, InvadersDifficulty.RELAXED), 1e-9)
        assertEquals(normal * 0.75, InvadersGame.spawnInterval(1, InvadersDifficulty.FAST), 1e-9)
        assertEquals(InvadersGame.baseFallTime * 0.75, InvadersGame.fallTime(1, InvadersDifficulty.FAST), 1e-9)
    }

    @Test
    fun sameSeedGivesTheSameSpawnSequence() {
        fun sequence(seed: Int): List<Pair<Char, Int>> {
            val g = game(seed = seed)
            val out = ArrayList<Pair<Char, Int>>()
            repeat(12) {
                g.advance(g.spawnInterval)
                out.addAll(g.invaders.takeLast(1).map { it.character to it.column })
                // Keep the field clear so nothing lands and the sequence stays pure spawn order.
                g.invaders.toList().forEach { g.shoot(it.character) }
            }
            return out
        }
        assertEquals(sequence(42), sequence(42))
        assertTrue(sequence(42) != sequence(43))
        // Consecutive spawns never share a column, and every character is from the pool.
        val s = sequence(42)
        assertNotNull(s)
        s.zipWithNext().forEach { (a, b) -> assertTrue(a.second != b.second) }
        s.forEach { assertTrue(it.first in pool) }
    }

    // Speed ramp (#178, retuned in #194), against fixtures/invaders-ramp.json.

    @Test
    fun rampConstantsAreTheFixtures() {
        val d = fixture.getJSONObject("derivation")
        assertEquals(d.getDouble("minWpm"), InvadersGame.minWpm, 0.0)
        assertEquals(d.getDouble("startOffset"), InvadersGame.rampStartOffset, 0.0)
        assertEquals(d.getDouble("stepUp"), InvadersGame.rampStepUp, 0.0)
        assertEquals(d.getDouble("stepDown"), InvadersGame.rampStepDown, 0.0)
        assertEquals(d.getInt("hitsPerStep"), InvadersGame.hitsPerRampStep)
        assertEquals(d.getInt("hold"), InvadersGame.rampHold)
    }

    @Test
    fun rampStartFollowsTheFixtureTable() {
        val table = fixture.getJSONArray("startTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val wpm = row.getDouble("characterWpm")
            assertEquals("start at $wpm WPM", row.getDouble("startWpm"), InvadersGame.rampStart(wpm), 1e-9)
            val config = InvadersGame.Config(characters = listOf('K'), characterWpm = wpm)
            assertEquals("Config.startWpm at $wpm", row.getDouble("startWpm"), config.startWpm, 1e-9)
            assertEquals("Config.targetWpm at $wpm", row.getDouble("targetWpm"), config.targetWpm, 1e-9)
            val opens = InvadersGame(config, Random(1))
            assertEquals("a new game opens at the start", row.getDouble("startWpm"), opens.currentWpm, 1e-9)
            assertEquals("a new game opens with no streak", 0, opens.rampStreak)
            assertEquals("a new game opens with nothing to settle", 0, opens.rampSettling)
        }
    }

    @Test
    fun perfectRunReachesTheTargetOnTheFixturesHit() {
        val pr = fixture.getJSONObject("perfectRun")
        val g = InvadersGame(InvadersGame.Config(characters = listOf('K'), characterWpm = pr.getDouble("characterWpm")), rng = Random(3))
        assertEquals(pr.getDouble("startWpm"), g.currentWpm, 1e-9)
        val toTarget = pr.getInt("hitsToTarget")
        repeat(toTarget - 1) {
            while (g.invaders.isEmpty()) g.advance(0.05)
            assertTrue(g.shoot('K').isHit)
        }
        assertEquals("one hit short", pr.getDouble("wpmOneHitShort"), g.currentWpm, 1e-9)
        while (g.invaders.isEmpty()) g.advance(0.05)
        assertTrue(g.shoot('K').isHit)
        assertEquals("at the target on hit $toTarget", g.config.targetWpm, g.currentWpm, 1e-9)
        assertEquals(pr.getInt("hitsPerLaterStep"), InvadersGame.rampHold + InvadersGame.hitsPerRampStep)
        assertFalse(g.isOver)
    }

    @Test
    fun rampFollowsTheScriptedScenario() {
        val sc = fixture.getJSONObject("scenario")
        val g = InvadersGame(
            InvadersGame.Config(characters = listOf('K'), lives = sc.getInt("lives"), characterWpm = sc.getDouble("characterWpm")),
            rng = Random(7)
        )
        assertEquals(sc.getDouble("startWpm"), g.currentWpm, 1e-9)
        assertEquals(sc.getDouble("targetWpm"), g.config.targetWpm, 1e-9)
        val events = sc.getJSONArray("events")
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val kind = ev.getString("event")
            repeat(ev.optInt("times", 1)) {
                when (kind) {
                    // A one-character pool: whatever is on the field is a K.
                    "hit" -> {
                        while (g.invaders.isEmpty()) g.advance(0.05)
                        assertTrue(g.shoot('K').isHit)
                    }
                    "escape" -> {
                        var landed = 0
                        while (landed == 0) landed = g.advance(0.05).count { it is InvadersEvent.Escaped }
                        assertEquals("one landing at a time", 1, landed)
                    }
                    "wrongShot" -> assertFalse(g.shoot('Z').isHit)
                    else -> fail("unknown event '$kind' in the fixture")
                }
            }
            assertEquals("after event $i ($kind)", ev.getDouble("currentWpm"), g.currentWpm, 1e-9)
            assertEquals("streak after event $i ($kind)", ev.getInt("streak"), g.rampStreak)
            assertEquals("settling after event $i ($kind)", ev.getInt("settling"), g.rampSettling)
        }
        assertEquals(sc.getDouble("bestWpm"), g.bestWpm, 1e-9)
        assertFalse(g.isOver)
    }

    @Test
    fun noRampAtOrBelowTheFloor() {
        val floor = fixture.getJSONObject("derivation").getDouble("noRampAtOrBelow")
        val g = InvadersGame(InvadersGame.Config(characters = listOf('K'), characterWpm = floor), rng = Random(2))
        assertEquals(g.config.startWpm, g.config.targetWpm, 0.0)
        repeat(12) {
            while (g.invaders.isEmpty()) g.advance(0.05)
            assertTrue(g.shoot('K').isHit)
        }
        assertEquals(floor, g.currentWpm, 1e-9)
        assertEquals(floor, g.bestWpm, 1e-9)
    }

    // Adaptive character weighting (#194), against the same fixture.

    @Test
    fun weightingConstantsAndWeightsAreTheFixtures() {
        val w = fixture.getJSONObject("weighting")
        assertEquals(w.getInt("debtPerMiss"), InvadersGame.debtPerMiss)
        assertEquals(w.getInt("maxDebt"), InvadersGame.maxMissDebt)
        assertEquals(w.getDouble("bonus"), InvadersGame.missWeightBonus, 0.0)
        val table = w.getJSONArray("weightTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            assertEquals("debt ${row.getInt("debt")}", row.getDouble("weight"), InvadersGame.spawnWeight(row.getInt("debt")), 1e-9)
        }
    }

    @Test
    fun pickLandsWhereTheFixtureSays() {
        val cases = fixture.getJSONObject("weighting").getJSONArray("pick")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val weights = c.getJSONArray("weights").let { arr -> List(arr.length()) { arr.getDouble(it) } }
            assertEquals("pick($weights, ${c.getDouble("roll")})", c.getInt("index"), InvadersGame.pick(weights, c.getDouble("roll")))
        }
    }

    @Test
    fun spawnWeightsFollowTheWeightingScenario() {
        // Labels bind to whatever the generator spawns, per the driving rules
        // in the fixture's comment.
        val sc = fixture.getJSONObject("weighting").getJSONObject("scenario")
        val g = InvadersGame(InvadersGame.Config(characters = sc.getString("pool").toList(), lives = sc.getInt("lives")), rng = Random(11))
        val labels = HashMap<String, Char>()
        fun shootAll(which: (Char) -> Boolean) {
            g.invaders.filter { which(it.character) }.forEach { assertTrue(g.shoot(it.character).isHit) }
        }
        fun landed(events: List<InvadersEvent>) = events.filterIsInstance<InvadersEvent.Escaped>().map { it.invader }
        val events = sc.getJSONArray("events")
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val kind = ev.getString("event")
            repeat(ev.optInt("times", 1)) {
                var steps = 0
                when (kind) {
                    "escape" -> {
                        val label = ev.optString("label", "")
                        val bound = labels[label]
                        if (bound != null) {
                            var done = false
                            while (!done && steps < 100_000) {
                                shootAll { it != bound }
                                val fell = landed(g.advance(0.05))
                                if (fell.any { it.character == bound }) done = true
                                else assertTrue("event $i: the wrong character landed", fell.isEmpty())
                                steps += 1
                            }
                        } else {
                            var fell = emptyList<Invader>()
                            while (fell.isEmpty() && steps < 100_000) { fell = landed(g.advance(0.05)); steps += 1 }
                            assertEquals("event $i: one landing at a time", 1, fell.size)
                            if (label.isNotEmpty()) labels[label] = fell[0].character
                        }
                    }
                    "hit" -> {
                        val c = labels[ev.getString("label")] ?: throw AssertionError("event $i: hit needs a bound label")
                        var done = false
                        while (!done && steps < 100_000) {
                            shootAll { it != c }
                            if (g.invaders.any { it.character == c }) {
                                assertTrue(g.shoot(c).isHit)
                                done = true
                            } else {
                                g.advance(0.05)
                            }
                            steps += 1
                        }
                    }
                    "wrongShot" -> {
                        if (ev.optBoolean("emptyField", false)) {
                            shootAll { true }
                            assertTrue(g.invaders.isEmpty())
                            assertFalse(g.shoot('Z').isHit)
                        } else {
                            val avoidArr = ev.optJSONArray("avoid")
                            val avoided: Set<Char> = if (avoidArr == null) emptySet() else
                                (0 until avoidArr.length()).mapNotNull { labels[avoidArr.getString(it)] }.toSet()
                            while ((g.invaders.isEmpty() || g.invaders.any { it.character in avoided }) && steps < 100_000) {
                                shootAll { it in avoided }
                                if (g.invaders.isEmpty()) g.advance(0.05)
                                steps += 1
                            }
                            val low = g.lowest ?: throw AssertionError("event $i: nothing on the field to miss")
                            if (ev.has("bind")) labels[ev.getString("bind")] = low.character
                            assertFalse(g.shoot('Z').isHit)
                        }
                    }
                    else -> fail("unknown event '$kind' in the fixture")
                }
                assertTrue("event $i: gave up waiting for the generator", steps < 100_000)
            }
            val expected = HashMap<Char, Double>()
            val weights = ev.getJSONObject("weights")
            for (label in weights.keys()) {
                val c = labels[label] ?: throw AssertionError("event $i: label $label is not bound")
                expected[c] = weights.getDouble(label)
            }
            for ((c, weight) in g.spawnWeights) {
                assertEquals("event $i ($kind): weight of $c", expected[c] ?: 1.0, weight, 1e-9)
            }
        }
        assertFalse(g.isOver)
    }

    @Test
    fun characterInDebtSpawnsMoreOften() {
        // The weights reach the spawn choice through this port's own generator.
        val ss = fixture.getJSONObject("weighting").getJSONObject("spawnShare")
        val g = InvadersGame(InvadersGame.Config(characters = ss.getString("pool").toList(), lives = ss.getInt("lives")), rng = Random(21))
        val debited = ss.getString("debited")[0]
        val spawns = ss.getInt("spawns")
        var debitedSpawns = 0
        repeat(spawns) {
            assertEquals(1, g.advance(g.spawnInterval).count { it is InvadersEvent.Spawned })
            assertEquals(1, g.invaders.size)
            val inv = g.invaders[0]
            if (inv.character == debited) {
                debitedSpawns += 1
                assertFalse(g.shoot('Z').isHit)
            }
            assertTrue(g.shoot(inv.character).isHit)
        }
        val share = debitedSpawns.toDouble() / spawns
        assertTrue("share $share under the fixture's minimum", share >= ss.getDouble("minShare"))
        assertTrue("share $share over the fixture's maximum", share <= ss.getDouble("maxShare"))
    }
    // On-screen keyboard (#178), against the same fixture.

    @Test
    fun keyboardRowsMatchTheFixture() {
        val kb = fixture.getJSONObject("keyboard")
        assertEquals(kb.getString("digits"), InvadersKeyboard.digitRow.joinToString(""))
        val letters = kb.getJSONArray("letters")
        assertEquals(letters.length(), InvadersKeyboard.letterRows.size)
        for (i in 0 until letters.length()) {
            assertEquals(letters.getString(i), InvadersKeyboard.letterRows[i].joinToString(""))
        }
        val cases = kb.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val expected = c.getJSONArray("rows").let { arr -> List(arr.length()) { arr.getString(it) } }
            val got = InvadersKeyboard.rows(c.getString("pool").toList()).map { it.joinToString("") }
            assertEquals(c.getString("name"), expected, got)
        }
    }
}
