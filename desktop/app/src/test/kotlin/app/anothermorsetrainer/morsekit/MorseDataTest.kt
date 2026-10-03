package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertTrue
import org.junit.Test

/** Curated quiz data rules, held to the iOS MorseKitCheck behavior. */
class MorseDataTest {

    /** QRL is the statement, QRL? the question — never conflated again (#27). */
    @Test
    fun qrlStatementAndQuestionAreDistinct() {
        val qrl = MorseData.qCodes.first { it.token == "QRL" }.meaning
        val qrlQ = MorseData.qCodes.first { it.token == "QRL?" }.meaning
        assertTrue("QRL must be the busy statement", "busy" in qrl)
        assertTrue("QRL? must be the in-use question", qrlQ.endsWith("?") && "in use" in qrlQ)
    }

    @Test
    fun qCodeTokensAreUniqueAndSendable() {
        val tokens = MorseData.qCodes.map { it.token }
        assertTrue(tokens.toSet().size == tokens.size)
        assertTrue(tokens.all { CWText.isFullySendable(it) })
    }
}
