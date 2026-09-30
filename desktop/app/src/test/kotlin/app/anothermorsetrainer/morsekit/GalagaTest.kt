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
 * CW Galaga rules (#187), held to the same expectations as the Swift
 * MorseKitCheck "CW Galaga" section: the two engines are twins. Constants,
 * formations, timings, the combo table, the ramp, the flight paths and a
 * scripted scenario are pinned by `fixtures/galaga.json`, the same file the
 * Swift harness reads.
 */
class GalagaTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("galaga.json")
        assertNotNull("fixtures/galaga.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun game(seed: Int = 1, lives: Int = 3, characterWpm: Double = 20.0) =
        GalagaGame(GalagaGame.Config(characters = listOf('K'), lives = lives, characterWpm = characterWpm), rng = Random(seed))

    private fun enemy(id: Int, state: GalagaEnemyState, progress: Double, row: Int = 0, column: Int = 0, fromLeft: Boolean = true) =
        GalagaEnemy(id, 'K', row, column, fromLeft, state, progress, 1.0)

    @Test
    fun scoringAndRampConstantsAreTheFixtures() {
        val d = fixture.getJSONObject("derivation")
        assertEquals(d.getInt("pointsPerHit"), GalagaGame.pointsPerHit)
        assertEquals(d.getInt("diveBonus"), GalagaGame.diveBonus)
        assertEquals(d.getInt("waveBonus"), GalagaGame.waveBonus)
        assertEquals(d.getInt("comboStep"), GalagaGame.comboStep)
        assertEquals(d.getInt("maxMultiplier"), GalagaGame.maxMultiplier)
        assertEquals(d.getInt("lives"), GalagaGame.Config(characters = listOf('K')).lives)
        assertEquals(d.getDouble("minWpm"), GalagaGame.minWpm, 0.0)
        assertEquals(d.getDouble("startOffset"), GalagaGame.rampStartOffset, 0.0)
        assertEquals(d.getDouble("step"), GalagaGame.rampStep, 0.0)
        assertEquals(d.getInt("hitsPerStep"), GalagaGame.hitsPerRampStep)
        val layout = d.getJSONObject("layout")
        assertEquals(layout.getDouble("formationTop"), GalagaPath.formationTop, 0.0)
        assertEquals(layout.getDouble("rowPitch"), GalagaPath.rowPitch, 0.0)
    }

    @Test
    fun timingConstantsAreTheFixtures() {
        val t = fixture.getJSONObject("derivation").getJSONObject("timing")
        assertEquals(t.getDouble("baseEntryInterval"), GalagaGame.baseEntryInterval, 0.0)
        assertEquals(t.getDouble("entryIntervalDecay"), GalagaGame.entryIntervalDecay, 0.0)
        assertEquals(t.getDouble("minEntryInterval"), GalagaGame.minEntryInterval, 0.0)
        assertEquals(t.getDouble("baseEntryTime"), GalagaGame.baseEntryTime, 0.0)
        assertEquals(t.getDouble("entryTimeDecay"), GalagaGame.entryTimeDecay, 0.0)
        assertEquals(t.getDouble("minEntryTime"), GalagaGame.minEntryTime, 0.0)
        assertEquals(t.getDouble("baseDiveInterval"), GalagaGame.baseDiveInterval, 0.0)
        assertEquals(t.getDouble("diveIntervalDecay"), GalagaGame.diveIntervalDecay, 0.0)
        assertEquals(t.getDouble("minDiveInterval"), GalagaGame.minDiveInterval, 0.0)
        assertEquals(t.getDouble("baseDiveTime"), GalagaGame.baseDiveTime, 0.0)
        assertEquals(t.getDouble("diveTimeDecay"), GalagaGame.diveTimeDecay, 0.0)
        assertEquals(t.getDouble("minDiveTime"), GalagaGame.minDiveTime, 0.0)
        assertEquals(t.getDouble("returnFactor"), GalagaGame.returnFactor, 0.0)
        assertEquals(t.getDouble("waveGap"), GalagaGame.waveGap, 0.0)
        val scale = t.getJSONObject("difficultyScale")
        assertEquals(scale.getDouble("relaxed"), InvadersDifficulty.RELAXED.timeScale, 0.0)
        assertEquals(scale.getDouble("normal"), InvadersDifficulty.NORMAL.timeScale, 0.0)
        assertEquals(scale.getDouble("fast"), InvadersDifficulty.FAST.timeScale, 0.0)
    }

    @Test
    fun formationsFollowTheFixtureTable() {
        val table = fixture.getJSONArray("formationTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val wave = row.getInt("wave")
            val f = GalagaGame.formation(wave)
            assertEquals("rows at wave $wave", row.getInt("rows"), f.rows)
            assertEquals("columns at wave $wave", row.getInt("columns"), f.columns)
            assertEquals("size at wave $wave", row.getInt("size"), f.size)
            assertEquals("maxDivers at wave $wave", row.getInt("maxDivers"), GalagaGame.maxDivers(wave))
        }
    }

    @Test
    fun timingsFollowTheFixtureTable() {
        val table = fixture.getJSONArray("timingTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val wave = row.getInt("wave")
            val diff = InvadersDifficulty.valueOf(row.getString("difficulty").uppercase())
            assertEquals("entryInterval wave $wave $diff", row.getDouble("entryInterval"), GalagaGame.entryInterval(wave, diff), 1e-9)
            assertEquals("entryTime wave $wave $diff", row.getDouble("entryTime"), GalagaGame.entryTime(wave, diff), 1e-9)
            assertEquals("diveInterval wave $wave $diff", row.getDouble("diveInterval"), GalagaGame.diveInterval(wave, diff), 1e-9)
            assertEquals("diveTime wave $wave $diff", row.getDouble("diveTime"), GalagaGame.diveTime(wave, diff), 1e-9)
            assertEquals("returnTime wave $wave $diff", row.getDouble("returnTime"), GalagaGame.returnTime(wave, diff), 1e-9)
        }
    }

    @Test
    fun comboMultiplierFollowsTheFixtureTable() {
        val table = fixture.getJSONArray("comboTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            assertEquals("combo ${row.getInt("combo")}", row.getInt("multiplier"), GalagaGame.multiplier(row.getInt("combo")))
        }
    }

    @Test
    fun rampStartFollowsTheFixtureTable() {
        val table = fixture.getJSONArray("startTable")
        assertTrue(table.length() > 0)
        for (i in 0 until table.length()) {
            val row = table.getJSONObject(i)
            val wpm = row.getDouble("characterWpm")
            assertEquals("start at $wpm WPM", row.getDouble("startWpm"), GalagaGame.rampStart(wpm), 1e-9)
            val config = GalagaGame.Config(characters = listOf('K'), characterWpm = wpm)
            assertEquals("Config.startWpm at $wpm", row.getDouble("startWpm"), config.startWpm, 1e-9)
            assertEquals("Config.targetWpm at $wpm", row.getDouble("targetWpm"), config.targetWpm, 1e-9)
            assertEquals("a new game opens at the start", row.getDouble("startWpm"), GalagaGame(config, Random(1)).currentWpm, 1e-9)
        }
    }

    @Test
    fun pathsFollowTheFixture() {
        val cases = fixture.getJSONArray("paths")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val row = c.getInt("row")
            val column = c.getInt("column")
            val columns = c.getInt("columns")
            val fromLeft = c.getBoolean("fromLeft")
            val s = GalagaPath.slot(row, column, columns)
            assertEquals("slot x ($row,$column)", c.getJSONObject("slot").getDouble("x"), s.x, 1e-9)
            assertEquals("slot y ($row,$column)", c.getJSONObject("slot").getDouble("y"), s.y, 1e-9)
            val entry = c.getJSONArray("entry")
            for (j in 0 until entry.length()) {
                val p = entry.getJSONObject(j)
                val got = GalagaPath.entry(p.getDouble("t"), fromLeft, s)
                assertEquals("entry x t=${p.getDouble("t")} ($row,$column)", p.getDouble("x"), got.x, 1e-9)
                assertEquals("entry y t=${p.getDouble("t")} ($row,$column)", p.getDouble("y"), got.y, 1e-9)
            }
            val dive = c.getJSONArray("dive")
            for (j in 0 until dive.length()) {
                val p = dive.getJSONObject(j)
                val got = GalagaPath.dive(p.getDouble("t"), s)
                assertEquals("dive x t=${p.getDouble("t")} ($row,$column)", p.getDouble("x"), got.x, 1e-9)
                assertEquals("dive y t=${p.getDouble("t")} ($row,$column)", p.getDouble("y"), got.y, 1e-9)
            }
            // position() is the same curves keyed by state; the return is the dive backwards.
            assertEquals(GalagaPath.entry(0.25, fromLeft, s), GalagaPath.position(enemy(1, GalagaEnemyState.ENTERING, 0.25, row, column, fromLeft), columns))
            assertEquals(GalagaPath.dive(0.25, s), GalagaPath.position(enemy(2, GalagaEnemyState.RETURNING, 0.75, row, column, fromLeft), columns))
            assertEquals(s, GalagaPath.position(enemy(3, GalagaEnemyState.FORMED, 0.0, row, column, fromLeft), columns))
        }
    }

    @Test
    fun enemiesEnterOnTheIntervalAndSettle() {
        val g = game(seed = 3)
        val interval = g.entryInterval
        assertTrue(g.advance(interval - 0.01).isEmpty())
        assertTrue(g.enemies.isEmpty())
        val first = g.advance(0.02)
        assertEquals(1, first.size)
        val e = (first[0] as GalagaEvent.Entered).enemy
        assertEquals(GalagaEnemyState.ENTERING, e.state)
        assertEquals(0, e.row)
        assertEquals(0, e.column)
        assertTrue(e.fromLeft)
        assertEquals(0.01 / g.entryTime, e.progress, 1e-9)
        assertEquals(g.entryTime, e.legTime, 1e-9)
        g.advance(g.entryTime)
        assertEquals(GalagaEnemyState.FORMED, g.enemies[0].state)
        // The second slot is (0,1), entered from the right.
        assertTrue(g.enemies.size > 1)
        assertEquals(0, g.enemies[1].row)
        assertEquals(1, g.enemies[1].column)
        assertFalse(g.enemies[1].fromLeft)
    }

    @Test
    fun aDiveThatLandsCostsALifeAndTheEnemyReturns() {
        val g = game(seed = 5)
        var dived: GalagaEnemy? = null
        var landed: GalagaEnemy? = null
        var guardSteps = 0
        while (landed == null && guardSteps < 100_000) {
            for (ev in g.advance(0.05)) {
                if (ev is GalagaEvent.Dived && dived == null) dived = ev.enemy
                if (ev is GalagaEvent.Landed) landed = ev.enemy
            }
            guardSteps += 1
        }
        assertNotNull(dived)
        assertEquals(GalagaEnemyState.DIVING, dived!!.state)
        assertEquals(0.0, dived.progress, 0.0)
        assertEquals(g.diveTime, dived.legTime, 1e-9)
        assertNotNull(landed)
        assertEquals(1.0, landed!!.progress, 0.0)
        assertEquals(2, g.lives)
        assertEquals(1, g.misses)
        assertEquals(0, g.combo)
        assertEquals(GalagaEnemyState.RETURNING, g.enemies.first { it.id == landed.id }.state)
        // Shooting it on the way back scores without the dive bonus.
        val before = g.score
        val shot = g.shoot('k')
        assertTrue(shot.isHit)
        assertEquals(landed.id, shot.enemy!!.id)
        assertEquals(GalagaEnemyState.RETURNING, shot.enemy.state)
        assertEquals(GalagaGame.pointsPerHit, shot.points)
        assertEquals(before + GalagaGame.pointsPerHit, g.score)
    }

    @Test
    fun threatOrderPrefersDiversThenReturnersThenTheLowestRow() {
        val entering = enemy(1, GalagaEnemyState.ENTERING, 0.9)
        val topRow = enemy(2, GalagaEnemyState.FORMED, 0.0, row = 0, column = 1)
        val lowRow = enemy(3, GalagaEnemyState.FORMED, 0.0, row = 2, column = 1)
        val returning = enemy(4, GalagaEnemyState.RETURNING, 0.2, row = 1)
        val diver = enemy(5, GalagaEnemyState.DIVING, 0.1, row = 1, column = 1)
        val deeperDiver = enemy(6, GalagaEnemyState.DIVING, 0.4, row = 1, column = 2)
        assertTrue(GalagaGame.threat(deeperDiver) > GalagaGame.threat(diver))
        assertTrue(GalagaGame.threat(diver) > GalagaGame.threat(returning))
        assertTrue(GalagaGame.threat(returning) > GalagaGame.threat(lowRow))
        assertTrue(GalagaGame.threat(lowRow) > GalagaGame.threat(topRow))
        assertTrue(GalagaGame.threat(topRow) > GalagaGame.threat(entering))
    }

    @Test
    fun everyHitScoresByTheRuleAndADiverEarnsTheBonus() {
        val g = game(seed = 9)
        var sawDiver = false
        var shots = 0
        var guardSteps = 0
        while (shots < 12 && guardSteps < 100_000) {
            g.advance(0.05)
            guardSteps += 1
            // Shoot only once something is diving, or once the field is full,
            // so the dive bonus is exercised at least once.
            val diving = g.enemies.any { it.state == GalagaEnemyState.DIVING }
            if (diving || g.enemies.size >= 4) {
                val before = g.score
                val combo = g.combo
                val shot = g.shoot('K')
                shots += 1
                val hit = shot.enemy
                assertNotNull("shot $shots hit nothing", hit)
                val expected = (GalagaGame.pointsPerHit + (if (hit!!.state == GalagaEnemyState.DIVING) GalagaGame.diveBonus else 0)) *
                    GalagaGame.multiplier(combo + 1)
                if (hit.state == GalagaEnemyState.DIVING) sawDiver = true
                val bonus = if (shot.waveCleared) GalagaGame.waveBonus else 0
                assertEquals("points of shot $shots", expected, shot.points)
                assertEquals("score after shot $shots", before + expected + bonus, g.score)
            }
        }
        assertEquals(12, shots)
        assertTrue("a diver was hit", sawDiver)
        val wrong = g.shoot('Z')
        assertFalse(wrong.isHit)
        assertNull(wrong.enemy)
        assertEquals(0, wrong.points)
        assertEquals(0, g.combo)
        assertEquals(1, g.misses)
    }

    @Test
    fun theLastHitOfAFormationClearsTheWave() {
        val g = game(seed = 11)
        var cleared = false
        var clearedScore = 0
        var guardSteps = 0
        while (!cleared && guardSteps < 100_000) {
            g.advance(0.05)
            guardSteps += 1
            if (g.enemies.isNotEmpty()) {
                val before = g.score
                val shot = g.shoot('K')
                if (shot.waveCleared) { cleared = true; clearedScore = g.score - before - shot.points }
            }
        }
        assertTrue(cleared)
        assertEquals(2, g.wave)
        assertEquals(8, g.hits)
        assertEquals(GalagaGame.waveBonus, clearedScore)
        assertEquals(0, g.released)
        assertTrue(g.enemies.isEmpty())
        // Wave 2's first entry waits the wave gap plus an interval.
        assertTrue(g.advance(GalagaGame.waveGap + g.entryInterval - 0.01).isEmpty())
        assertTrue(g.enemies.isEmpty())
        assertEquals(1, g.advance(0.02).size)
        assertEquals(1, g.enemies.size)
        assertEquals(10, g.formation.size)
    }

    @Test
    fun threeLandedDivesEndTheGame() {
        val g = game(seed = 13)
        var over = false
        var landings = 0
        var guardSteps = 0
        while (!over && guardSteps < 100_000) {
            for (ev in g.advance(0.1)) {
                if (ev is GalagaEvent.Landed) landings += 1
                if (ev is GalagaEvent.GameOver) over = true
            }
            guardSteps += 1
        }
        assertTrue(over)
        assertTrue(g.isOver)
        assertEquals(0, g.lives)
        assertEquals(3, landings)
        assertTrue(g.enemies.isEmpty())
        // A finished game ignores time and shots.
        assertTrue(g.advance(10.0).isEmpty())
        assertFalse(g.shoot('K').isHit)
        assertEquals(3, g.misses)
    }

    @Test
    fun theScenarioHolds() {
        val sc = fixture.getJSONObject("scenario")
        val g = game(seed = 7, lives = sc.getInt("lives"), characterWpm = sc.getDouble("characterWpm"))
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
                        while (g.enemies.isEmpty()) g.advance(0.05)
                        assertTrue("event $i: the hit missed", g.shoot('K').isHit)
                    }
                    "diveLands" -> {
                        var landed = 0
                        while (landed == 0) landed = g.advance(0.05).count { it is GalagaEvent.Landed }
                        assertEquals("one landing at a time", 1, landed)
                    }
                    "wrongShot" -> assertFalse(g.shoot('Z').isHit)
                    else -> fail("unknown event '$kind' in the fixture")
                }
            }
            assertEquals("currentWpm after event $i ($kind)", ev.getDouble("currentWpm"), g.currentWpm, 1e-9)
            assertEquals("combo after event $i ($kind)", ev.getInt("combo"), g.combo)
            assertEquals("multiplier after event $i ($kind)", ev.getInt("multiplier"), g.multiplier)
            assertEquals("lives after event $i ($kind)", ev.getInt("lives"), g.lives)
            assertEquals("wave after event $i ($kind)", ev.getInt("wave"), g.wave)
        }
        assertEquals(sc.getDouble("bestWpm"), g.bestWpm, 1e-9)
        assertFalse(g.isOver)
    }

    @Test
    fun noRampAtOrBelowTheFloor() {
        val floor = fixture.getJSONObject("derivation").getDouble("noRampAtOrBelow")
        val g = game(seed = 2, characterWpm = floor)
        assertEquals(g.config.startWpm, g.config.targetWpm, 0.0)
        repeat(12) {
            while (g.enemies.isEmpty()) g.advance(0.05)
            assertTrue(g.shoot('K').isHit)
        }
        assertEquals(floor, g.currentWpm, 1e-9)
        assertEquals(floor, g.bestWpm, 1e-9)
    }

    @Test
    fun sameSeedGivesTheSameSequence() {
        val pool = listOf('K', 'M', 'R', 'S')
        fun sequence(seed: Int): List<String> {
            val g = GalagaGame(GalagaGame.Config(characters = pool), rng = Random(seed))
            val out = ArrayList<String>()
            var steps = 0
            while (out.size < 20 && steps < 100_000) {
                for (ev in g.advance(0.05)) {
                    if (ev is GalagaEvent.Entered) out.add("E${ev.enemy.character}")
                    if (ev is GalagaEvent.Dived) { out.add("D${ev.enemy.id}"); g.shoot(ev.enemy.character) }
                }
                steps += 1
            }
            return out
        }
        assertEquals(sequence(42), sequence(42))
        assertTrue(sequence(42) != sequence(43))
        sequence(42).forEach { assertTrue(it, it.startsWith("D") || (it.length == 2 && it[1] in pool)) }
    }
}
