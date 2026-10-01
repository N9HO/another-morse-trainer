package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.MidiKeyParser
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Transmitter

/**
 * Listens for key events from a MIDI keyer (the Vail Adapter) and reports each
 * as a simple key-down/up, for hardware sending practice.
 *
 * The Vail Adapter sends MIDI note-on/off on channel 1: note 0 = straight key,
 * 1/20/61 = dit paddle, 2/21/62 = dah paddle; velocity > 0 = pressed. The adapter
 * does any iambic timing itself, so — exactly like the iOS `MIDIInput` →
 * `SendingKeyer` path — every keyer note maps to a single logical key and
 * [MorseDecoder] times it. Each note's state is tracked separately, though, so
 * overlapping paddle presses hold the key: it goes down on the first press and
 * up only when the last paddle is released.
 *
 * Desktop port of the Android `MidiKeyInput` (`android.media.midi` →
 * `javax.sound.midi`). Java Sound has no device-added callback, so
 * [MidiPorts.Watch] polls for hot-plug; everything else — the per-note hold,
 * the stuck-key release on unplug, the parser — is the Android code. Callbacks
 * are marshaled to the UI thread so they can drive Compose state.
 */
class MidiKeyInput {

    private val main = MainHandler()

    private class Open(val key: String, val name: String, val device: MidiDevice, val transmitter: Transmitter)

    // Touched only on the UI thread (connect/disconnect post there).
    private val open = mutableListOf<Open>()
    private var onKey: ((Boolean) -> Unit)? = null
    private var onConnected: ((String?) -> Unit)? = null
    private var watch: MidiPorts.Watch? = null

    /**
     * Also fired (on the UI thread) on every change of the one logical key,
     * with the time the key event happened in [System.nanoTime] terms. Java
     * Sound's input timestamps are unreliable across providers (-1 on ALSA,
     * device-relative on Windows), so this is the arrival time on the MIDI
     * thread, before the hop to the UI thread. Set before [start]; cleared by [stop].
     */
    var onKeyTimed: ((isDown: Boolean, atNanos: Long) -> Unit)? = null

    /** Per-paddle key state: the keyer notes currently held down. */
    private val heldNotes = mutableSetOf<Int>()

    /** Java Sound always carries a MIDI system on Windows and Linux. */
    val isSupported: Boolean get() = !MidiPorts.isUnavailable

    /**
     * True when MIDI cannot be used here at all — Java Sound's MIDI system
     * failed to load — as opposed to nothing plugged in yet, which [start]'s
     * `onConnected` reports as null.
     */
    val isUnavailable: Boolean get() = MidiPorts.isUnavailable

    /**
     * Begin listening. [onKey] fires (on the UI thread) with `true` on key-down
     * and `false` on key-up; [onConnected] reports the connected device's name —
     * null when none is attached yet, and again when the last one unplugs.
     */
    fun start(onKey: (Boolean) -> Unit, onConnected: (String?) -> Unit) {
        this.onKey = onKey
        this.onConnected = onConnected
        if (!isSupported) { onConnected(null); return }
        onConnected(null)
        val w = MidiPorts.Watch(
            sources = true,
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
        heldNotes.clear()
        MidiPorts.runOnWorker {
            closing.forEach {
                runCatching { it.transmitter.close() }
                runCatching { it.device.close() }
            }
        }
        onKey = null; onConnected = null; onKeyTimed = null
    }

    /** On the MIDI worker thread: open the port, then hand it to the UI thread. */
    private fun connect(port: MidiPorts.Port) {
        val device = try { MidiSystem.getMidiDevice(port.info) } catch (_: Exception) { return }
        val transmitter = try {
            if (!device.isOpen) device.open()
            device.transmitter
        } catch (_: Exception) {
            // Held by another program (WinMM and ALSA raw MIDI are exclusive).
            runCatching { device.close() }
            return
        }
        transmitter.receiver = KeyReceiver()
        main.post {
            if (watch == null) {
                // Stopped while this was opening.
                runCatching { transmitter.close() }
                runCatching { device.close() }
                return@post
            }
            open.add(Open(port.key, port.name, device, transmitter))
            onConnected?.invoke(port.name)
        }
    }

    /** Unplug detection: drop the device, release a stuck key, update the UI. */
    private fun disconnect(key: String) {
        val idx = open.indexOfFirst { it.key == key }
        if (idx < 0) return
        val gone = open.removeAt(idx)
        MidiPorts.runOnWorker {
            runCatching { gone.transmitter.close() }
            runCatching { gone.device.close() }
        }
        // The unplugged key can't send its note-offs any more.
        if (heldNotes.isNotEmpty()) {
            heldNotes.clear()
            onKey?.invoke(false)
            onKeyTimed?.invoke(false, System.nanoTime())
        }
        onConnected?.invoke(open.lastOrNull()?.name)
    }

    /** Aggregate per-note state into one logical key: down while ANY note is held. */
    private fun updateHeld(note: Int, isDown: Boolean, atNanos: Long) {
        val wasHeld = heldNotes.isNotEmpty()
        if (isDown) heldNotes.add(note) else heldNotes.remove(note)
        val nowHeld = heldNotes.isNotEmpty()
        if (nowHeld != wasHeld) {
            onKey?.invoke(nowHeld)
            onKeyTimed?.invoke(nowHeld, atNanos)
        }
    }

    /**
     * Parses each incoming message into Vail-style key down/up events. Java
     * Sound delivers one message per call, but the walk through
     * [MidiKeyParser] is kept so the note mapping stays the tested one.
     */
    private inner class KeyReceiver : Receiver {
        override fun send(message: MidiMessage, timeStamp: Long) {
            val at = System.nanoTime()
            val bytes = message.message ?: return
            val events = MidiKeyParser.messages(bytes, 0, message.length)
            if (events.isEmpty()) return
            main.post { events.forEach { updateHeld(it.note, it.isDown, at) } }
        }

        override fun close() {}
    }
}
