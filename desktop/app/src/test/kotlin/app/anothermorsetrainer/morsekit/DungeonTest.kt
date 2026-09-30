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
 * CW Dungeon rules (#186), held to the same expectations as the Swift
 * MorseKitCheck "CW Dungeon" section: the two engines are twins. Everything
 * is pinned by `fixtures/dungeon.json`, the same file the Swift harness reads.
 */
class DungeonTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("dungeon.json")
        assertNotNull("fixtures/dungeon.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val derivation: JSONObject get() = fixture.getJSONObject("derivation")

    /** "TRAP>MAP" in the fixture names a spell of the book. */
    private fun spellNamed(key: String): DungeonSpell =
        DungeonSpells.all.firstOrNull { "${it.spell}>${it.counter}" == key }
            ?: throw AssertionError("no spell '$key' in the book")

    private fun difficultyNamed(key: String): InvadersDifficulty = InvadersDifficulty.valueOf(key.uppercase())

    private fun waitForCast(g: DungeonGame) {
        var waited = 0.0
        while (g.pendingCast == null && !g.isOver && waited < 10) { g.advance(0.05); waited += 0.05 }
        assertNotNull("a spell should be pending", g.pendingCast)
    }

    @Test
    fun constantsAreTheFixtures() {
        val d = derivation
        assertEquals(d.getInt("pointsPerHit"), DungeonGame.pointsPerHit)
        assertEquals(d.getInt("roomClearBonus"), DungeonGame.roomClearBonus)
        assertEquals(d.getInt("hitsPerMonster"), DungeonGame.hitsPerMonster)
        assertEquals(d.getInt("bossEvery"), DungeonGame.bossEvery)
        assertEquals(d.getInt("bossHits"), DungeonGame.bossHits)
        assertEquals(d.getDouble("baseWindow"), DungeonGame.baseWindow, 0.0)
        assertEquals(d.getDouble("windowDecay"), DungeonGame.windowDecay, 0.0)
        assertEquals(d.getDouble("minWindow"), DungeonGame.minWindow, 0.0)
        assertEquals(d.getDouble("castDelay"), DungeonGame.castDelay, 0.0)
        assertEquals(d.getDouble("roomDelay"), DungeonGame.roomDelay, 0.0)
        assertEquals(d.getDouble("minWpm"), DungeonGame.minWpm, 0.0)
        assertEquals(d.getDouble("startOffset"), DungeonGame.rampStartOffset, 0.0)
        assertEquals(d.getDouble("step"), DungeonGame.rampStep, 0.0)
        assertEquals(d.getInt("hitsPerStep"), DungeonGame.hitsPerRampStep)
        assertEquals(d.getInt("starterCount"), DungeonSpells.starterCount)
        assertEquals(d.getInt("minPool"), DungeonSpells.minPool)
    }

    @Test
    fun spellBookIsTheFixturesInOrder() {
        val spells = fixture.getJSONArray("spells")
        assertEquals(spells.length(), DungeonSpells.all.size)
        for (i in 0 until spells.length()) {
            val want = spells.getJSONObject(i)
            val got = DungeonSpells.all[i]
            assertEquals("spell $i", want.getString("spell"), got.spell)
            assertEquals("counter of ${got.spell}", want.getString("counter"), got.counter)
            assertEquals("heals of ${got.spell}", want.getBoolean("heals"), got.heals)
            assertEquals("characters of ${got.spell}", want.getString("characters"), got.characters.sorted().joinToString(""))
        }
        assertEquals(DungeonSpells.all.take(derivation.getInt("starterCount")), DungeonSpells.starters)
    }

    @Test
    fun poolsFollowTheFixture() {
        val pools = fixture.getJSONArray("pools")
        assertTrue(pools.length() > 0)
        for (i in 0 until pools.length()) {
            val p = pools.getJSONObject(i)
            val got = DungeonSpells.pool(p.getString("characters").toList())
            val want = p.getJSONArray("spells").let { arr -> List(arr.length()) { arr.getString(it) } }
            assertEquals(p.getString("name"), want, got.spells.map { "${it.spell}>${it.counter}" })
            assertEquals(p.getString("name") + " fallback", p.getBoolean("fallback"), got.fallback)
        }
    }

    @Test
    fun roomLayoutsFollowTheFixture() {
        val rooms = fixture.getJSONArray("rooms")
        assertTrue(rooms.length() > 0)
        for (i in 0 until rooms.length()) {
            val r = rooms.getJSONObject(i)
            val want = r.getJSONArray("monsters").let { arr ->
                List(arr.length()) { arr.getJSONObject(it).let { m -> "${m.getString("kind")}:${m.getInt("hits")}" } }
            }
            val got = DungeonGame.layout(r.getInt("room")).map { (kind, hits) -> "${kind.key}:$hits" }
            assertEquals("room ${r.getInt("room")}", want, got)
        }
    }

    @Test
    fun attackWindowsFollowTheFixture() {
        val windows = fixture.getJSONArray("windows")
        assertTrue(windows.length() > 0)
        for (i in 0 until windows.length()) {
            val w = windows.getJSONObject(i)
            val got = DungeonGame.window(w.getInt("room"), difficultyNamed(w.getString("difficulty")))
            assertEquals("room ${w.getInt("room")} ${w.getString("difficulty")}", w.getDouble("window"), got, 1e-6)
        }
    }

    @Test
    fun sendTimesFollowParisTiming() {
        val rows = fixture.getJSONArray("sendSeconds")
        assertTrue(rows.length() > 0)
        for (i in 0 until rows.length()) {
            val s = rows.getJSONObject(i)
            val got = DungeonGame.sendSeconds(s.getString("word"), MorseTiming(s.getDouble("wpm")))
            assertEquals("${s.getString("word")} at ${s.getDouble("wpm")} WPM", s.getDouble("seconds"), got, 1e-6)
        }
    }

    @Test
    fun multiplierFollowsTheFixture() {
        val rows = fixture.getJSONArray("multiplier")
        for (i in 0 until rows.length()) {
            val m = rows.getJSONObject(i)
            assertEquals("combo ${m.getInt("combo")}", m.getInt("multiplier"), DungeonGame.multiplier(m.getInt("combo")))
        }
    }

    @Test
    fun characterOutcomesFollowTheFixture() {
        val cases = fixture.getJSONArray("characterOutcomes")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("outcomes").let { arr ->
                List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    DungeonCharacterOutcome(o.getString("target")[0], if (o.isNull("chosen")) null else o.getString("chosen")[0])
                }
            }
            val got = DungeonGame.characterOutcomes(c.getString("expected"), c.getString("keyed"))
            assertEquals("${c.getString("expected")} vs '${c.getString("keyed")}'", want, got)
        }
    }

    @Test
    fun theFirstSpellIsCastAfterTheCastDelay() {
        val sc = fixture.getJSONObject("scenario")
        val spell = spellNamed(sc.getString("spell"))
        val g = DungeonGame(
            DungeonGame.Config(listOf(spell), difficultyNamed(sc.getString("difficulty")), sc.getInt("lives"), sc.getDouble("characterWpm")),
            rng = Random(7)
        )
        assertEquals(sc.getDouble("startWpm"), g.currentWpm, 1e-9)
        assertEquals(sc.getDouble("targetWpm"), g.config.targetWpm, 1e-9)
        assertEquals(1, g.room)
        assertEquals(1, g.monsters.size)
        assertNull(g.pendingCast)
        val delay = derivation.getDouble("castDelay")
        assertTrue(g.advance(delay - 0.01).isEmpty())
        val events = g.advance(0.02)
        assertEquals(1, events.size)
        val cast = (events[0] as DungeonEvent.Cast).cast
        assertEquals(spell, cast.spell)
        assertEquals(g.monsters[0].id, cast.monsterId)
        assertEquals(DungeonGame.window(1, g.config.difficulty), cast.window, 1e-9)
        assertEquals(DungeonGame.sendSeconds(spell.spell, MorseTiming(sc.getDouble("startWpm"))), cast.sendSeconds, 1e-9)
        assertTrue(g.isSending)
    }

    @Test
    fun keyingBetweenCastsIsNothing() {
        val g = DungeonGame(DungeonGame.Config(DungeonSpells.starters), rng = Random(1))
        assertNull(g.cast("MAP"))
        assertEquals(0, g.score)
        assertEquals(3, g.lives)
    }

    @Test
    fun runFollowsTheScriptedScenario() {
        val sc = fixture.getJSONObject("scenario")
        val spell = spellNamed(sc.getString("spell"))
        val g = DungeonGame(
            DungeonGame.Config(listOf(spell), difficultyNamed(sc.getString("difficulty")), sc.getInt("lives"), sc.getDouble("characterWpm")),
            rng = Random(7)
        )
        val roomDelay = derivation.getDouble("roomDelay")
        var lastRoom = 1
        val steps = sc.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            val kind = st.getString("step")
            repeat(st.optInt("times", 1)) {
                var waited = 0.0
                while (g.pendingCast == null && !g.isOver && waited < 10) { g.advance(0.05); waited += 0.05 }
                if (g.room != lastRoom) {
                    // A new room's first cast waits roomDelay, not castDelay.
                    assertTrue("step $i: room ${g.room} cast after $waited s", waited >= roomDelay - 0.05)
                    lastRoom = g.room
                }
                val pending = g.pendingCast ?: throw AssertionError("step $i: no spell pending")
                when (kind) {
                    "counter" -> assertTrue("step $i: the counter did not land", g.cast(pending.spell.counter)!!.isCountered)
                    "wrong" -> {
                        val r = g.cast("XX")!!
                        assertEquals("step $i", DungeonOutcome.WRONG, r.outcome)
                        assertEquals("step $i", 0, r.points)
                    }
                    "late" -> {
                        // The attack lands at sendSeconds + window, not a tick before.
                        assertTrue("step $i: landed early", g.advance(pending.sendSeconds + pending.window - 0.01).isEmpty())
                        assertTrue("step $i: never landed", g.advance(0.02).any { it is DungeonEvent.Attacked })
                    }
                    else -> fail("unknown step '$kind' in the fixture")
                }
            }
            val where = "after step $i ($kind)"
            assertEquals("$where score", st.getInt("score"), g.score)
            assertEquals("$where lives", st.getInt("lives"), g.lives)
            assertEquals("$where combo", st.getInt("combo"), g.combo)
            assertEquals("$where room", st.getInt("room"), g.room)
            assertEquals("$where currentWpm", st.getDouble("currentWpm"), g.currentWpm, 1e-9)
            assertEquals("$where alive", st.getInt("alive"), g.monsters.count { !it.isDown })
            assertEquals("$where over", st.optBoolean("gameOver", false), g.isOver)
        }
        assertEquals(sc.getInt("hits"), g.hits)
        assertEquals(sc.getInt("misses"), g.misses)
        assertEquals(sc.getInt("bestCombo"), g.bestCombo)
        assertEquals(sc.getDouble("bestWpm"), g.bestWpm, 1e-9)
        // A finished game ignores time and keying.
        assertTrue(g.isOver)
        assertTrue(g.advance(10.0).isEmpty())
        assertNull(g.cast(spell.counter))
    }

    @Test
    fun counteredHealRestoresALifeNeverPastTheStart() {
        val hs = fixture.getJSONObject("healScenario")
        val heal = spellNamed(hs.getString("spell"))
        assertTrue(heal.heals)
        val g = DungeonGame(DungeonGame.Config(listOf(heal), lives = hs.getInt("lives"), characterWpm = hs.getDouble("characterWpm")), rng = Random(3))
        val steps = hs.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            waitForCast(g)
            val r = if (st.getString("step") == "counter") g.cast(heal.counter) else g.cast("XX")
            assertNotNull(r)
            assertEquals("heal step $i lives", st.getInt("lives"), g.lives)
            assertEquals("heal step $i healed", st.getBoolean("healed"), r!!.healed)
        }
    }

    @Test
    fun sameSeedGivesTheSameSpellSequenceNeverTwiceRunning() {
        fun sequence(seed: Int): List<String> {
            val g = DungeonGame(DungeonGame.Config(DungeonSpells.all, lives = 50), rng = Random(seed))
            val out = ArrayList<String>()
            repeat(24) {
                waitForCast(g)
                out.add(g.pendingCast!!.spell.spell)
                g.cast("XX")
            }
            return out
        }
        val s = sequence(42)
        assertEquals(s, sequence(42))
        assertTrue(s != sequence(43))
        s.zipWithNext().forEach { (a, b) -> assertTrue("$a twice running", a != b) }
    }

    @Test
    fun farnsworthStretchesTheSpellsSpacing() {
        val g = DungeonGame(DungeonGame.Config(DungeonSpells.starters, characterWpm = 20.0, effectiveWpm = 10.0), rng = Random(1))
        assertEquals(g.config.startWpm, g.timing.wpm, 0.0)
        assertEquals(10.0, g.timing.effectiveWpm, 0.0)
        assertTrue(DungeonGame.sendSeconds("TRAP", g.timing) > DungeonGame.sendSeconds("TRAP", MorseTiming(g.config.startWpm)))
        val standard = DungeonGame(DungeonGame.Config(DungeonSpells.starters, characterWpm = 20.0), rng = Random(1))
        assertEquals(standard.timing.wpm, standard.timing.effectiveWpm, 0.0)
    }

    @Test
    fun echoIsAWrongCounterFlaggedAsSuch() {
        val spell = spellNamed("TRAP>MAP")
        val g = DungeonGame(DungeonGame.Config(listOf(spell)), rng = Random(1))
        waitForCast(g)
        val r = g.cast("trap")!!
        assertFalse(r.isCountered)
        assertTrue(r.isEcho)
        assertEquals(2, g.lives)
    }
}
