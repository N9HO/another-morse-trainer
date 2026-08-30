package app.anothermorsetrainer

import android.content.Context

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
 */
object AdapterKeyer {

    /** Shared with the Vail repeater, which keeps its own settings alongside. */
    private const val PREFS = "amt_vail"
    private const val KEY_MODE = "keyerMode"

    /** A straight key is the safe assumption: it is what a bare adapter keys as. */
    val DEFAULT_MODE = MidiKeyOutput.KeyerMode.STRAIGHT_KEY

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): MidiKeyOutput.KeyerMode =
        MidiKeyOutput.KeyerMode.fromCode(prefs(context).getInt(KEY_MODE, DEFAULT_MODE.code))

    fun setMode(context: Context, mode: MidiKeyOutput.KeyerMode) {
        prefs(context).edit().putInt(KEY_MODE, mode.code).apply()
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
