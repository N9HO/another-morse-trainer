package app.anothermorsetrainer

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequencer
import javax.sound.midi.Synthesizer

/**
 * Hardware MIDI ports as Java Sound sees them, and a poller for hot-plug.
 *
 * `android.media.midi` and CoreMIDI both call back when a device arrives or
 * leaves. `javax.sound.midi` has no such callback: the device list is simply
 * re-read on each [MidiSystem.getMidiDeviceInfo] call (on Windows the WinMM
 * provider and on Linux the ALSA raw-MIDI provider both re-count their devices
 * when asked). So [Watch] polls once a second and reports the difference —
 * a key plugged in is noticed within a second, which is quicker than anyone
 * gets from the USB socket to the paddle.
 *
 * What is listed, and what is not:
 * - Only hardware ports. The JDK's own software synthesizer and sequencer
 *   (Gervill, "Real Time Sequencer") are skipped by type, and three
 *   pass-through ports that are not keys are skipped by name: Windows'
 *   "Microsoft GS Wavetable Synth" (an output port that would *sound* an RX
 *   buzz through the speakers) and ALSA's "Midi Through".
 * - Windows (WinMM): USB class-compliant MIDI devices, which is what the Vail
 *   Adapter is. A WinMM port is exclusive — if another program (a DAW, the
 *   Vail web client in a browser using Web MIDI) has it open, opening it here
 *   fails, and the key reads as not connected. Bluetooth LE MIDI devices are
 *   not WinMM devices at all and never appear.
 * - Linux (ALSA): raw-MIDI hardware devices (`/dev/snd/midiC*D*`), which is
 *   how the kernel's USB audio driver exposes a class-compliant device. A
 *   BlueZ Bluetooth MIDI device is an ALSA *sequencer* client with no raw-MIDI
 *   node, so it does not appear either. Raw MIDI is also one-opener-at-a-time.
 *   In the Flatpak the sandbox needs `--device=all` to see `/dev/snd` at all.
 */
internal object MidiPorts {

    /** One end of a device. [key] is stable across polls for the same physical port. */
    class Port(val key: String, val name: String, val info: MidiDevice.Info)

    private val skipNames = listOf("gs wavetable", "midi through", "real time sequencer", "gervill")

    /** True when Java Sound's MIDI system cannot be used at all (no provider, no native half). */
    val isUnavailable: Boolean by lazy {
        try { MidiSystem.getMidiDeviceInfo(); false } catch (_: Throwable) { true }
    }

    /** Ports that transmit to us: a key's output. */
    fun keySources(): List<Port> = scan(wantTransmitter = true)

    /** Ports that receive from us: the adapter's input, for the wake sequence and the buzz. */
    fun keySinks(): List<Port> = scan(wantTransmitter = false)

    private fun scan(wantTransmitter: Boolean): List<Port> {
        val infos = try { MidiSystem.getMidiDeviceInfo() } catch (_: Throwable) { return emptyList() }
        val seen = HashMap<String, Int>()
        val out = mutableListOf<Port>()
        for (info in infos) {
            val lower = info.name.orEmpty().lowercase()
            if (skipNames.any { lower.contains(it) }) continue
            val device = try { MidiSystem.getMidiDevice(info) } catch (_: Throwable) { continue }
            if (device is Synthesizer || device is Sequencer) continue
            val ok = if (wantTransmitter) device.maxTransmitters != 0 else device.maxReceivers != 0
            if (!ok) continue
            // Two identical adapters have identical infos; the occurrence count
            // keeps their keys apart.
            val base = "${info.name}|${info.vendor}|${info.description}|${info.version}"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            out.add(Port("$base#$n", displayName(info), info))
        }
        return out
    }

    /**
     * The name to show. The ALSA provider appends the card address
     * ("Vail [hw:2,0,0]"); the description usually carries the product name,
     * but the name alone is what the Vail check in [MidiKeyOutput] reads.
     */
    private fun displayName(info: MidiDevice.Info): String =
        info.name.orEmpty().replace(Regex("\\s*\\[hw:[0-9,]+]$"), "").ifBlank { info.description.orEmpty() }

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "amt-midi-scan").apply { isDaemon = true }
    }

    /** Runs [task] on the MIDI worker thread; opening and closing ports can block. */
    fun runOnWorker(task: () -> Unit) { scheduler.execute { runCatching(task) } }

    /** Runs [task] on the MIDI worker thread after [delayMs]. */
    fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*> =
        scheduler.schedule({ runCatching(task) }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)

    /**
     * Polls one side's port list and reports ports that appeared and ports that
     * went, both on the MIDI worker thread. [stop] ends the polling.
     */
    class Watch(
        private val sources: Boolean,
        private val onAdded: (Port) -> Unit,
        private val onRemoved: (Port) -> Unit
    ) {
        private val known = LinkedHashMap<String, Port>()
        @Volatile private var future: ScheduledFuture<*>? = null
        @Volatile private var running = false

        fun start() {
            running = true
            future = scheduler.scheduleWithFixedDelay({ runCatching { poll() } }, 0, 1000, TimeUnit.MILLISECONDS)
        }

        fun stop() {
            running = false
            future?.cancel(false)
            future = null
            scheduler.execute { known.clear() }
        }

        private fun poll() {
            if (!running) return
            val now = (if (sources) keySources() else keySinks()).associateBy { it.key }
            for ((k, p) in now) if (k !in known) { known[k] = p; onAdded(p) }
            val gone = known.keys.filter { it !in now }
            for (k in gone) known.remove(k)?.let(onRemoved)
        }
    }
}
