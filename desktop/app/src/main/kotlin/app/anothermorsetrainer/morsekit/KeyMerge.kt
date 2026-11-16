package app.anothermorsetrainer.morsekit

/**
 * Many physical keys, one logical key (the Sending Analyzer, row-23 of the
 * parity audit).
 *
 * The analyzer's [KeyingRecorder] knows one key: a second key-down while it is
 * down is dropped, and the first key-up ends the mark. With two things able to
 * key at once (the on-screen key, a Vail adapter's paddles, the keyboard) a
 * release of one while the other is still held would cut the mark short.
 * This is the rule the iOS `SendingKeyer.handle` has always applied: the key is
 * down while ANY source is held, up only when the last one is released.
 *
 * Pure: no clock, no sidetone. The caller stamps whatever edge this reports.
 * Pinned by `fixtures/gap-analyzer-merge.json`. Twin of Swift `KeyMerge`.
 */
class KeyMerge<S> {
    private val held = mutableSetOf<S>()

    /** Whether the logical key is down now. */
    val isDown: Boolean get() = held.isNotEmpty()

    /**
     * [source] went down or up. Returns the new state of the logical key when
     * this changed it (true = down), otherwise null.
     */
    fun update(source: S, isDown: Boolean): Boolean? {
        val was = held.isNotEmpty()
        if (isDown) held.add(source) else held.remove(source)
        val now = held.isNotEmpty()
        return if (now != was) now else null
    }

    /** Drop every held source. Returns false (an "up" edge) if the key was down, otherwise null. */
    fun releaseAll(): Boolean? {
        val was = held.isNotEmpty()
        held.clear()
        return if (was) false else null
    }
}
