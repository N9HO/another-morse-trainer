package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the leaderboard and buddy streak do when the device cannot attest
 * (#349), desktop copy, held to `fixtures/attestation-availability.json` — the file the
 * Swift harness and the desktop tests read too, so all three apps hide the
 * same features, keep the same ones and point at the same note.
 */
class AttestationAvailabilityTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("attestation-availability.json")
        assertNotNull("fixtures/attestation-availability.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun `every fixture state gets its feature switches`() {
        val states = fixture.getJSONArray("states")
        assertEquals("the fixture lists every state", AttestationState.entries.size, states.length())
        for (i in 0 until states.length()) {
            val c = states.getJSONObject(i)
            val state = AttestationState.fromId(c.getString("state"))
            assertNotNull("unknown state ${c.getString("state")}", state)
            val f = AttestationAvailability.features(state!!)
            val label = c.getString("state")
            assertEquals("$label shareSwitchVisible", c.getBoolean("shareSwitchVisible"), f.shareSwitchVisible)
            assertEquals("$label optInPromptOffered", c.getBoolean("optInPromptOffered"), f.optInPromptOffered)
            assertEquals("$label runsRanked", c.getBoolean("runsRanked"), f.runsRanked)
            assertEquals("$label deleteScoresOffered", c.getBoolean("deleteScoresOffered"), f.deleteScoresOffered)
            assertEquals("$label buddyActionsEnabled", c.getBoolean("buddyActionsEnabled"), f.buddyActionsEnabled)
            assertEquals("$label boardReadable", c.getBoolean("boardReadable"), f.boardReadable)
            assertEquals("$label personalBestsKept", c.getBoolean("personalBestsKept"), f.personalBestsKept)
            assertEquals("$label trainingModesWork", c.getBoolean("trainingModesWork"), f.trainingModesWork)
            assertEquals("$label note", AttestationNote.fromId(c.getString("noteKey")), f.note)
        }
    }

    @Test
    fun `training and personal bests never depend on attestation`() {
        for (state in AttestationState.entries) {
            val f = AttestationAvailability.features(state)
            assertTrue("$state keeps training", f.trainingModesWork)
            assertTrue("$state keeps personal bests", f.personalBestsKept)
            assertTrue("$state keeps the public board readable", f.boardReadable)
        }
    }

    @Test
    fun `desktop is always without platform attestation and shows the unranked note`() {
        val state = AttestationAvailability.desktopState
        assertEquals(AttestationState.NO_PLATFORM_ATTESTATION, state)
        val f = AttestationAvailability.features(state)
        assertEquals(false, f.runsRanked)
        assertEquals(false, f.shareSwitchVisible)
        assertEquals(false, f.buddyActionsEnabled)
        assertEquals(AttestationNote.NO_PLATFORM_ATTESTATION, f.note)
    }
}
