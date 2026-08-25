package app.anothermorsetrainer

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper

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
 * Port of MorseTrainerApp/MIDIOutput.swift (CoreMIDI → `android.media.midi`).
 */
class MidiKeyOutput(private val context: Context) {

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

    private val main = Handler(Looper.getMainLooper())
    private var manager: MidiManager? = null
    private val openDevices = mutableListOf<MidiDevice>()
    private val openPorts = mutableListOf<MidiInputPort>()
    private var onConnected: ((String?) -> Unit)? = null

    // Mirrored config, (re)applied whenever the adapter (re)connects.
    private var ditDurationMs = 60          // 20 WPM (1200 / 20)
    private var keyerMode = KeyerMode.STRAIGHT_KEY
    private var sidetoneMidiNote = 72       // C5

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = connect(device)
    }

    /** True on devices that expose any MIDI support at all. */
    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)

    /** Apply settings without sending — seeds config before the first connect. */
    fun configure(keyerMode: KeyerMode, wpm: Int, sidetoneMidiNote: Int) {
        this.keyerMode = keyerMode
        this.ditDurationMs = ditDurationMs(wpm)
        this.sidetoneMidiNote = sidetoneMidiNote.coerceIn(0, 127)
    }

    /** Begin: open every device input port and broadcast the init sequence. */
    @Suppress("DEPRECATION")
    fun start(onConnected: (String?) -> Unit) {
        this.onConnected = onConnected
        if (!isSupported) { onConnected(null); return }
        val mgr = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: run { onConnected(null); return }
        manager = mgr
        mgr.devices.forEach { connect(it) }
        mgr.registerDeviceCallback(deviceCallback, main)
        if (openPorts.isEmpty()) onConnected(null)
    }

    fun stop() {
        manager?.let { try { it.unregisterDeviceCallback(deviceCallback) } catch (_: Exception) {} }
        openPorts.forEach { try { it.close() } catch (_: Exception) {} }
        openDevices.forEach { try { it.close() } catch (_: Exception) {} }
        openPorts.clear(); openDevices.clear()
        manager = null
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
        openPorts.forEach { sendInitSequence(it) }
    }

    /**
     * RX piezo feedback: buzz the adapter for a received tone, scheduled to land
     * at the same local time as the audio playback. [playAtLocalMs] is a
     * [System.currentTimeMillis] timestamp; note-on/off are timestamped in the
     * [System.nanoTime] base CoreMIDI/`android.media.midi` use for scheduling.
     */
    fun scheduleBuzz(note: Int, durationMs: Int, playAtLocalMs: Long) {
        if (openPorts.isEmpty() || durationMs <= 0) return
        val clamped = note.coerceIn(0, 127)
        val leadMs = (playAtLocalMs - System.currentTimeMillis()).coerceAtLeast(0)
        val onTs = System.nanoTime() + leadMs * 1_000_000L
        val offTs = onTs + durationMs.toLong() * 1_000_000L
        val on = byteArrayOf(0x90.toByte(), clamped.toByte(), 0x7F)
        val off = byteArrayOf(0x80.toByte(), clamped.toByte(), 0x00)
        openPorts.forEach { port ->
            try {
                port.send(on, 0, on.size, onTs)
                port.send(off, 0, off.size, offTs)
            } catch (_: Exception) {}
        }
    }

    // ---- Device discovery ----

    private fun connect(info: MidiDeviceInfo) {
        if (info.inputPortCount <= 0) return   // we write TO the adapter's input port
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty().lowercase()
        // Skip CoreMIDI-style network sessions (no analogue on Android, but be safe).
        if (name.contains("network") && name.contains("session")) return
        val mgr = manager ?: return
        mgr.openDevice(info, { device ->
            if (device == null) return@openDevice
            openDevices.add(device)
            val port = device.openInputPort(0) ?: return@openDevice
            openPorts.add(port)
            sendInitSequence(port)
            val displayName = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            main.post { onConnected?.invoke(displayName) }
        }, main)
    }

    // ---- Sending ----

    /** Disable keyboard mode, set dit duration, set keyer mode, set sidetone. */
    private fun sendInitSequence(port: MidiInputPort) {
        sendNow(port, byteArrayOf(0xB0.toByte(), 0x00, 0x00))                                   // MIDI mode
        sendNow(port, byteArrayOf(0xB0.toByte(), 0x01, minOf(127, ditDurationMs / 2).toByte()))
        sendNow(port, byteArrayOf(0xC0.toByte(), keyerMode.code.toByte()))
        sendNow(port, byteArrayOf(0xB0.toByte(), 0x02, sidetoneMidiNote.toByte()))
    }

    private fun broadcast(message: ByteArray) {
        openPorts.forEach { sendNow(it, message) }
    }

    private fun sendNow(port: MidiInputPort, message: ByteArray) {
        try { port.send(message, 0, message.size) } catch (_: Exception) {}
    }

    // ---- Helpers ----

    /** PARIS standard: dit (ms) = 1200 / WPM. */
    private fun ditDurationMs(wpm: Int): Int = 1200 / maxOf(5, wpm)
}
