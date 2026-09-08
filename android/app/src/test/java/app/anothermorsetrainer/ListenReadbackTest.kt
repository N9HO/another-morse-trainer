package app.anothermorsetrainer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Listen & Learn's readback choice (#210): the token-plus-meaning sets are
 * spoken either spelled out and then the full meaning, or as the brief
 * meaning alone; the display carries the whole gloss either way. Pins the
 * strings a listener actually hears, which the iOS twin (AppModel.listenPool)
 * has no harness for.
 */
class ListenReadbackTest {

    private fun item(content: ListenContent, readback: ListenReadback, token: String): ListenItem =
        listenPool(content, readback).first { it.display.startsWith("$token — ") }

    @Test
    fun `spelled readback says the letters and then the whole meaning`() {
        assertEquals("t n x. thanks", item(ListenContent.QSO_TOP_20, ListenReadback.SPELLED, "TNX").spoken)
        assertEquals("k n. go ahead — named station only", item(ListenContent.QSO_TOP_20, ListenReadback.SPELLED, "<KN>").spoken)
    }

    @Test
    fun `meaning-only readback says the brief meaning alone`() {
        assertEquals("thanks", item(ListenContent.QSO_TOP_20, ListenReadback.MEANING_ONLY, "TNX").spoken)
        assertEquals("go ahead", item(ListenContent.QSO_TOP_20, ListenReadback.MEANING_ONLY, "<KN>").spoken)
        assertEquals("five nine nine", item(ListenContent.QSO_TOP_20, ListenReadback.MEANING_ONLY, "5NN").spoken)
        assertEquals("my location is", item(ListenContent.ABBREVIATIONS, ListenReadback.MEANING_ONLY, "QTH").spoken)
    }

    @Test
    fun `the display shows the whole gloss whatever is spoken`() {
        assertEquals("<KN> — go ahead — named station only", item(ListenContent.QSO_TOP_20, ListenReadback.MEANING_ONLY, "<KN>").display)
        assertEquals("<KN> — go ahead — named station only", item(ListenContent.QSO_TOP_20, ListenReadback.SPELLED, "<KN>").display)
    }

    @Test
    fun `characters and words are unchanged by the readback choice`() {
        assertEquals(
            listenPool(ListenContent.WORDS, ListenReadback.SPELLED).map { it.spoken },
            listenPool(ListenContent.WORDS, ListenReadback.MEANING_ONLY).map { it.spoken }
        )
    }
}
