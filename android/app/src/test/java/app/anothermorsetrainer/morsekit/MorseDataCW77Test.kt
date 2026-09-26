package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CWOps "CW 77" list (#240), pinned against `fixtures/cw77.json` at the
 * repo root — the same file the iOS `MorseKitCheck` harness reads. The
 * fixture carries the maintainer-supplied file verbatim; the expected set is
 * derived from it here (repeats dropped, first kept, five prosigns
 * bracketed), not read back from the table, so both ports drifting the same
 * way still fails.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class MorseDataCW77Test {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("cw77.json")
        assertNotNull("fixtures/cw77.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private val fixtureItems: List<TokenMeaning> by lazy {
        val items = fixture.getJSONArray("items")
        (0 until items.length()).map {
            val o = items.getJSONObject(it)
            TokenMeaning(o.getString("token"), o.getString("meaning"))
        }
    }

    @Test
    fun deDuplicatingTheSourceGivesTheFixturesSeventyItemsInOrder() {
        val source = strings(fixture.getJSONArray("source"))
        val prosigns = strings(fixture.getJSONArray("prosigns")).toSet()
        assertEquals(75, source.size)
        val derived = source.distinct().map { if (it in prosigns) "<$it>" else it }
        assertEquals(71, fixture.getInt("uniqueCount"))
        assertEquals(fixture.getInt("uniqueCount"), derived.size)
        assertEquals(derived, fixtureItems.map { it.token })
    }

    @Test
    fun tokensAndMeaningsMatchTheFixtureInOrder() {
        assertEquals(fixtureItems.map { it.token }, MorseData.cw77.map { it.token })
        assertEquals(fixtureItems.map { it.meaning }, MorseData.cw77.map { it.meaning })
    }

    @Test
    fun nameAndRecommendedPlaybackAreTheFixtures() {
        assertEquals("CW 77", MorseData.CW77_NAME)
        val rec = fixture.getJSONObject("recommended")
        assertEquals(rec.getDouble("wpm"), MorseData.CW77_RECOMMENDED_WPM, 0.0)
        assertFalse(rec.getBoolean("farnsworth"))
    }

    @Test
    fun everyTokenIsSendable() {
        for ((token, _) in MorseData.cw77) {
            if (token.startsWith("<")) {
                assertTrue("$token is not a prosign MorseData.prosigns knows", MorseData.prosigns.any { it.name == token })
            } else {
                assertTrue("$token has a character with no Morse pattern", token.all { MorseCode.pattern(it) != null })
            }
        }
    }

    @Test
    fun meaningsAgreeWithTheReferenceTables() {
        for ((token, meaning) in MorseData.cw77) {
            val known = MorseData.abbreviations.firstOrNull { it.token == token }?.meaning
                ?: MorseData.qCodes.firstOrNull { it.token == token }?.meaning
                ?: MorseData.prosigns.firstOrNull { it.name == token }?.meaning
                ?: MorseData.qsoElements.firstOrNull { it.token == token }?.meaning
            if (known != null) assertEquals("$token: the reference table says \"$known\"", known, meaning)
            assertTrue("$token has an empty brief form", MorseData.briefMeaning(meaning).isNotEmpty())
        }
    }

    @Test
    fun listenItemsAnswerTheMeaningAndWordItemsTheToken() {
        val items = MorseData.cw77Items()
        val words = MorseData.cw77WordItems()
        assertEquals(fixtureItems.map { it.token }, items.map { it.display })
        assertEquals(fixtureItems.map { it.meaning }, items.map { it.answer })
        assertEquals(fixtureItems.map { it.token }, words.map { it.display })
        assertEquals(fixtureItems.map { it.token }, words.map { it.answer })
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    @Test
    fun prosignsPlayRunTogetherAndEverythingElseAsText() {
        val items = MorseData.cw77Items()
        assertEquals(MorseItem.Playable.Pattern("-...-"), items.first { it.display == "<BT>" }.playable)
        assertEquals(MorseItem.Playable.Text("BK"), items.first { it.display == "BK" }.playable)
        assertEquals(MorseItem.Playable.Text("HW?"), items.first { it.display == "HW?" }.playable)
    }

    @Test
    fun theCallsignAndNameRuleMatchesEveryFixtureCase() {
        val personal = fixture.getJSONObject("personal")
        assertEquals(personal.getString("placeholderCallsign"), MorseData.CW77_PLACEHOLDER_CALLSIGN)
        assertEquals(personal.getString("callsignMeaning"), MorseData.CW77_CALLSIGN_MEANING)
        assertEquals(personal.getString("nameMeaning"), MorseData.CW77_NAME_MEANING)
        val cases = personal.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(
                c.getString("why"),
                strings(c.getJSONArray("expected")),
                MorseData.cw77Personal(c.getString("callsign"), c.getString("name")).map { it.token }
            )
        }
    }

    @Test
    fun personalItemsFollowTheSeventyWithTheirOwnMeanings() {
        val mine = MorseData.cw77Personal("n9ho", "Justin")
        assertEquals(listOf(MorseData.CW77_CALLSIGN_MEANING, MorseData.CW77_NAME_MEANING), mine.map { it.meaning })
        val items = MorseData.cw77Items(mine)
        assertEquals(72, items.size)
        assertEquals(listOf("N9HO", "JUSTIN"), items.takeLast(2).map { it.display })
        assertEquals(72, items.map { it.id }.toSet().size)
    }
}
