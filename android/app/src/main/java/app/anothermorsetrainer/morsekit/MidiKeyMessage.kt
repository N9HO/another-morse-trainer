package app.anothermorsetrainer.morsekit

/**
 * Parsing of the MIDI byte stream a Morse key sends, kept free of the Android
 * MIDI classes so the wire format can be unit-tested on the JVM.
 *
 * A MIDI buffer is a *stream*, not one message. `MidiReceiver.onSend` hands over
 * `count` bytes that may hold several messages back to back — that is why it
 * carries an offset and a count at all — and a BLE-MIDI key routinely batches
 * events, since the BLE-MIDI transport exists to amortize the radio's ~7.5 ms
 * connection interval. Within a buffer a repeated status byte may also be
 * dropped (running status). Reading only the first three bytes therefore loses
 * events, and the one most often lost is a key-up that arrived in the same
 * radio burst as its key-down — which leaves the key held down until the next
 * press, because [MidiKeyInput] holds the key while any note is down.
 *
 * Twin of `Sources/MorseKit/MIDIKeyMessage.swift` in the iOS repo.
 */

/** Which contact of a Morse key an event came from. */
enum class MidiKeyPaddle { STRAIGHT, DIT, DAH }

/** One key-down or key-up decoded from a MIDI stream. */
data class MidiKeyMessage(val paddle: MidiKeyPaddle, val note: Int, val isDown: Boolean)

object MidiKeyParser {

    /**
     * Note numbers the supported keys send, across firmwares and keyer modes:
     * straight key = 0; dit paddle = 1, 20, or (passthrough) 61; dah paddle =
     * 2, 21, or (passthrough) 62. This matches the Vail web repeater's full set
     * so paddle input works regardless of mode. Every other note is unmapped:
     * we listen to every MIDI device, so an unrelated synth must never register
     * as keying.
     */
    fun paddleForNote(note: Int): MidiKeyPaddle? = when (note) {
        0 -> MidiKeyPaddle.STRAIGHT
        1, 20, 61 -> MidiKeyPaddle.DIT
        2, 21, 62 -> MidiKeyPaddle.DAH
        else -> null
    }

    /**
     * Every key event in [count] bytes of [msg] starting at [offset], in order.
     *
     * Handles running status (a status byte omitted because it repeats the
     * previous one) and System Real-Time bytes, which the spec allows to appear
     * *between* the bytes of another message without disturbing it.
     */
    fun messages(msg: ByteArray, offset: Int = 0, count: Int = msg.size): List<MidiKeyMessage> {
        val out = mutableListOf<MidiKeyMessage>()
        val end = minOf(offset + count, msg.size)
        var runningStatus = 0
        var i = offset

        while (i < end) {
            val byte = msg[i].toInt() and 0xFF

            // System Real-Time (0xF8..0xFF): one byte, may interleave anywhere.
            if (byte >= 0xF8) { i++; continue }

            val status: Int
            if (byte and 0x80 != 0) {
                // System Common (0xF0..0xF7) cancels running status, and its
                // payload length varies — skip to the next status byte.
                if (byte >= 0xF0) {
                    runningStatus = 0
                    i++
                    while (i < end && (msg[i].toInt() and 0x80) == 0) i++
                    continue
                }
                status = byte
                runningStatus = byte
                i++
            } else {
                // A data byte with no status of its own continues the last
                // channel message. Without one there is nothing to continue.
                if (runningStatus == 0) { i++; continue }
                status = runningStatus
            }

            val expected = dataByteCount(status)
            val data = IntArray(expected)
            var got = 0
            while (got < expected && i < end) {
                val next = msg[i].toInt() and 0xFF
                if (next >= 0xF8) { i++; continue }      // interleaved real-time
                if (next and 0x80 != 0) break            // truncated message
                data[got++] = next
                i++
            }
            // A message cut short by the end of the buffer ends the walk.
            if (got < expected) break

            val kind = status and 0xF0
            if (kind != 0x80 && kind != 0x90) continue
            val paddle = paddleForNote(data[0]) ?: continue
            // Note On with velocity 0 is the conventional Note Off.
            out.add(MidiKeyMessage(paddle, data[0], kind == 0x90 && data[1] > 0))
        }

        return out
    }

    /** Data bytes carried by a channel message, by status nibble. */
    private fun dataByteCount(status: Int): Int = when (status and 0xF0) {
        0xC0, 0xD0 -> 1     // Program Change, Channel Pressure
        else -> 2           // Note Off/On, Aftertouch, CC, Pitch Bend
    }
}
