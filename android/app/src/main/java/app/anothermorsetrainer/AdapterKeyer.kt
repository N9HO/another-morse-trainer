package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which kind of key is plugged into the Vail Adapter, in one place.
 *
 * This describes the operator's *hardware* — a straight key or an iambic
 * paddle — rather than anything about a drill, so every screen that wakes a key
 * shares one answer instead of keeping its own. [HardwareKey] (Sending
 * Practice, Common Words, Rapid Fire) and the Vail repeater both read and write
 * here, which is what lets a paddle set once stay a paddle everywhere (#43).
 *
 * Why the app asserts a mode at all: the adapter boots in HID keyboard mode and
 * sends no MIDI until it receives a Control Change, so waking it necessarily
 * includes a keyer mode. Until this was settable outside the repeater screen
 * that mode was always [MidiKeyOutput.KeyerMode.STRAIGHT_KEY] — which is why an
 * iambic paddle configured at vailmorse.com reverted the moment AMT opened it.
 * The wake still asserts a mode; it now asserts the operator's.
 *
 * The mode is a Compose state as well as a stored pref, so a screen that is
 * *currently holding the adapter open* recomposes when the Settings sheet drawn
 * over it changes the mode, and can push the change down the port it already
 * has. Storing it alone was not enough: the new mode then waited for the next
 * wake, which is why changing it looked like it did nothing at all until you
 * backed out of the module and came in again (issue #46).
 */
object AdapterKeyer {

    /** Shared with the Vail repeater, which keeps its own settings alongside. */
    private const val PREFS = "amt_vail"
    private const val KEY_MODE = "keyerMode"

    /** A straight key is the safe assumption: it is what a bare adapter keys as. */
    val DEFAULT_MODE = MidiKeyOutput.KeyerMode.STRAIGHT_KEY

    private var prefs: SharedPreferences? = null
    private var current by mutableStateOf(DEFAULT_MODE)

    private fun prefsFor(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Seed the observable mode from storage. Called once, from `MainActivity`. */
    fun init(context: Context) {
        val p = prefsFor(context)
        prefs = p
        current = MidiKeyOutput.KeyerMode.fromCode(p.getInt(KEY_MODE, DEFAULT_MODE.code))
    }

    /**
     * The operator's key. Reading this from a Composable subscribes it to
     * changes made anywhere else — that is what makes the push in
     * [AdapterConfigSync] fire. The fallback covers a caller that runs before
     * [init]; it reads storage directly rather than writing the state, so it is
     * safe to call during composition.
     */
    fun mode(context: Context): MidiKeyOutput.KeyerMode =
        if (prefs != null) current
        else MidiKeyOutput.KeyerMode.fromCode(prefsFor(context).getInt(KEY_MODE, DEFAULT_MODE.code))

    fun setMode(context: Context, mode: MidiKeyOutput.KeyerMode) {
        current = mode
        prefsFor(context).also { prefs = it }.edit().putInt(KEY_MODE, mode.code).apply()
    }

    /**
     * Whether the adapter times the sending itself for [mode]. A straight key
     * and passthrough key from the contact directly; the rest are the adapter's
     * own keyer, sending at whatever speed the screen pushed — the practice
     * speed in a drill, the keyer slider on the repeater screen.
     */
    fun adapterTimesSending(mode: MidiKeyOutput.KeyerMode): Boolean =
        mode != MidiKeyOutput.KeyerMode.STRAIGHT_KEY && mode != MidiKeyOutput.KeyerMode.PASSTHROUGH
}
