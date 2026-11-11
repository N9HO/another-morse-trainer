package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.MidiKeyPaddle
import app.anothermorsetrainer.morsekit.MidiKeyParser
import app.anothermorsetrainer.morsekit.MidiKeyer
import app.anothermorsetrainer.morsekit.PaddleKeyer
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Transmitter
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Listens for key events from a MIDI keyer (the Vail Adapter) and reports each
 * as a simple key-down/up, for hardware sending practice.
 *
 * Supported keys send MIDI note-on/off; velocity > 0 = pressed. Straight = note
 * 0 (the Vail Adapter's straight key, and every element its keyer modes send,
 * already timed) or 30 (Morserino-32 Pocket, keyer in Straight Key mode); dit =
 * 1/20/61, dah = 2/21/62 (see [MidiKeyParser.paddleForNote]). This runs
 * [MidiKeyer] against the clock: a straight note keys as it comes, and dit and
 * dah from a paddle with no keyer of its own are timed in the on-screen
 * paddles' mode and speed: 1/2 from a Vail Adapter in Passthrough, 20/21 from
 * the Pocket over USB (the TinyMIDI is BLE-only). A Vail Adapter's own keyer
 * sends timed elements on 1/2 outside Passthrough, and those key as they come
 * ([MidiKeyer.timesNote]).
 *
 * Desktop port of the Android `MidiKeyInput` (`android.media.midi` →
 * `javax.sound.midi`). Java Sound has no device-added callback, so
 * [MidiPorts.Watch] polls for hot-plug; everything else — the keyer, the
 * stuck-key release on unplug, the parser — is the Android code. Callbacks
 * are marshaled to the UI thread so they can drive Compose state.
 */
class MidiKeyInput {

    private val main = MainHandler()

    private class Open(val key: String, val name: String, val device: MidiDevice, val transmitter: Transmitter)

    // Touched only on the UI thread (connect/disconnect post there).
    private val open = mutableListOf<Open>()
    private var onKey: ((isDown: Boolean, atMs: Long) -> Unit)? = null
    private var onConnected: ((String?) -> Unit)? = null
    private var watch: MidiPorts.Watch? = null

    /**
     * Also fired (on the UI thread) on every change of the one logical key,
     * with the time the key event happened in [System.nanoTime] terms. Java
     * Sound's input timestamps are unreliable across providers (-1 on ALSA,
     * device-relative on Windows), so this is the arrival time on the MIDI
     * thread, before the hop to the UI thread — or, for a paddle, the time the
     * app's keyer scheduled the element for. `timedByApp` is true for the
     * paddle's elements, which the Sending Analyzer judges as a keyer. Set
     * before [start]; cleared by [stop].
     */
    var onKeyTimed: ((isDown: Boolean, atNanos: Long, timedByApp: Boolean) -> Unit)? = null

    /**
     * The key's contacts as one key. Its clock is [System.nanoTime] in ms, so
     * its edges are already on [onKeyTimed]'s clock. UI thread only.
     */
    private val keyer = MidiKeyer(PaddleKeyer.Mode.IAMBIC_A, 20.0)
    private val tick = Runnable { onTick() }

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
     * and `false` on key-up, and `atMs`, the edge's own wall-clock time (a
     * paddle's elements are timed by this app's keyer); [onConnected] reports the connected device's name —
     * null when none is attached yet, and again when the last one unplugs.
     */
    fun start(onKey: (isDown: Boolean, atMs: Long) -> Unit, onConnected: (String?) -> Unit) {
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

    /**
     * Time a paddle at [mode] and [wpm]: the on-screen paddles' settings,
     * pushed by the screen when they change. [MidiKeyer.configure] holds a
     * change made while keying until the keyer is idle.
     */
    fun configure(mode: PaddleKeyer.Mode, wpm: Double) {
        emit(keyer.configure(mode, wpm, nowMs()))
        schedule()
    }

    fun stop() {
        watch?.stop()
        watch = null
        val closing = open.toList()
        open.clear()
        main.removeCallbacks(tick)
        // Let go of whatever was held, silently: the callbacks go too.
        keyer.releaseAll(nowMs())
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
        // The unplugged key can't send its note-offs any more: let go of
        // whatever it left held and cut a tone in progress.
        emit(keyer.releaseAll(nowMs()))
        schedule()
        onConnected?.invoke(open.lastOrNull()?.name)
    }

    /**
     * One contact of the key, on the UI thread. A contact keyed as it comes
     * (the straight-key note, or a Vail Adapter's own timed elements on notes
     * 1 and 2 outside Passthrough: [MidiKeyer.timesNote]) is stamped with its
     * arrival time; a timed paddle contact is timed from now, because its
     * element is scheduled from the moment the keyer hears it.
     */
    private fun contact(paddle: MidiKeyPaddle, note: Int, isDown: Boolean, atNanos: Long) {
        // Stopped: a note posted before stop() must not start an element
        // whose note-off can no longer arrive, or the keyer clocks forever.
        if (watch == null) return
        val timed = MidiKeyer.timesNote(note, adapterPassthrough = AdapterKeyer.mode() == MidiKeyOutput.KeyerMode.PASSTHROUGH)
        val t = if (paddle == MidiKeyPaddle.STRAIGHT || !timed) atNanos / 1_000_000.0 else nowMs()
        emit(keyer.contact(paddle, isDown, t, timed))
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        val deadline = keyer.nextDeadlineMs ?: return
        main.postDelayed(tick, ceil((deadline - nowMs()).coerceAtLeast(0.0)).toLong())
    }

    private fun onTick() {
        val deadline = keyer.nextDeadlineMs ?: return
        emit(keyer.advance(maxOf(deadline, nowMs())))
        schedule()
    }

    private fun emit(edges: List<MidiKeyer.Edge>) {
        for (edge in edges) {
            // The edge's own time (the packet's, or when the keyer scheduled
            // the element) in wall-clock ms, so a late tick can't change an
            // element's length. iOS passes `atMs` the same way.
            onKey?.invoke(edge.isDown, System.currentTimeMillis() + (edge.atMs - nowMs()).roundToLong())
            onKeyTimed?.invoke(edge.isDown, (edge.atMs * 1_000_000.0).roundToLong(), edge.timedByApp)
        }
    }

    private fun nowMs(): Double = System.nanoTime() / 1_000_000.0

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
            main.post { events.forEach { contact(it.paddle, it.note, it.isDown, at) } }
        }

        override fun close() {}
    }
}
