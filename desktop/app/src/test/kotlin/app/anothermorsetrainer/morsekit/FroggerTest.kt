package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * CW Frogger rules (#190), held to the same expectations as the Swift
 * MorseKitCheck "CW Frogger" sections: the two engines are twins. The board,
 * speeds, scoring, label stages, ramp and a scripted crossing are pinned by
 * `fixtures/frogger.json`, the same file the Swift harness reads; the rules
 * the fixture leaves to the random generator are checked over many seeds.
 */
class FroggerTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("frogger.json")
        assertNotNull("fixtures/frogger.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun kind(event: FroggerEvent): String? = when (event) {
        FroggerEvent.Hopped -> "hopped"
        is FroggerEvent.Cue -> "cue"
        is FroggerEvent.Passed -> "passed"
        is FroggerEvent.Landed -> "landed"
        is FroggerEvent.Squashed -> "squashed"
        is FroggerEvent.Sank -> "sank"
        is FroggerEvent.Drowned -> "drowned"
        is FroggerEvent.Crossed -> "crossed"
        FroggerEvent.GameOver -> "gameOver"
        is FroggerEvent.Entered -> null
    }

    private fun difficulty(name: String): InvadersDifficulty = InvadersDifficulty.valueOf(name.uppercase())

    // Against fixtures/frogger.json.

    @Test
    fun boardAndScoringConstantsAreTheFixtures() {
        val d = fixture.getJSONObject("derivation")
        assertEquals(d.getInt("rows"), FroggerGame.rows)
        assertEquals(d.getInt("startRow"), FroggerGame.startRow)
        assertEquals(d.getInt("medianRow"), FroggerGame.medianRow)
        assertEquals(d.getInt("goalRow"), FroggerGame.goalRow)
        assertEquals(d.getInt("columns"), FroggerGame.columns)
        assertEquals(d.getDouble("frogHalfWidth"), FroggerGame.frogHalfWidth, 1e-9)
        assertEquals(d.getDouble("waveSpeedGrowth"), FroggerGame.waveSpeedGrowth, 1e-9)
        assertEquals(d.getDouble("maxLaneSpeed"), FroggerGame.maxLaneSpeed, 1e-9)
        assertEquals(d.getInt("pointsPerHop"), FroggerGame.pointsPerHop)
        assertEquals(d.getInt("pointsPerDecision"), FroggerGame.pointsPerDecision)
        assertEquals(d.getInt("pointsPerCrossing"), FroggerGame.pointsPerCrossing)
        assertEquals(d.getInt("memoryFromWave"), FroggerGame.memoryFromWave)
        assertEquals(d.getInt("hiddenFromWave"), FroggerGame.hiddenFromWave)
    }

    @Test
    fun rampConstantsAreTheFixtures() {
        val d = fixture.getJSONObject("derivation")
        assertEquals(d.getDouble("minWpm"), FroggerGame.minWpm, 0.0)
        assertEquals(d.getDouble("rampStartOffset"), FroggerGame.rampStartOffset, 0.0)
        assertEquals(d.getDouble("rampStep"), FroggerGame.rampStep, 0.0)
        assertEquals(d.getInt("decisionsPerStep"), FroggerGame.decisionsPerRampStep)
    }

    @Test
    fun laneLayoutIsTheFixtures() {
        val lanes = fixture.getJSONArray("lanes")
        assertEquals(lanes.length(), FroggerGame.lanes.size)
        for (i in 0 until lanes.length()) {
            val expected = lanes.getJSONObject(i)
            val lane = FroggerGame.lanes[i]
            assertEquals(expected.getInt("row"), lane.row)
            assertEquals(expected.getString("kind"), lane.kind.name.lowercase())
            assertEquals(expected.getInt("direction"), lane.direction)
            assertEquals(expected.getInt("count"), lane.count)
            assertEquals(expected.getDouble("width"), lane.width, 1e-9)
            assertEquals(expected.getDouble("baseSpeed"), lane.baseSpeed, 1e-9)
        }
        val scales = fixture.getJSONObject("difficultyTimeScale")
        for (d in InvadersDifficulty.entries) {
            assertEquals(d.name, scales.getDouble(d.name.lowercase()), d.timeScale, 1e-9)
        }
    }

    @Test
    fun laneSpeedsFollowTheFixtureTable() {
        val table = fixture.getJSONArray("speedTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val got = FroggerGame.laneSpeed(row.getDouble("baseSpeed"), row.getInt("wave"), difficulty(row.getString("difficulty")))
            assertEquals("base ${row.getDouble("baseSpeed")} wave ${row.getInt("wave")} ${row.getString("difficulty")}", row.getDouble("speed"), got, 1e-6)
        }
    }

    @Test
    fun multiplierAndLabelStageFollowTheFixture() {
        val mult = fixture.getJSONArray("multiplier")
        for (i in 0 until mult.length()) {
            val row = mult.getJSONObject(i)
            assertEquals("combo ${row.getInt("combo")}", row.getInt("multiplier"), FroggerGame.multiplier(row.getInt("combo")))
        }
        val stages = fixture.getJSONArray("labelStage")
        for (i in 0 until stages.length()) {
            val row = stages.getJSONObject(i)
            assertEquals("wave ${row.getInt("wave")}", row.getString("stage"), FroggerGame.labelStage(row.getInt("wave")).name.lowercase())
        }
    }

    @Test
    fun rampStartFollowsTheFixtureTable() {
        val table = fixture.getJSONArray("rampStartTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val wpm = row.getDouble("characterWpm")
            assertEquals("start at $wpm WPM", row.getDouble("startWpm"), FroggerGame.rampStart(wpm), 1e-9)
            val config = FroggerGame.Config(characters = listOf('K'), characterWpm = wpm)
            assertEquals("Config.startWpm at $wpm", row.getDouble("startWpm"), config.startWpm, 1e-9)
            assertEquals("Config.targetWpm at $wpm", row.getDouble("targetWpm"), config.targetWpm, 1e-9)
            assertEquals("a new game opens at the start", row.getDouble("startWpm"), FroggerGame(config, Random(1)).currentWpm, 1e-9)
        }
    }

    @Test
    fun scriptedCrossingFollowsTheFixture() {
        val sc = fixture.getJSONObject("scenario")
        val pool = sc.getString("pool").toList()
        val cueChar = pool[0]
        val g = FroggerGame(
            FroggerGame.Config(
                characters = pool, difficulty = difficulty(sc.getString("difficulty")),
                lives = sc.getInt("lives"), characterWpm = sc.getDouble("characterWpm")
            ),
            rng = Random(9)
        )
        assertEquals(FroggerFrog(FroggerGame.startRow, 0.5), g.frog)
        assertEquals(sc.getDouble("startWpm"), g.currentWpm, 1e-9)
        assertEquals(sc.getDouble("targetWpm"), g.config.targetWpm, 1e-9)
        assertEquals(mapOf(sc.getInt("firstCueRow") to cueChar), g.cues)
        assertEquals(FroggerGame.lanes.sumOf { it.count }, g.objects.size)
        assertTrue(g.objects.all { it.character == cueChar })

        val steps = sc.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val events: List<FroggerEvent>
            val what: String
            if (step.has("advance")) {
                events = g.advance(step.getDouble("advance"))
                what = "advance ${step.getDouble("advance")}"
            } else if (step.has("move")) {
                events = g.move(FroggerDirection.valueOf(step.getString("move").uppercase()))
                what = "move ${step.getString("move")}"
            } else {
                fail("step $i: unknown step in the fixture"); return
            }
            val tag = "step $i ($what)"
            val expectedEvents = step.getJSONArray("events").let { arr -> List(arr.length()) { arr.getString(it) } }
            assertEquals(tag, expectedEvents, events.mapNotNull { kind(it) })
            for (e in events) {
                if (e is FroggerEvent.Cue) {
                    assertEquals("$tag: cue row", g.frog.row + 1, e.row)
                    assertEquals("$tag: cue character", cueChar, e.character)
                }
            }
            if (step.has("enteredCount")) {
                assertEquals("$tag: entered", step.getInt("enteredCount"), events.count { it is FroggerEvent.Entered })
            }
            assertEquals("$tag: row", step.getInt("row"), g.frog.row)
            assertEquals("$tag: x", step.getDouble("x"), g.frog.x, 1e-5)
            assertEquals("$tag: score", step.getInt("score"), g.score)
            assertEquals("$tag: lives", step.getInt("lives"), g.lives)
            assertEquals("$tag: wave", step.getInt("wave"), g.wave)
            assertEquals("$tag: combo", step.getInt("combo"), g.combo)
            assertEquals("$tag: currentWpm", step.getDouble("currentWpm"), g.currentWpm, 1e-9)
        }
        val totals = sc.getJSONObject("final")
        assertEquals(totals.getInt("decisions"), g.decisions)
        assertEquals(totals.getInt("misses"), g.misses)
        assertEquals(totals.getDouble("bestWpm"), g.bestWpm, 1e-9)
        assertEquals(totals.getBoolean("isOver"), g.isOver)
        assertEquals(totals.getInt("decisions").toDouble() / (totals.getInt("decisions") + totals.getInt("misses")), g.accuracy, 1e-9)
    }

    // Rules the fixture leaves to the random generator.

    private fun middle(g: FroggerGame, row: Int): FroggerObject? =
        g.objects.firstOrNull { it.row == row && abs(it.x - 0.5) < 1e-9 }

    @Test
    fun wrongVehicleSquashesAndCuedOneIsPassedThrough() {
        // A two-character pool: whatever the cue is, the other label is wrong.
        // Objects sit at 1/6, 1/2 and 5/6 until time moves, so the frog at 0.5
        // is on the middle object of every lane it hops into.
        var squashSeen = false
        var passSeen = false
        for (seed in 1..40) {
            val g = FroggerGame(FroggerGame.Config(characters = listOf('K', 'M')), rng = Random(seed))
            val cue = g.nextCue!!
            val vehicle = middle(g, 1)!!
            val events = g.move(FroggerDirection.UP)
            if (vehicle.character == cue) {
                passSeen = true
                assertTrue(events.contains(FroggerEvent.Passed(vehicle, FroggerGame.pointsPerDecision)))
                assertEquals(3, g.lives)
                assertEquals(1, g.combo)
            } else {
                squashSeen = true
                assertTrue(events.contains(FroggerEvent.Squashed(vehicle, cue)))
                assertEquals(2, g.lives)
                assertEquals(0, g.frog.row)
                assertEquals(1, g.cues.size)
            }
        }
        assertTrue(squashSeen)
        assertTrue(passSeen)
    }

    @Test
    fun wrongLogSinksAndCuedOneIsRidden() {
        var sinkSeen = false
        var landSeen = false
        for (seed in 101..140) {
            val g = FroggerGame(FroggerGame.Config(characters = listOf('K', 'M')), rng = Random(seed))
            // Walk the road first; a squash sends the frog back, so give up on that seed.
            var atMedian = false
            for (i in 0 until 4) {
                val ev = g.move(FroggerDirection.UP)
                if (ev.any { it is FroggerEvent.Squashed }) break
                if (g.frog.row == FroggerGame.medianRow) atMedian = true
            }
            if (!atMedian) continue
            val cue = g.nextCue!!
            val log = middle(g, 5)!!
            val landing = g.move(FroggerDirection.UP)
            if (log.character == cue) {
                landSeen = true
                assertTrue(landing.any { it is FroggerEvent.Landed && it.obj.id == log.id })
                assertEquals(log.id, g.ridingId)
            } else {
                sinkSeen = true
                assertTrue(landing.contains(FroggerEvent.Sank(log, cue)))
                assertEquals(0, g.frog.row)
                assertNull(g.ridingId)
            }
        }
        assertTrue(sinkSeen)
        assertTrue(landSeen)
    }

    @Test
    fun cuedLogCarriesTheFrogAndWrapsWithIt() {
        val g = FroggerGame(FroggerGame.Config(characters = listOf('K')), rng = Random(3))
        repeat(5) { g.move(FroggerDirection.UP) }
        val before = g.frog.x
        g.advance(0.5)
        val lane5 = FroggerGame.lanes.first { it.row == 5 }
        assertEquals(5, g.frog.row)
        assertNotNull(g.ridingId)
        assertEquals(before - FroggerGame.laneSpeed(lane5.baseSpeed, 1, InvadersDifficulty.NORMAL) * 0.5, g.frog.x, 1e-9)
        val riding = g.ridingId
        g.advance(4.0)
        assertEquals(riding, g.ridingId)
        assertTrue(g.frog.x >= 0 && g.frog.x < 1)
        val log = g.objects.first { it.id == riding }
        assertEquals(0.0, FroggerGame.wrappedDistance(log.x, g.frog.x), 1e-9)
    }

    @Test
    fun passingVehicleIsCreditedOncePerOverlap() {
        val g = FroggerGame(FroggerGame.Config(characters = listOf('K')), rng = Random(4))
        g.move(FroggerDirection.UP)
        assertEquals(1, g.decisions)
        repeat(10) { g.advance(0.01) }
        assertEquals(1, g.decisions)
    }

    @Test
    fun threeDeathsEndTheGame() {
        val g = FroggerGame(FroggerGame.Config(characters = listOf('K')), rng = Random(5))
        g.advance(1.0)   // the middle log of row 5 has moved off the frog's line
        var deaths = 0
        var over = false
        repeat(3) {
            for (i in 0 until 5) {
                val ev = g.move(FroggerDirection.UP)
                if (ev.any { it is FroggerEvent.Drowned }) deaths += 1
                if (ev.contains(FroggerEvent.GameOver)) over = true
                if (g.frog.row == 0) break
            }
        }
        assertEquals(3, deaths)
        assertTrue(over)
        assertTrue(g.isOver)
        assertEquals(0, g.lives)
        // A finished game ignores hops and time.
        assertTrue(g.move(FroggerDirection.UP).isEmpty())
        assertTrue(g.advance(1.0).isEmpty())
    }

    @Test
    fun labelStagesFollowTheWaves() {
        val g = FroggerGame(FroggerGame.Config(characters = listOf('K')), rng = Random(6))
        assertEquals(FroggerLabelStage.VISIBLE, g.labelStage)
        assertTrue((1..7).all { g.isLabelVisible(it) })
        repeat(2) { repeat(8) { g.move(FroggerDirection.UP) } }
        assertEquals(3, g.wave)
        assertEquals(FroggerLabelStage.MEMORY, g.labelStage)
        assertFalse("lane 1 is cued, so hidden", g.isLabelVisible(1))
        assertTrue("lane 2 is not cued yet", g.isLabelVisible(2))
        repeat(2) { repeat(8) { g.move(FroggerDirection.UP) } }
        assertEquals(5, g.wave)
        assertEquals(FroggerLabelStage.HIDDEN, g.labelStage)
        assertTrue((1..7).none { g.isLabelVisible(it) })
    }

    @Test
    fun sameSeedGivesTheSameLabelsAndCue() {
        fun labels(seed: Int): String {
            val g = FroggerGame(FroggerGame.Config(characters = listOf('K', 'M', 'R', 'S')), rng = Random(seed))
            return g.objects.joinToString("") { it.character.toString() } + (g.nextCue ?: '?')
        }
        assertEquals(labels(42), labels(42))
        assertTrue(labels(42) != labels(43))
        for (seed in 1..30) {
            val g = FroggerGame(FroggerGame.Config(characters = listOf('K', 'M', 'R', 'S', 'U', 'A')), rng = Random(seed))
            assertTrue("the cue is a label lane 1 carries", g.objects.any { it.row == 1 && it.character == g.nextCue })
        }
    }
}
