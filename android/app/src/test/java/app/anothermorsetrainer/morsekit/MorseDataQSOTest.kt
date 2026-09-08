package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Listen & Learn's curated on-air QSO vocabulary (#182), pinned against
 * `fixtures/qso-elements.json` at the repo root — the same file the iOS
 * `MorseKitCheck` harness reads. The fixture is the one ordered list both
 * ports carry; the tiers are its first N. It was written from operating
 * practice, not captured from either port, so both drifting the same way
 * still fails.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class MorseDataQSOTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("qso-elements.json")
        assertNotNull("fixtures/qso-elements.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val fixtureItems: List<TokenMeaning> by lazy {
        val items = fixture.getJSONArray("items")
        (0 until items.length()).map {
            val o = items.getJSONObject(it)
            TokenMeaning(o.getString("token"), o.getString("meaning"))
        }
    }

    @Test
    fun theTableHasExactlyOneHundredItems() {
        assertEquals(100, fixtureItems.size)
        assertEquals(100, MorseData.qsoElements.size)
    }

    @Test
    fun tokensAndMeaningsMatchTheFixtureInOrder() {
        assertEquals(fixtureItems.map { it.token }, MorseData.qsoElements.map { it.token })
        assertEquals(fixtureItems.map { it.meaning }, MorseData.qsoElements.map { it.meaning })
    }

    @Test
    fun noTokenAppearsTwice() {
        val tokens = MorseData.qsoElements.map { it.token }
        assertEquals(tokens.size, tokens.toSet().size)
    }

    /** Brief readback (#210): the meaning before its first " — " qualifier. */
    @Test
    fun briefMeaningsMatchTheFixturesExamples() {
        val brief = fixture.getJSONObject("brief")
        assertEquals(brief.getString("separator"), MorseData.BRIEF_SEPARATOR)
        val examples = brief.getJSONArray("examples")
        for (i in 0 until examples.length()) {
            val ex = examples.getJSONObject(i)
            assertEquals("brief(\"${ex.getString("meaning")}\")", ex.getString("brief"), MorseData.briefMeaning(ex.getString("meaning")))
        }
    }

    @Test
    fun everyMeaningHasANonEmptyBriefFormWithNoQualifierLeft() {
        for ((token, meaning) in MorseData.qsoElements) {
            val b = MorseData.briefMeaning(meaning)
            assertTrue("$token has an empty brief form", b.isNotEmpty())
            assertTrue("$token's brief form still carries a qualifier: $b", MorseData.BRIEF_SEPARATOR !in b)
        }
    }

    @Test
    fun tierSizesAreTheFixtures() {
        val tiers = fixture.getJSONObject("tiers")
        assertEquals(tiers.getInt("top20"), MorseData.QSO_TOP_20_COUNT)
        assertEquals(tiers.getInt("top100"), MorseData.QSO_TOP_100_COUNT)
    }

    /**
     * Every token is sendable: a bracketed token is a prosign spelled as
     * [MorseData.prosigns] spells it; anything else is characters with a Morse
     * pattern, with single spaces as word gaps.
     */
    @Test
    fun everyTokenIsSendable() {
        for ((token, _) in MorseData.qsoElements) {
            if (token.startsWith("<")) {
                assertTrue("$token is not a prosign MorseData.prosigns knows", MorseData.prosigns.any { it.name == token })
            } else {
                assertTrue("$token has stray spacing", token == token.trim() && "  " !in token)
                assertTrue("$token has a character with no Morse pattern", token.all { it == ' ' || MorseCode.pattern(it) != null })
            }
        }
    }

    /**
     * Where a token already lives in the abbreviation, Q-code or prosign
     * tables, the meaning is that table's wording — one answer per token.
     */
    @Test
    fun meaningsAgreeWithTheReferenceTables() {
        for ((token, meaning) in MorseData.qsoElements) {
            val known = MorseData.abbreviations.firstOrNull { it.token == token }?.meaning
                ?: MorseData.qCodes.firstOrNull { it.token == token }?.meaning
                ?: MorseData.prosigns.firstOrNull { it.name == token }?.meaning
            if (known != null) assertEquals("$token: the reference table says \"$known\"", known, meaning)
        }
    }

    @Test
    fun tiersAreTheFirstNAsMeaningItems() {
        val top20 = MorseData.qsoElementItems(MorseData.QSO_TOP_20_COUNT)
        val top100 = MorseData.qsoElementItems(MorseData.QSO_TOP_100_COUNT)
        assertEquals(fixtureItems.take(20).map { it.token }, top20.map { it.display })
        assertEquals(fixtureItems.map { it.token }, top100.map { it.display })
        assertEquals(fixtureItems.map { it.meaning }, top100.map { it.answer })
        assertEquals(top100.size, top100.map { it.id }.toSet().size)
    }

    @Test
    fun aBracketedTokenPlaysTheRunTogetherProsignPattern() {
        val ar = MorseData.qsoElementItems(MorseData.QSO_TOP_100_COUNT).first { it.display == "<AR>" }
        assertEquals(MorseItem.Playable.Pattern(".-.-."), ar.playable)
    }

    @Test
    fun plainAndMultiWordTokensPlayAsText() {
        val items = MorseData.qsoElementItems(MorseData.QSO_TOP_100_COUNT)
        assertEquals(MorseItem.Playable.Text("CQ"), items.first { it.display == "CQ" }.playable)
        assertEquals(MorseItem.Playable.Text("CQ CQ CQ"), items.first { it.display == "CQ CQ CQ" }.playable)
    }
}
