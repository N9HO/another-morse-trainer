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

/**
 * Listens for key events from a MIDI keyer (the Vail Adapter or a BLE-MIDI key)
 * and reports each as a simple key-down/up, for hardware sending practice.
 *
 * The Vail Adapter sends MIDI note-on/off on channel 1: note 0 = straight key,
 * 1/20/61 = dit paddle, 2/21/62 = dah paddle; velocity > 0 = pressed. The adapter
 * does any iambic timing itself, so — exactly like the iOS `MIDIInput` →
 * `SendingKeyer` path — every keyer note maps to a single logical key and
 * [MorseDecoder] times it. Each note's state is tracked separately, though, so
 * overlapping paddle presses hold the key: it goes down on the first press and
 * up only when the last paddle is released.
 *
 * Port of MorseTrainerApp/MIDIInput.swift (CoreMIDI → `android.media.midi`).
 * Callbacks are marshaled to the main thread so they can drive Compose state.
 */
class MidiKeyInput(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var manager: MidiManager? = null

    private class Open(val id: Int, val name: String?, val device: MidiDevice, val port: MidiOutputPort)

    private val open = mutableListOf<Open>()
    private var onKey: ((Boolean) -> Unit)? = null
    private var onConnected: ((String?) -> Unit)? = null

    /** Per-paddle key state: the keyer notes currently held down. */
    private val heldNotes = mutableSetOf<Int>()

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = connect(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = disconnect(device)
    }

    /** True on devices that expose any MIDI support at all. */
    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)

    /**
     * Begin listening. [onKey] fires (on the main thread) with `true` on key-down
     * and `false` on key-up; [onConnected] reports the connected device's name —
     * null when none is attached yet, and again when the last one unplugs.
     */
    // getDevices + the Handler-based registerDeviceCallback are deprecated in API
    // 33 in favor of transport/executor variants that need API 33 — the older
    // forms are the correct cross-version choice at minSdk 24.
    @Suppress("DEPRECATION")
    fun start(onKey: (Boolean) -> Unit, onConnected: (String?) -> Unit) {
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

    fun stop() {
        manager?.let { try { it.unregisterDeviceCallback(deviceCallback) } catch (_: Exception) {} }
        open.forEach {
            try { it.port.close() } catch (_: Exception) {}
            try { it.device.close() } catch (_: Exception) {}
        }
        open.clear()
        heldNotes.clear()
        manager = null
        onKey = null; onConnected = null
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
        // The unplugged key can't send its note-offs any more.
        if (heldNotes.isNotEmpty()) {
            heldNotes.clear()
            onKey?.invoke(false)
        }
        onConnected?.invoke(open.lastOrNull()?.name)
    }

    /** Aggregate per-note state into one logical key: down while ANY note is held. */
    private fun updateHeld(note: Int, isDown: Boolean) {
        val wasHeld = heldNotes.isNotEmpty()
        if (isDown) heldNotes.add(note) else heldNotes.remove(note)
        val nowHeld = heldNotes.isNotEmpty()
        if (nowHeld != wasHeld) onKey?.invoke(nowHeld)
    }

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
            main.post { events.forEach { updateHeld(it.note, it.isDown) } }
        }
    }
}
