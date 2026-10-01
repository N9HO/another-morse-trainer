package app.anothermorsetrainer

import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage

/**
 * MIDI **output** to the Vail Adapter — the counterpart to [MidiKeyInput].
 *
 * The adapter boots in HID keyboard mode and emits Ctrl key up/down events. It
 * also enumerates as a MIDI device. Sending any Control Change switches it into
 * MIDI mode and suppresses the keyboard output, so on connect we broadcast the
 * init sequence to every device input port — matching the web client and the
 * iOS `MIDIOutput`.
 *
 * Outbound message vocabulary (channel 1):
 * ```
 *   B0 00 vv   Mode: vv >= 0x40 = Keyboard, vv < 0x40 = MIDI (we send 0x00)
 *   B0 01 vv   Dit duration: ms = vv * 2
 *   B0 02 vv   Sidetone MIDI note (also drives the adapter's piezo)
 *   C0 vv      Keyer mode (Program Change)
 *   90 NN 7F   Buzz the adapter at MIDI note NN (RX feedback)
 *   80 NN 00   Silence the adapter
 * ```
 *
 * Port of MorseTrainerApp/MIDIOutput.swift (CoreMIDI → `android.media.midi`),
 * and on desktop from the Android port to `javax.sound.midi`: ports are found by
 * [MidiPorts.Watch] polling instead of a device callback, and the RX buzz is
 * timed by the MIDI worker thread because Java Sound receivers do not honour
 * send timestamps.
 */
class MidiKeyOutput {

    /** Keyer mode set on the adapter via Program Change. Values match the Vail firmware. */
    enum class KeyerMode(val code: Int, val displayName: String) {
        PASSTHROUGH(0, "Passthrough"),
        STRAIGHT_KEY(1, "Straight Key"),
        BUG(2, "Bug"),
        ELECTRIC_BUG(3, "Electric Bug"),
        SINGLE_DOT(4, "Single Dot"),
        ULTIMATIC(5, "Ultimatic"),
        PLAIN_IAMBIC(6, "Plain Iambic"),
        IAMBIC_A(7, "Iambic A"),
        IAMBIC_B(8, "Iambic B"),
        KEYAHEAD(9, "Keyahead");

        companion object {
            fun fromCode(code: Int): KeyerMode = entries.firstOrNull { it.code == code } ?: STRAIGHT_KEY
        }
    }

    private val main = MainHandler()

    private class Open(val key: String, val name: String?, val device: MidiDevice, val port: Receiver)

    // Touched only on the UI thread.
    private val open = mutableListOf<Open>()
    private var onConnected: ((String?) -> Unit)? = null
    private var watch: MidiPorts.Watch? = null

    // Mirrored config, (re)applied whenever the adapter (re)connects.
    private var ditDurationMs = 60          // 20 WPM (1200 / 20)
    private var keyerMode = KeyerMode.STRAIGHT_KEY
    private var sidetoneMidiNote = 72       // C5

    /** Does this device look like the Vail Adapter (by advertised name)? */
    private fun isVail(name: String?): Boolean =
        name.orEmpty().lowercase().contains("vail")

    /**
     * The ports whose piezo we may buzz: any recognized Vail Adapter — or, when
     * nothing identifies itself as one, a single connected device is assumed to
     * be it. Never the whole port list, so an unrelated synth stays silent.
     */
    private fun buzzTargets(): List<Open> =
        open.filter { isVail(it.name) }.ifEmpty { if (open.size == 1) open.toList() else emptyList() }

    /** Java Sound always carries a MIDI system on Windows and Linux. */
    val isSupported: Boolean get() = !MidiPorts.isUnavailable

    /** Apply settings without sending — seeds config before the first connect. */
    fun configure(keyerMode: KeyerMode, wpm: Int, sidetoneMidiNote: Int) {
        this.keyerMode = keyerMode
        this.ditDurationMs = ditDurationMs(wpm)
        this.sidetoneMidiNote = sidetoneMidiNote.coerceIn(0, 127)
    }

    /**
     * Apply settings to an adapter that is already connected, sending only what
     * actually changed. This is the live counterpart to [configure]: the wake
     * sequence pushes the whole config once, and this keeps it current while
     * the port stays open, so a mode or speed picked mid-session reaches the
     * adapter instead of waiting for the next wake (issue #46).
     *
     * The mirrored fields are the comparison, so a repeated call with unchanged
     * values costs nothing on the wire, and a change made before anything is
     * connected still lands — it updates the mirror the next wake will send.
     */
    fun applyConfig(keyerMode: KeyerMode, wpm: Int, sidetoneMidiNote: Int) {
        if (keyerMode != this.keyerMode) setKeyerMode(keyerMode)
        if (ditDurationMs(wpm) != this.ditDurationMs) setSpeed(wpm)
        if (sidetoneMidiNote.coerceIn(0, 127) != this.sidetoneMidiNote) setSidetone(sidetoneMidiNote)
    }

    /** Begin: open every device input port and broadcast the init sequence. */
    fun start(onConnected: (String?) -> Unit) {
        this.onConnected = onConnected
        if (!isSupported) { onConnected(null); return }
        onConnected(null)
        val w = MidiPorts.Watch(
            sources = false,
            onAdded = { port -> connect(port) },
            onRemoved = { port -> main.post { disconnect(port.key) } }
        )
        watch = w
        w.start()
    }

