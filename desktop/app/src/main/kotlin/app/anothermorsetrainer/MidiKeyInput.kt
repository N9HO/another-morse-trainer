package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.MidiKeyParser
import app.anothermorsetrainer.morsekit.MidiKeyRoute
import app.anothermorsetrainer.morsekit.PaddleKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Transmitter

/**
 * Listens for key events from a MIDI keyer (the Vail Adapter) and reports each
 * as a simple key-down/up, for hardware sending practice.
 *
 * Supported keys send MIDI note-on/off; velocity > 0 = pressed. Straight = note
 * 0 (the Vail Adapter's straight key) or 30 (Morserino-32 Pocket, keyer in
 * Straight Key mode); dit = 1/20/61, dah = 2/21/62 (see
 * [MidiKeyParser.paddleForNote]). Everything comes out as one logical key, the
 * way a Vail Adapter in a keyer mode sends it, built from two kinds of note
 * ([MidiKeyParser.routeForNote], #380):
 *
 * - Held notes key for as long as they are held: the straight keys, and 1/2
 *   while the adapter Keyer mode is anything but Passthrough (there the
 *   adapter has already timed each element, or the paddle is a straight key).
 *   Each note's state is tracked separately, so overlapping presses hold the
 *   key: down on the first press, up only when the last is released.
 * - Raw paddle contacts — 1/2 from a Vail Adapter in Passthrough, 20/21 from
 *   an N6ARA TinyMIDI or a Morserino-32 Pocket, 61/62 — go into a
 *   [PaddleKeyerDriver], the on-screen paddles' keyer, at the Settings paddle
 *   mode and character speed, so a squeeze is iambic keying rather than one
 *   long tone. Its elements key the same logical key.
 *
 * Desktop port of the Android `MidiKeyInput` (`android.media.midi` →
 * `javax.sound.midi`). Java Sound has no device-added callback, so
 * [MidiPorts.Watch] polls for hot-plug; everything else — the per-note hold,
 * the stuck-key release on unplug, the raw-paddle keyer, the parser — is the
 * Android code. Callbacks are marshaled to the UI thread so they can drive
 * Compose state.
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

    /**
     * Fired (on the UI thread) when a raw paddle press goes to the app's
     * keyer: the elements that follow are the app's, made at the Settings
     * speed, which the Sending Analyzer judges as paddles through a keyer.
     * Set before [start]; cleared by [stop].
     */
    var onAppKeyer: (() -> Unit)? = null

    /** Per-note key state: the held (straight-routed) notes currently down. */
    private val heldNotes = mutableSetOf<Int>()

    /**
     * Raw paddle notes currently down, by the keyer paddle each was routed to
     * when it went down — so its release goes the same way even if the keyer
     * mode changed while it was held, and two notes on one paddle (1 and 20)
     * hold it until both are up.
     */
    private val keyedNotes = mutableMapOf<Int, PaddleKeyer.Element>()
    private var keyerScope: CoroutineScope? = null
    private var paddles: PaddleKeyerDriver? = null
    /** Whether the app keyer's tone is on. */
    private var keyerDown = false
    /** The logical key as last reported. */
    private var reported = false

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
        startKeyer()
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
        stopKeyer()
        reported = false
        onKey = null; onConnected = null; onKeyTimed = null; onAppKeyer = null
    }

    private fun startKeyer() {
        if (paddles != null) return
        val scope = MainScope()
        keyerScope = scope
        paddles = PaddleKeyerDriver(Settings.paddleMode, Settings.characterWpm, scope).also {
            it.onKey = { down, atMs -> keyerEdge(down, atMs) }
        }
    }

    private fun stopKeyer() {
        paddles?.let { it.onKey = null; it.stop() }
        paddles = null
        keyerScope?.cancel()
        keyerScope = null
        keyedNotes.clear()
        keyerDown = false
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
        // The unplugged key can't send its note-offs any more: let go of its
        // held notes and of the keyer's paddles (which ends any tone at once).
        heldNotes.clear()
        if (keyedNotes.isNotEmpty()) {
            keyedNotes.clear()
            paddles?.stop()
        }
        report(System.nanoTime())
        onConnected?.invoke(open.lastOrNull()?.name)
    }

    /**
     * One note went down or up: hold it as a key, or hand it to the app's
     * paddle keyer, as [MidiKeyParser.routeForNote] says for the adapter Keyer
     * mode in use when it went down (#380).
     */
    private fun handleNote(note: Int, isDown: Boolean, atNanos: Long) {
        if (!isDown) {
            val element = keyedNotes.remove(note)
            if (element != null) {
                paddles?.paddle(element, keyedNotes.containsValue(element))
            } else if (heldNotes.remove(note)) {
                report(atNanos)
            }
            return
        }
        val raw = AdapterKeyer.sendsRawPaddles(AdapterKeyer.mode())
        when (MidiKeyParser.routeForNote(note, raw)) {
            null -> return
            MidiKeyRoute.HELD -> {
                heldNotes.add(note)
                report(atNanos)
            }
            MidiKeyRoute.KEYER_DIT -> keyerPress(note, PaddleKeyer.Element.DIT)
            MidiKeyRoute.KEYER_DAH -> keyerPress(note, PaddleKeyer.Element.DAH)
        }
    }

    private fun keyerPress(note: Int, element: PaddleKeyer.Element) {
        val driver = paddles ?: return
        // Follow Settings, as the on-screen paddles do; the driver applies a
        // change only once it is idle, so an element in flight keeps its timing.
        driver.configure(Settings.paddleMode, Settings.characterWpm)
        keyedNotes[note] = element
        onAppKeyer?.invoke()
        driver.paddle(element, true)
    }

    /**
     * An edge from the app's keyer, at the time it scheduled it
     * ([System.currentTimeMillis] terms, as [PaddleKeyerDriver] keeps it),
     * moved onto the [System.nanoTime] clock [onKeyTimed] reports in.
     */
    private fun keyerEdge(isDown: Boolean, atWallMs: Long) {
        keyerDown = isDown
        report(System.nanoTime() - (System.currentTimeMillis() - atWallMs) * 1_000_000L)
    }

    /**
     * Aggregate into one logical key: down while any held note is down or the
     * keyer's tone is on, reported only when that changes.
     */
    private fun report(atNanos: Long) {
        val now = heldNotes.isNotEmpty() || keyerDown
        if (now == reported) return
        reported = now
        onKey?.invoke(now)
        onKeyTimed?.invoke(now, atNanos)
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
            main.post { events.forEach { handleNote(it.note, it.isDown, at) } }
        }

        override fun close() {}
    }
}
