package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MIDI key wire format. Twin of the `MIDI key wire format` section in the
 * iOS repo's MorseKitCheck/main.swift (iOS issue #81).
 */
class MidiKeyParserTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun down(paddle: MidiKeyPaddle, note: Int) = MidiKeyMessage(paddle, note, true)
    private fun up(paddle: MidiKeyPaddle, note: Int) = MidiKeyMessage(paddle, note, false)

    /**
     * The reported bug: a BLE-MIDI key batches events into one radio burst, so a
     * buffer holding a key-down and its key-up must yield both. Reading only the
     * first message dropped the release and left the key stuck down.
     */
    @Test
    fun `both events in one buffer are decoded`() {
        assertEquals(
            listOf(down(MidiKeyPaddle.STRAIGHT, 0), up(MidiKeyPaddle.STRAIGHT, 0)),
            MidiKeyParser.messages(bytes(0x90, 0x00, 0x7F, 0x80, 0x00, 0x00))
        )
    }

    @Test
    fun `running status continues the previous message`() {
        assertEquals(
            listOf(down(MidiKeyPaddle.DIT, 1), up(MidiKeyPaddle.DIT, 1)),
            MidiKeyParser.messages(bytes(0x90, 0x01, 0x7F, 0x01, 0x00))
        )
    }

    @Test
    fun `note on with velocity zero is a release`() {
        assertEquals(
            listOf(up(MidiKeyPaddle.DAH, 2)),
            MidiKeyParser.messages(bytes(0x90, 0x02, 0x00))
        )
    }

    @Test
    fun `an interleaved clock byte does not break the message`() {
        assertEquals(
            listOf(down(MidiKeyPaddle.DAH, 2)),
            MidiKeyParser.messages(bytes(0x90, 0xF8, 0x02, 0x7F))
        )
    }

    /** We open every MIDI device, so an unrelated instrument must stay silent. */
    @Test
    fun `unrelated MIDI traffic is ignored`() {
        assertTrue(MidiKeyParser.messages(bytes(0x90, 0x40, 0x7F)).isEmpty())
        assertTrue(MidiKeyParser.messages(bytes(0xB0, 0x00, 0x00)).isEmpty())
        assertNull(MidiKeyParser.paddleForNote(64))
    }

    /** A one-byte Program Change must not swallow the key event behind it. */
    @Test
    fun `a program change does not swallow the next message`() {
        assertEquals(
            listOf(down(MidiKeyPaddle.STRAIGHT, 0)),
            MidiKeyParser.messages(bytes(0xC0, 0x01, 0x90, 0x00, 0x7F))
        )
    }

    @Test
    fun `a truncated trailing message is dropped not guessed`() {
        assertEquals(
            listOf(down(MidiKeyPaddle.STRAIGHT, 0)),
            MidiKeyParser.messages(bytes(0x90, 0x00, 0x7F, 0x90, 0x01))
        )
    }

    @Test
    fun `offset and count bound the walk`() {
        val buffer = bytes(0xFF, 0x90, 0x00, 0x7F, 0xFF)
        assertEquals(
            listOf(down(MidiKeyPaddle.STRAIGHT, 0)),
            MidiKeyParser.messages(buffer, offset = 1, count = 3)
        )
    }

    @Test
    fun `every keyer note maps to its paddle`() {
        assertEquals(MidiKeyPaddle.STRAIGHT, MidiKeyParser.paddleForNote(0))
        assertEquals(MidiKeyPaddle.DIT, MidiKeyParser.paddleForNote(20))
        assertEquals(MidiKeyPaddle.DIT, MidiKeyParser.paddleForNote(61))
        assertEquals(MidiKeyPaddle.DAH, MidiKeyParser.paddleForNote(21))
        assertEquals(MidiKeyPaddle.DAH, MidiKeyParser.paddleForNote(62))
    }
}
