package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brass Knuckles Knock Out rules, held to the same expectations as the Swift
 * MorseKitCheck "Brass Knuckles Knock Out" section through
 * `fixtures/brass-knuckles.json`. The expected values come from the game's own
 * reference implementation, not from either port (see the fixture's comment).
 */
class BrassKnucklesTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("brass-knuckles.json")
        assertNotNull("fixtures/brass-knuckles.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
    private fun ints(a: JSONArray): List<Int> = (0 until a.length()).map { a.getInt(it) }

    private fun quality(s: String) = when (s) {
        "good" -> BoxingQuality.GOOD
        "off" -> BoxingQuality.OFF
        else -> BoxingQuality.BAD
    }

    @Test
    fun theLadderIsTheFixtures() {
        val rows = fixture.getJSONArray("ladder")
        assertEquals(BoxingRules.FIGHTERS, rows.length())
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            val f = BoxingRules.LADDER[i]
            val got = listOf(
                f.name, f.from, f.blurb, f.koch, f.speedBump, f.spacingPct, f.hint, f.noise, f.fading,
                f.maxTell, f.hp, f.power, f.reactMs, f.restLeastMs, f.restExtraMs, f.openMs, f.pitchOffsetHz, f.fist
            )
            val want = (0 until r.length()).map { r.get(it) }
            assertEquals("fighter $i", want, got)
        }
    }

    @Test
    fun speedsAreTheFixtures() {
        val rows = fixture.getJSONArray("speeds")
        for (i in 0 until rows.length()) {
            val r = ints(rows.getJSONArray(i))
            val (wpm, eff) = BoxingRules.LADDER[r[0]].speeds(r[1], r[2])
            assertEquals("fighter ${r[0]} base ${r[1]} farnsworth ${r[2]}", listOf(r[3], r[4]), listOf(wpm, eff))
        }
    }

    @Test
    fun punchesDamageAndUnitsAreTheFixtures() {
        val rows = fixture.getJSONArray("characters")
        assertEquals(41, rows.length())
        val order = StringBuilder()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            val c = r.getString(0)[0]
            order.append(c)
            val kind = when (val p = BoxingRules.punchOf(c)) {
                BoxingPunch.Jab -> "jab"
                BoxingPunch.Hook -> "hook"
                is BoxingPunch.Combo -> "combo${p.punches}"
                null -> "none"
            }
            assertEquals("punch of $c", r.getString(1), kind)
            assertEquals("units of $c", r.getInt(2), BoxingRules.units(c))
            assertEquals("good $c", r.getInt(3), BoxingRules.damage(c, BoxingQuality.GOOD))
            assertEquals("off $c", r.getInt(4), BoxingRules.damage(c, BoxingQuality.OFF))
            assertEquals("bad $c", r.getInt(5), BoxingRules.damage(c, BoxingQuality.BAD))
        }
        assertEquals(BoxingRules.KOCH, order.toString())
        val none = fixture.getString("noPunch")[0]
        assertNull(BoxingRules.punchOf(none))
        assertEquals(0, BoxingRules.damage(none, BoxingQuality.GOOD))
    }

    @Test
    fun theLadderAddsKochCharactersInOrder() {
        val all = StringBuilder()
        BoxingRules.LADDER.forEachIndexed { i, f -> all.append(f.newChars(i)) }
        assertEquals(BoxingRules.KOCH, all.toString())
    }

    @Test
    fun rngAndUsCallsAreTheFixtures() {
        val rng = fixture.getJSONObject("rng")
        val a = BoxingRng(1)
        assertEquals(ints(rng.getJSONArray("seed1Below100")), (0 until 16).map { a.below(100) })
        val b = BoxingRng(0xDEADBEEF.toInt())
        assertEquals(ints(rng.getJSONArray("seedDEADBEEFBelow1000")), (0 until 16).map { b.below(1000) })
        val c = BoxingRng(1)
        assertEquals(strings(rng.getJSONArray("usCallsSeed1")), (0 until 24).map { c.usCall(it % 10) })
    }

    @Test
    fun tellsAndWordsAreTheFixtures() {
        val rows = fixture.getJSONArray("tellsAndWords")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val k = r.getInt("fighter")
            val f = BoxingFight(k, 12345, true, 60, "K5MME")
            assertEquals("tells of fighter $k", strings(r.getJSONArray("tells")), (0 until 12).map { f.pickTell() })
            val g = BoxingFight(k, 777, true, 60, "K5MME")
            assertEquals("words of fighter $k", strings(r.getJSONArray("words")), (0 until 10).map { g.pickWord() })
        }
    }

    @Test
    fun careerOpensFightersAsTheFixtureSays() {
        val rows = fixture.getJSONArray("career")
        val c = BoxingCareer()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            val opens = c.finished(r.getInt(0), r.getBoolean(1), r.getInt(2))
            assertEquals("opens at step $i", r.getBoolean(3), opens)
            assertEquals("unlocked at step $i", r.getInt(4), c.unlocked)
            assertEquals("best at step $i", r.getInt(5), c.bestScore(r.getInt(0)))
            assertEquals("wins at step $i", r.getInt(6), c.wins[r.getInt(0)])
        }
        assertFalse(c.champion())
        val champ = BoxingCareer(unlocked = BoxingRules.FIGHTERS)
        assertFalse(champ.finished(BoxingRules.FIGHTERS - 1, true, 10))
        assertTrue(champ.champion())
    }

    @Test
    fun worstCharactersComeFirst() {
        val s = BoxingStats()
        s.count('K', true); s.count('K', true); s.count('M', false); s.count('R', true); s.count('R', false)
        val want = fixture.getJSONArray("worst")
        val got = s.worst()
        assertEquals(want.length(), got.size)
        for (i in 0 until want.length()) {
            val w = want.getJSONArray(i)
            assertEquals(w.getString(0)[0], got[i].first)
            assertEquals(w.getInt(1), got[i].second)
            assertEquals(w.getInt(2), got[i].third)
        }
    }

    // The scripted fights.

    private fun u(x: Int): String = x.toUInt().toString()

    private fun foeName(p: BoxingFoe): String = when (p) {
        BoxingFoe.Idle -> "Idle"
        is BoxingFoe.Windup -> "Windup(${side(p.side)})"
        is BoxingFoe.Punch -> "Punch(${side(p.side)})"
        is BoxingFoe.Whiff -> "Whiff(${side(p.side)})"
        BoxingFoe.Open -> "Open"
        is BoxingFoe.Hurt -> "Hurt(${side(p.side)})"
        BoxingFoe.Block -> "Block"
        BoxingFoe.Stunned -> "Stunned"
        BoxingFoe.Down -> "Down"
        BoxingFoe.Out -> "Out"
        BoxingFoe.Rise -> "Rise"
        BoxingFoe.Victory -> "Victory"
    }

    private fun meName(p: BoxingMe): String = when (p) {
        BoxingMe.Idle -> "Idle"
        is BoxingMe.Dodge -> "Dodge(${side(p.side)})"
        is BoxingMe.Jab -> "Jab(${side(p.side)})"
        is BoxingMe.Hook -> "Hook(${side(p.side)})"
        is BoxingMe.Combo -> "Combo(${p.punches})"
        BoxingMe.Uppercut -> "Uppercut"
        is BoxingMe.Blocked -> "Blocked(${side(p.side)})"
        BoxingMe.Miss -> "Miss"
        BoxingMe.Hurt -> "Hurt"
        BoxingMe.Down -> "Down"
        BoxingMe.Rise -> "Rise"
        BoxingMe.Victory -> "Victory"
    }

    private fun side(s: BoxingSide) = if (s == BoxingSide.LEFT) "Left" else "Right"

    private fun phaseName(p: BoxingPhase): String = when (p) {
        BoxingPhase.Intro -> "Intro"
        BoxingPhase.RoundStart -> "RoundStart"
        BoxingPhase.Fight -> "Fight"
        BoxingPhase.FoeDown -> "FoeDown"
        BoxingPhase.MeDown -> "MeDown"
        BoxingPhase.RoundEnd -> "RoundEnd"
        is BoxingPhase.Over -> "Over:${p.won}:" + when (p.how) {
            BoxingOutcome.KO -> "Ko"
            BoxingOutcome.TKO -> "Tko"
            BoxingOutcome.DECISION -> "Decision"
        }
    }

    private fun calloutName(c: BoxingCallout?): String = when (c) {
        null -> "-"
        is BoxingCallout.Wrong -> "Wrong:${c.sent}"
        BoxingCallout.Dodge -> "Dodge"
        BoxingCallout.TooSlow -> "TooSlow"
        BoxingCallout.Clean -> "Clean"
        BoxingCallout.Star -> "Star"
        BoxingCallout.Uppercut -> "Uppercut"
        BoxingCallout.Blocked -> "Blocked"
        BoxingCallout.Missed -> "Missed"
        BoxingCallout.Down -> "Down"
        BoxingCallout.Up -> "Up"
    }

    private fun snapshot(f: BoxingFight, now: Int, played: String?): String {
        val s = f.stats
        return listOf(
            phaseName(f.phase),
            f.duelName,
            "hp ${f.foeHp} ${f.myHp}",
            "st ${f.stars} run ${f.cleanRun}",
            "sc ${f.score}",
            "rd ${f.round}",
            "now ${u(now)}",
            "dl ${f.deadline()?.let { u(it) } ?: "-"}",
            "tell ${f.tell}",
            "ans ${f.answer}",
            "word ${f.word}",
            "typed ${f.typed}",
            "cnt ${f.count}",
            "up ${f.getUp}",
            "play ${played ?: "-"}",
            "stats ${s.tells},${s.dodged},${s.punches},${s.clean},${s.damage},${s.taken},${s.uppercuts},${s.knockdowns},${s.knocked}",
            "foe ${foeName(f.foePose(now).first)}",
            "me ${meName(f.mePose(now).first)}",
            "call ${calloutName(f.callout?.first)}",
            "stop ${f.stop}"
        ).joinToString("|")
    }

    private fun runOp(f: BoxingFight, op: List<String>, nowIn: Int): Int {
        var now = nowIn
        fun n(i: Int): Int = op.getOrNull(i)?.toLong()?.toInt() ?: 0
        when (op[0]) {
            "start" -> assertTrue(f.start(now))
            "startFalse" -> assertFalse(f.start(now))
            "advance" -> f.deadline()?.let { now = it; f.update(now) }
            "update" -> { now += n(1); f.update(now) }
            "tellPlayed" -> { now += n(1); f.tellPlayed(now) }
            "answer" -> {
                now += n(2)
                for (c in f.tell) f.key(c, quality(op[1]), now)
            }
            "key" -> { now += n(3); f.key(op[1][0], quality(op[2]), now) }
            "keyUnknown" -> { now += n(1); f.keyUnknown(now) }
            "getUp" -> { now += n(2); f.key(f.getUp, quality(op[1]), now) }
            "star" -> { now += n(1); f.star(now) }
            "word" -> {
                now += n(2)
                for (c in f.word) f.key(c, quality(op[1]), now)
            }
            "pause" -> { now += n(1); f.pause(now) }
            "resume" -> { now += n(1); f.resume(now) }
            "setFoeHp" -> f.foeHp = n(1)
            "setMyHp" -> f.myHp = n(1)
            "setStars" -> f.stars = n(1)
            "wait" -> now += n(1)
            else -> throw IllegalArgumentException("unknown op ${op[0]}")
        }
        return now
    }

    @Test
    fun scriptedFightsPlayOutAsTheFixtureSays() {
        val fights = fixture.getJSONArray("fights")
        assertTrue(fights.length() >= 10)
        for (k in 0 until fights.length()) {
            val fx = fights.getJSONObject(k)
            val name = fx.getString("name")
            val f = BoxingFight(fx.getInt("fighter"), fx.getLong("seed").toInt(), fx.getBoolean("hints"), fx.getInt("ditMs"), fx.getString("callsign"))
            val ops = fx.getJSONArray("ops")
            val expect = fx.getJSONArray("expect")
            assertEquals(name, ops.length(), expect.length())
            var now = 1000
            for (i in 0 until ops.length()) {
                val op = strings(ops.getJSONArray(i))
                now = runOp(f, op, now)
                val played = f.takePlay()
                var line = snapshot(f, now, played)
                val copies = ArrayList<String>()
                f.takeCopies { sent, copied -> copies.add("$sent${copied ?: "_"}") }
                line += "|copies ${copies.joinToString(",")}"
                assertEquals("$name, step $i ${op}", expect.getString(i), line)
            }
        }
    }
}
