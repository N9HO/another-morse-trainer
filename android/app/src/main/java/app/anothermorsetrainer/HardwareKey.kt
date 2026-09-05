package app.anothermorsetrainer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * A hardware Morse key (Vail Adapter / BLE-MIDI paddle) wired up for practice.
 *
 * Reading the key is only half of it. The Vail Adapter boots in HID keyboard
 * mode and **emits no MIDI note events at all until it receives a Control
 * Change** — so opening its output port and waiting, as the practice screens
 * used to do, gets you the device's name and nothing else: it enumerates, the
 * UI names it, and every paddle press goes nowhere. Only the Vail repeater ran
 * [MidiKeyOutput], so only the repeater ever woke the adapter; Sending
 * Practice, Common Words, and Rapid Fire were input-only and therefore dead
 * with a USB Vail Adapter (issue #42).
 *
 * This pairs the two so the wake can't be forgotten again: [start] opens the
 * key for reading *and* broadcasts the init sequence that puts the adapter into
 * MIDI mode. A BLE-MIDI key that was never in keyboard mode simply ignores the
 * init sequence, so this is safe for every supported key.
 *
 * The Vail repeater keeps driving [MidiKeyInput]/[MidiKeyOutput] directly — it
 * needs the piezo and live keyer-config paths this wrapper deliberately omits,
 * and one owner per device input port avoids two clients racing to open it.
 */
class HardwareKey(context: Context) {

    private val app = context.applicationContext
    private val input = MidiKeyInput(context)
    private val output = MidiKeyOutput(context)

    /**
     * The keyer mode describes the user's *hardware* (straight key vs iambic
     * paddle), so reuse the one they chose in Settings (or on the Vail repeater
     * screen — [AdapterKeyer] is the one store behind both) rather than
     * resetting their paddle every time practice starts. Sidetone is what
     * they're practising at and comes from [Settings], as does the speed the
     * adapter's own keyer sends at — in a drill that is the speed being drilled.
     */
    private val storedKeyerMode: MidiKeyOutput.KeyerMode
        get() = AdapterKeyer.mode(app)

    /** True on devices that expose any MIDI support at all. */
    val isSupported: Boolean get() = input.isSupported

    /**
     * True when MIDI cannot be used here at all, as opposed to no key being
     * connected yet — the two read differently: the first is a dead end, the
     * second just needs a key plugged in or a BLE key connected.
     */
    val isUnavailable: Boolean get() = input.isUnavailable

    /**
     * Begin listening. [onKey] fires (on the main thread) with `true` on
     * key-down and `false` on key-up; [onConnected] reports the connected key's
     * name — null when none is attached yet, and again when the last unplugs.
     *
     * The adapter is woken with the user's current sidetone and speed so its
     * own piezo and internal keyer match what the app is playing.
     */
    fun start(onKey: (Boolean) -> Unit, onConnected: (String?) -> Unit) {
        output.configure(
            keyerMode = storedKeyerMode,
            wpm = Settings.characterWpm.roundToInt(),
            sidetoneMidiNote = midiNoteForHz(Settings.sidetoneHz)
        )
        // Wake first: the adapter has to be in MIDI mode before anything it
        // sends is worth listening for. Both sides re-attach on hot-plug.
        //
        // The output side opens *every* device that accepts input, because the
        // adapter enumerates under several names across firmwares ("Vail",
        // "Adafruit QT Py M0") — so its connection reports are not a reliable
        // answer to "what is keying?" and are deliberately dropped. The name in
        // the UI comes from the input side, which is the half actually keying.
        output.start { }
        input.start(onKey = onKey, onConnected = onConnected)
    }

    fun stop() {
        input.stop()
        output.stop()
    }

    /** User-triggered retry of the wake sequence, for a key plugged in late. */
    fun wakeAdapter() = output.wakeAdapter()

    /**
     * Re-push the key and practice settings to an adapter that is already open.
     *
     * [start] configures the adapter once, from the settings as they stood when
     * the screen opened. Everything reachable from the mid-session Settings
     * sheet can move afterwards — the keyer mode itself, and the speed the
     * adapter's own keyer sends at in the modes where
     * [AdapterKeyer.adapterTimesSending] holds — and until this existed none of
     * it reached the adapter until the next wake, i.e. not until the operator
     * left the module and came back (issue #46). Only differences go on the
     * wire, so calling this is cheap.
     */
    fun applyConfig() {
        output.applyConfig(
            keyerMode = storedKeyerMode,
            wpm = Settings.characterWpm.roundToInt(),
            sidetoneMidiNote = midiNoteForHz(Settings.sidetoneHz)
        )
    }

    private companion object {
        /** Nearest MIDI note to a frequency in Hz (A4 = 69 = 440 Hz). */
        fun midiNoteForHz(hz: Double): Int =
            if (hz <= 0) 72 else (69 + 12 * log2(hz / 440.0)).roundToInt().coerceIn(0, 127)
    }
}

/**
 * Keep [key]'s adapter in step with the settings for as long as it is on screen.
 *
 * The Settings sheet is drawn *over* a running session rather than replacing
 * it, so the screen holding the adapter open is still composed while the
 * operator changes their keyer mode or speed. Reading those settings here
 * subscribes to them, and every change pushes down the port that is already
 * open — no second client racing for the device's input port, which is the
 * reason the setting used to only store itself and wait (issue #46).
 */
@Composable
fun AdapterConfigSync(key: HardwareKey) {
    val context = LocalContext.current
    val mode = AdapterKeyer.mode(context)
    val wpm = Settings.characterWpm
    val sidetoneHz = Settings.sidetoneHz
    LaunchedEffect(mode, wpm, sidetoneHz) { key.applyConfig() }
}
