package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The title a Stats session row shows for each stored mode string, held to
 * `fixtures/gap-docs-mode-titles.json` (read by the Swift harness and by the
 * other Kotlin tree too), so a session reads the same on every app.
 */
class SessionTitlesTest {

    private val modes by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("gap-docs-mode-titles.json")
        assertNotNull("fixtures/gap-docs-mode-titles.json is not on the test classpath", stream)
        val a = JSONObject(stream!!.bufferedReader().readText()).getJSONArray("modes")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test
    fun theFixtureListsAll25Modes() {
        assertEquals(25, modes.size)
    }

    @Test
    fun everyStoredModeShowsTheFixtureTitle() {
        for (m in modes) {
            assertEquals(
                "title shown for stored mode ${m.getString("kotlinMode")}",
                m.getString("title"),
                SessionRecord.displayTitle(m.getString("kotlinMode"))
            )
        }
    }

    @Test
    fun storedModeStringsStayTheSyncTable() {
        // The stored strings key saved records and the sync table: only the display is mapped.
        for (m in modes) {
            assertEquals(m.getString("id"), SyncWire.wireMode(m.getString("kotlinMode")))
        }
        assertTrue(SessionRecord.displayTitle("Some future mode") == "Some future mode")
    }
}
