package app.anothermorsetrainer

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import app.anothermorsetrainer.morsekit.MidiKeyParser
import app.anothermorsetrainer.morsekit.MidiKeyRoute
import app.anothermorsetrainer.morsekit.PaddleKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Listens for key events from a MIDI keyer (the Vail Adapter or a BLE-MIDI key)
 * and reports each as a simple key-down/up, for hardware sending practice.
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
 * Port of MorseTrainerApp/MIDIInput.swift (CoreMIDI → `android.media.midi`).
 * Callbacks are marshaled to the main thread so they can drive Compose state.
 */
class MidiKeyInput(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var manager: MidiManager? = null

    private class Open(val id: Int, val name: String?, val device: MidiDevice, val port: MidiOutputPort)

    private val open = mutableListOf<Open>()
    private var onKey: ((isDown: Boolean, atMs: Long) -> Unit)? = null
    private var onConnected: ((String?) -> Unit)? = null

    /**
     * Also fired (on the main thread) on every change of the one logical key,
     * with the time the key event happened in [System.nanoTime] terms — the
     * MIDI packet's own timestamp, not when the main thread got round to it.
     * The Sending Analyzer times the operator's fist from these. Set before
     * [start]; cleared by [stop].
     */
    var onKeyTimed: ((isDown: Boolean, atNanos: Long) -> Unit)? = null

    /**
     * Fired (on the main thread) when a raw paddle press goes to the app's
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

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = connect(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = disconnect(device)
    }

    /** True on devices that expose any MIDI support at all. */
    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)

    /**
     * True when MIDI cannot be used on this device at all — no
     * [PackageManager.FEATURE_MIDI], or no [MidiManager] behind it — so a
     * hardware key will never work here. Distinct from simply having nothing
     * plugged in yet, which [start]'s `onConnected` reports as null. The
     * counterpart of the iOS `SendingKeyer.midiUnavailable`, which is set when
     * CoreMIDI setup itself throws.
     */
    val isUnavailable: Boolean
        get() = !isSupported || context.getSystemService(Context.MIDI_SERVICE) !is MidiManager

    /**
     * Begin listening. [onKey] fires (on the main thread) with `true` on key-down
     * and `false` on key-up, and `atMs`, the edge's own wall-clock time; [onConnected] reports the connected device's name —
     * null when none is attached yet, and again when the last one unplugs.
     */
    // getDevices + the Handler-based registerDeviceCallback are deprecated in API
    // 33 in favor of transport/executor variants that need API 33 — the older
    // forms are the correct cross-version choice at minSdk 24.
    @Suppress("DEPRECATION")
    fun start(onKey: (isDown: Boolean, atMs: Long) -> Unit, onConnected: (String?) -> Unit) {
        this.onKey = onKey
        this.onConnected = onConnected
        startKeyer()
        if (!isSupported) { onConnected(null); return }
        val mgr = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: run {
            onConnected(null); return
        }
        manager = mgr
        mgr.devices.forEach { connect(it) }
        mgr.registerDeviceCallback(deviceCallback, main)
        if (open.isEmpty()) onConnected(null)
    }

    fun stop() {
        manager?.let { try { it.unregisterDeviceCallback(deviceCallback) } catch (_: Exception) {} }
        open.forEach {
            try { it.port.close() } catch (_: Exception) {}
            try { it.device.close() } catch (_: Exception) {}
        }
        open.clear()
        heldNotes.clear()
        stopKeyer()
        reported = false
        manager = null
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

    private fun connect(info: MidiDeviceInfo) {
        if (info.outputPortCount <= 0) return   // we read FROM the key's output port
        if (open.any { it.id == info.id }) return
        val mgr = manager ?: return
        mgr.openDevice(info, { device ->
            if (device == null) return@openDevice
            val port = device.openOutputPort(0) ?: run {
                try { device.close() } catch (_: Exception) {}
                return@openDevice
            }
            port.connect(KeyReceiver())
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            open.add(Open(info.id, name, device, port))
            main.post { onConnected?.invoke(name) }
        }, main)
    }

    /** Unplug detection: drop the device, release a stuck key, update the UI. */
    private fun disconnect(info: MidiDeviceInfo) {
        val idx = open.indexOfFirst { it.id == info.id }
        if (idx < 0) return
        val gone = open.removeAt(idx)
        try { gone.port.close() } catch (_: Exception) {}
        try { gone.device.close() } catch (_: Exception) {}
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
        val raw = AdapterKeyer.sendsRawPaddles(AdapterKeyer.mode(context))
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
        // The edge's own time in wall-clock ms (the packet's, or when the
        // keyer scheduled the element), so a late main-thread hop can't
        // change an element's length. iOS passes `atMs` the same way.
        onKey?.invoke(now, System.currentTimeMillis() - (System.nanoTime() - atNanos) / 1_000_000L)
        onKeyTimed?.invoke(now, atNanos)
    }

    /**
     * Parses raw MIDI bytes into Vail-style key down/up events.
     *
     * One [onSend] can carry several messages — that is what offset/count are
     * for, and a BLE-MIDI key batches events into a single radio burst — so the
     * whole buffer is walked. Reading only the first three bytes dropped a
     * key-up that shared a burst with its key-down, and since the key is held
     * while a note is down, the dropped release left it stuck. Note mapping
     * and the byte walk live in [MidiKeyParser] so they can be tested.
     */
    private inner class KeyReceiver : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val events = MidiKeyParser.messages(msg, offset, count)
            if (events.isEmpty()) return
            // The packet's timestamp is System.nanoTime()-based; a sender that
            // leaves it at 0 gets the arrival time instead.
            val at = if (timestamp > 0) timestamp else System.nanoTime()
            main.post { events.forEach { handleNote(it.note, it.isDown, at) } }
        }
    }
}