    fun stop() {
        watch?.stop()
        watch = null
        val closing = open.toList()
        open.clear()
        MidiPorts.runOnWorker {
            closing.forEach {
                runCatching { it.port.close() }
                runCatching { it.device.close() }
            }
        }
        onConnected = null
    }

    // ---- Configuration pushes ----

    fun setKeyerMode(mode: KeyerMode) {
        keyerMode = mode
        broadcast(byteArrayOf(0xC0.toByte(), mode.code.toByte()))
    }

    fun setSpeed(wpm: Int) {
        ditDurationMs = ditDurationMs(wpm)
        broadcast(byteArrayOf(0xB0.toByte(), 0x01, minOf(127, ditDurationMs / 2).toByte()))
    }

    fun setSidetone(midiNote: Int) {
        sidetoneMidiNote = midiNote.coerceIn(0, 127)
        broadcast(byteArrayOf(0xB0.toByte(), 0x02, sidetoneMidiNote.toByte()))
    }

    /** User-triggered retry of the wake/identify sequence. */
    fun wakeAdapter() {
        val ports = open.map { it.port }
        MidiPorts.runOnWorker { ports.forEach { sendInitSequence(it) } }
    }

    /**
     * RX piezo feedback: buzz the adapter for a received tone, scheduled to land
     * at the same local time as the audio playback. [playAtLocalMs] is a
     * [System.currentTimeMillis] timestamp; note-on/off are timestamped in the
     * [System.nanoTime] base CoreMIDI/`android.media.midi` use for scheduling.
     */
    fun scheduleBuzz(note: Int, durationMs: Int, playAtLocalMs: Long) {
        if (durationMs <= 0) return
        val targets = buzzTargets()
        if (targets.isEmpty()) return
        val clamped = note.coerceIn(0, 127)
        val leadMs = (playAtLocalMs - System.currentTimeMillis()).coerceAtLeast(0)
        // Java Sound receivers send immediately whatever timestamp they are
        // given, so the MIDI worker thread holds each message until its time.
        MidiPorts.schedule(leadMs) {
            targets.forEach { out -> sendNow(out.port, byteArrayOf(0x90.toByte(), clamped.toByte(), 0x7F)) }
        }
        MidiPorts.schedule(leadMs + durationMs) {
            targets.forEach { out -> sendNow(out.port, byteArrayOf(0x80.toByte(), clamped.toByte(), 0x00)) }
        }
    }

    // ---- Device discovery ----

    /** On the MIDI worker thread: open the adapter's input, wake it, hand it to the UI thread. */
    private fun connect(p: MidiPorts.Port) {
        val lower = p.name.lowercase()
        // Skip CoreMIDI-style network sessions (no analogue here, but be safe).
        if (lower.contains("network") && lower.contains("session")) return
        val device = try { MidiSystem.getMidiDevice(p.info) } catch (_: Exception) { return }
        val port = try {
            if (!device.isOpen) device.open()
            device.receiver
        } catch (_: Exception) {
            runCatching { device.close() }
            return
        }
        sendInitSequence(port)
        main.post {
            if (watch == null) {
                runCatching { port.close() }
                runCatching { device.close() }
                return@post
            }
            open.add(Open(p.key, p.name, device, port))
            onConnected?.invoke(p.name)
        }
    }

    /** Unplug detection: drop the device and report what (if anything) remains. */
    private fun disconnect(key: String) {
        val idx = open.indexOfFirst { it.key == key }
        if (idx < 0) return
        val gone = open.removeAt(idx)
        MidiPorts.runOnWorker {
            runCatching { gone.port.close() }
            runCatching { gone.device.close() }
        }
        onConnected?.invoke(open.lastOrNull()?.name)
    }

    // ---- Sending ----

    /** Disable keyboard mode, set dit duration, set keyer mode, set sidetone. */
    private fun sendInitSequence(port: Receiver) {
        sendNow(port, byteArrayOf(0xB0.toByte(), 0x00, 0x00))                                   // MIDI mode
        sendNow(port, byteArrayOf(0xB0.toByte(), 0x01, minOf(127, ditDurationMs / 2).toByte()))
        sendNow(port, byteArrayOf(0xC0.toByte(), keyerMode.code.toByte()))
        sendNow(port, byteArrayOf(0xB0.toByte(), 0x02, sidetoneMidiNote.toByte()))
    }

    private fun broadcast(message: ByteArray) {
        val ports = open.map { it.port }
        MidiPorts.runOnWorker { ports.forEach { sendNow(it, message) } }
    }

    private fun sendNow(port: Receiver, message: ByteArray) {
        try {
            val msg = ShortMessage()
            when (message.size) {
                3 -> msg.setMessage(message[0].toInt() and 0xFF, message[1].toInt() and 0xFF, message[2].toInt() and 0xFF)
                2 -> msg.setMessage(message[0].toInt() and 0xFF, message[1].toInt() and 0xFF, 0)
                else -> return
            }
            port.send(msg, -1)
        } catch (e: Exception) {
            // Swallowing this silently left "never sent" and "sent and
            // ignored by the adapter" indistinguishable from outside (#107).
            System.err.println("MidiKeyOutput: send failed: ${message.joinToString(" ") { "%02X".format(it) }}: $e")
        }
    }

    // ---- Helpers ----

    /** PARIS standard: dit (ms) = 1200 / WPM. */
    private fun ditDurationMs(wpm: Int): Int = 1200 / maxOf(5, wpm)
}
