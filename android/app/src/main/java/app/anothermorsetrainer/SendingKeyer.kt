package app.anothermorsetrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.MorseDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives "sending practice": an on-screen Morse key plays sidetone and is
 * decoded back to text via [MorseDecoder]. The decoded text answers the current
 * drill. Exposes [decodedText] / [isKeying] as Compose snapshot state.
 *
 * Port of the iOS `SendingKeyer`, minus the MIDI input path (hardware keying is
 * the separate MIDI parity item) — the on-screen key works identically. The
 * idle-finalize timer uses a coroutine on a scope supplied by the screen.
 *
 * Translated from MorseTrainerApp/SendingKeyer.swift.
 */
class SendingKeyer(wpm: Double, toneHz: Double) {

    /** Decoded text so far (finalized characters; in-progress appears on gap). */
    var decodedText by mutableStateOf("")
        private set

    /** True while the key is held down (drives the pressed look). */
    var isKeying by mutableStateOf(false)
        private set

    private val sidetone = SidetoneGenerator(toneHz)
    private val decoder = MorseDecoder(wpm)
    private var keyDownAtMs: Long? = null
    private var idleJob: Job? = null

    /** Set by the screen before keying, for the letter/word-gap finalize timer. */
    var scope: CoroutineScope? = null

    init {
        decoder.onUpdate = { decodedText = it }
    }

    fun start() = sidetone.start()

    fun stop() {
        idleJob?.cancel()
        idleJob = null
        sidetone.stop()
    }

    /** On-screen key press/release. */
    fun touchKey(isDown: Boolean) = handle(isDown, System.currentTimeMillis())

    fun clear() = decoder.reset()

    /** Flush the in-progress character and return the full decoded answer. */
    fun submit(): String = decoder.submit().trim()

    // MARK: - Key handling

    private fun handle(isDown: Boolean, ms: Long) {
        if (isDown) {
            if (keyDownAtMs != null) return
            keyDownAtMs = ms
            isKeying = true
            idleJob?.cancel(); idleJob = null
            sidetone.setKeyDown(true)
        } else {
            val down = keyDownAtMs ?: return
            keyDownAtMs = null
            isKeying = false
            sidetone.setKeyDown(false)
            decoder.ingestTone((ms - down).coerceAtLeast(0).toDouble())
            scheduleIdleFinalize()
        }
    }

    /**
     * After the key is released, a letter-gap of silence finalizes the current
     * character; a longer word-gap of silence adds a space.
     */
    private fun scheduleIdleFinalize() {
        idleJob?.cancel()
        val s = scope ?: return
        val letterGap = decoder.letterGapMs
        val wordGap = decoder.wordGapMs
        idleJob = s.launch {
            delay(letterGap.toLong())
            decoder.finishCharacter()
            delay(maxOf(0L, (wordGap - letterGap).toLong()))
            decoder.ingestGap(decoder.wordGapMs)
        }
    }
}
