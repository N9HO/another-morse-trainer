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
import app.anothermorsetrainer.morsekit.MidiKeyPaddle
import app.anothermorsetrainer.morsekit.MidiKeyParser
import app.anothermorsetrainer.morsekit.MidiKeyer
import app.anothermorsetrainer.morsekit.PaddleKeyer
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Listens for key events from a MIDI keyer (the Vail Adapter or a BLE-MIDI key)
 * and reports each as a simple key-down/up, for hardware sending practice.
 *
 * Supported keys send MIDI note-on/off; velocity > 0 = pressed. Straight = note
 * 0 (the Vail Adapter's straight key, and every element its keyer modes send,
 * already timed) or 30 (Morserino-32 Pocket, keyer in Straight Key mode); dit =
 * 1/20/61, dah = 2/21/62 (see [MidiKeyParser.paddleForNote]). This runs
 * [MidiKeyer] against the clock: a straight note keys as it comes, and dit and
 * dah from a paddle with no keyer of its own are timed in the on-screen
 * paddles' mode and speed: 1/2 from a Vail Adapter in Passthrough, 20/21 from
 * the N6ARA TinyMIDI or the Pocket. A Vail Adapter's own keyer sends timed
 * elements on 1/2 outside Passthrough, and those key as they come
 * ([MidiKeyer.timesNote]).
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
     * MIDI packet's own timestamp, not when the main thread got round to it,
     * or for a paddle the time the app's keyer scheduled the element for.
     * `timedByApp` is true for the paddle's elements, which the Sending
     * Analyzer judges as a keyer. The Sending Analyzer times the operator's
     * fist from these. Set before [start]; cleared by [stop].
     */
    var onKeyTimed: ((isDown: Boolean, atNanos: Long, timedByApp: Boolean) -> Unit)? = null

    /**
     * The key's contacts as one key. Its clock is [System.nanoTime] in ms, so
     * its edges are already on [onKeyTimed]'s clock. Main thread only.
     */
    private val keyer = MidiKeyer(PaddleKeyer.Mode.IAMBIC_A, 20.0)
    private val tick = Runnable { onTick() }

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
     * and `false` on key-up, and `atMs`, the edge's own wall-clock time (a
     * paddle's elements are timed by this app's keyer); [onConnected] reports the connected device's name —
     * null when none is attached yet, and again when the last one unplugs.
     */
    // getDevices + the Handler-based registerDeviceCallback are deprecated in API
    // 33 in favor of transport/executor variants that need API 33 — the older
    // forms are the correct cross-version choice at minSdk 24.
    @Suppress("DEPRECATION")
    fun start(onKey: (isDown: Boolean, atMs: Long) -> Unit, onConnected: (String?) -> Unit) {
        this.onKey = onKey
        this.onConnected = onConnected
        if (!isSupported) { onConnected(null); return }
        val mgr = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: run {
            onConnected(null); return
        }
        manager = mgr
        mgr.devices.forEach { connect(it) }
        mgr.registerDeviceCallback(deviceCallback, main)
        if (open.isEmpty()) onConnected(null)
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
        manager?.let { try { it.unregisterDeviceCallback(deviceCallback) } catch (_: Exception) {} }
        open.forEach {
            try { it.port.close() } catch (_: Exception) {}
            try { it.device.close() } catch (_: Exception) {}
        }
        open.clear()
        main.removeCallbacks(tick)
        // Let go of whatever was held, silently: the callbacks go too.
        keyer.releaseAll(nowMs())
        manager = null
        onKey = null; onConnected = null; onKeyTimed = null
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
        // The unplugged key can't send its note-offs any more: let go of
        // whatever it left held and cut a tone in progress.
        emit(keyer.releaseAll(nowMs()))
        schedule()
        onConnected?.invoke(open.lastOrNull()?.name)
    }

    /**
     * One contact of the key, on the main thread. A contact keyed as it comes
     * (the straight-key note, or a Vail Adapter's own timed elements on notes
     * 1 and 2 outside Passthrough: [MidiKeyer.timesNote]) is stamped with the
     * packet's own time; a timed paddle contact is timed from now, because its
     * element is scheduled from the moment the keyer hears it.
     */
    private fun contact(paddle: MidiKeyPaddle, note: Int, isDown: Boolean, atNanos: Long) {
        // Stopped: a note posted before stop() must not start an element
        // whose note-off can no longer arrive, or the keyer clocks forever.
        if (manager == null) return
        val timed = MidiKeyer.timesNote(note, adapterPassthrough = AdapterKeyer.mode(context) == MidiKeyOutput.KeyerMode.PASSTHROUGH)
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
     * Parses raw MIDI bytes into Vail-style key down/up events.
     *
     * One [onSend] can carry several messages — that is what offset/count are
     * for, and a BLE-MIDI key batches events into a single radio burst — so the
     * whole buffer is walked. Reading only the first three bytes dropped a
     * key-up that shared a burst with its key-down, and since the key is held
     * while any note is down, the dropped release left it stuck. Note mapping
     * and the byte walk live in [MidiKeyParser] so they can be tested.
     */
    private inner class KeyReceiver : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val events = MidiKeyParser.messages(msg, offset, count)
            if (events.isEmpty()) return
            // The packet's timestamp is System.nanoTime()-based; a sender that
            // leaves it at 0 gets the arrival time instead.
            val at = if (timestamp > 0) timestamp else System.nanoTime()
            main.post { events.forEach { contact(it.paddle, it.note, it.isDown, at) } }
        }
    }
}
