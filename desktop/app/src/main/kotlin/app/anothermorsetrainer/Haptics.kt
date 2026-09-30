package app.anothermorsetrainer

/**
 * Tactile feedback, which a desktop computer does not have.
 *
 * Kept as a class with the Android port's four calls so every ported screen
 * reads the same; each is a no-op here. The Settings screen hides the haptics
 * switch on desktop, and PARITY.md records it as a platform limitation (no
 * vibration motor), the way the Android app hides MIDI controls on a device
 * without `FEATURE_MIDI`.
 */
class Haptics {
    /** A correct answer. */
    fun success() {}

    /** A wrong answer. */
    fun error() {}

    /** A light tick for selections. */
    fun selection() {}

    /** A soft tap for primary taps. */
    fun tap() {}

    companion object {
        /** False on every desktop: there is no vibration hardware to drive. */
        const val isAvailable: Boolean = false
    }
}
