package app.anothermorsetrainer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
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
    fun start(
        onKey: (Boolean) -> Unit,
        onConnected: (String?) -> Unit,
        onKeyTimed: ((isDown: Boolean, atNanos: Long) -> Unit)? = null
    ) {
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
        input.onKeyTimed = onKeyTimed
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

/**
 * A hardware Morse key (Vail Adapter / BLE-MIDI) typing into a screen that
 * otherwise takes the keyboard — the Pileup Runner and Contest box, Type It,
 * QRQ, Daily Dit, Defender's typed copy and Journey's choices (#251).
 *
 * The adapter types nothing on its own: out of the box it is a HID keyboard
 * sending Ctrl (or `[` `]`) for dit and dah, which a text field ignores, and
 * it sends MIDI only once [HardwareKey] wakes it. Only the screens that answer
 * by keying ran one, so everywhere else the key was dead. This runs the same
 * [SendingKeyer] + [HardwareKey] pair those screens use — adapter wake,
 * sidetone, decoder — and hands each newly decoded run of text to [onText].
 * Once the key has been idle for two word gaps (after the decoder's own word
 * space), the operator has stopped sending: [onPause] fires and the decoder
 * starts clean.
 *
 * Compose it only while the screen is taking input; leaving composition stops
 * the key. Hardware keys only — the on-screen key stays with the modes that
 * answer by keying. Typing still works alongside it. Twin of the iOS
 * `HardwareKeyInput` view modifier in SendingKeyerView.swift.
 */
@Composable
fun HardwareKeyInput(onText: (String) -> Unit, onPause: () -> Unit = {}) {
    val context = LocalContext.current
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey(context) }
    // A keyer mode or speed picked in the Settings sheet reaches the adapter now.
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    val currentOnText by rememberUpdatedState(onText)
    val currentOnPause by rememberUpdatedState(onPause)
    // How much of the decoded text has already been handed on.
    var fed by remember { mutableIntStateOf(0) }

    // Listening for the key and waking the adapter start with the screen; the
    // sidetone does not. Starting it takes exclusive audio focus, which would
    // pause the user's music on every screen this sits on even with no key
    // attached (MorsePlayer's rule: focus only while something sounds). So it
    // starts when a key is connected — or on its first key-down, whichever
    // comes first — and stops when the key goes away.
    DisposableEffect(Unit) {
        keyer.scope = scope
        midi.start(
            onKey = { down ->
                if (down) keyer.start()
                keyer.touchKey(down)
            },
            onConnected = { name -> if (name != null) keyer.start() else keyer.stop() }
        )
        onDispose {
            midi.stop()
            keyer.stop()
        }
    }

    val decoded = keyer.decodedText
    LaunchedEffect(decoded) {
        if (decoded.length < fed) fed = 0 // the decoder was cleared
        if (decoded.length > fed) {
            val chunk = decoded.substring(fed)
            fed = decoded.length
            currentOnText(chunk)
        }
    }

    // Restarted by every key edge — counted, so a down/up pair that lands in
    // one frame still restarts it — and re-checked on waking. A pause with
    // nothing decoded sends nothing.
    val edges = keyer.edgeCount
    val hasText = decoded.isNotEmpty()
    LaunchedEffect(edges, hasText) {
        if (keyer.isKeying || !hasText) return@LaunchedEffect
        delay((keyer.wordGapMs * 2).toLong())
        if (keyer.isKeying || keyer.edgeCount != edges) return@LaunchedEffect
        currentOnPause()
        keyer.clear()
        fed = 0
    }
}

/** Append a decoded run to a text box: no leading space into an empty box or after a space. */
internal fun appendKeyed(text: String, chunk: String): String {
    val add = if (text.isEmpty() || text.endsWith(" ")) chunk.trimStart(' ') else chunk
    return text + add
}
