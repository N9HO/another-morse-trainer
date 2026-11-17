package app.anothermorsetrainer.morsekit

/**
 * A MIDI key's notes → the one logical key (#380), between the note parser
 * and the app's paddle keyer. Pure and clock-free: the caller supplies every
 * time (in its own clock's units), runs the keyer (`PaddleKeyerDriver` in the
 * app) on its own clock, and feeds the keyer's tone back in. Pinned by
 * `fixtures/midi-key-merge.json`, which the iOS harness reads too. Mirrors iOS
 * `MIDIKeyMerge`.
 */
class MidiKeyMerge {

    /** What the caller does for one input, in order. */
    sealed interface Action {
        /** The logical key went down or up, at [at]. */
        data class Key(val isDown: Boolean, val at: Long) : Action
        /** Press or release one paddle of the app's keyer. */
        data class Paddle(val element: PaddleKeyer.Element, val isDown: Boolean) : Action
        /** Stop the app's keyer: let go of both paddles and cut its tone now. */
        data object StopKeyer : Action
    }

    /** Held notes down now: they key the logical key as they come. */
    private val held = mutableSetOf<Int>()

    /**
     * Raw paddle notes down now, by the paddle each pressed when it went down,
     * so its release goes the same way even if the adapter's mode changed
     * meanwhile, and two notes on one paddle (1 and 20) hold it until both are up.
     */
    private val keyed = mutableMapOf<Int, PaddleKeyer.Element>()
    private var keyerDown = false
    private var reported = false

    /** Whether a note holds a paddle of the keyer now. The app reads its settings into the keyer only while none does. */
    val holdsAPaddle: Boolean get() = keyed.isNotEmpty()

    /**
     * One note went down or up at [at]. A press goes where
     * [MidiKeyParser.routeForNote] sends it for the adapter's Keyer mode
     * ([rawPaddles]: `AdapterKeyer.sendsRawPaddles`); a release goes where its
     * press went.
     */
    fun note(note: Int, isDown: Boolean, rawPaddles: Boolean, at: Long): List<Action> {
        if (!isDown) {
            val element = keyed.remove(note)
            if (element != null) {
                // Another note may still hold this paddle.
                return if (element in keyed.values) emptyList() else listOf(Action.Paddle(element, false))
            }
            if (!held.remove(note)) return emptyList()
            return report(at)
        }
        if (note in held || note in keyed) return emptyList()
        return when (MidiKeyParser.routeForNote(note, rawPaddles)) {
            null -> emptyList()
            MidiKeyRoute.HELD -> {
                held += note
                report(at)
            }
            MidiKeyRoute.KEYER_DIT -> press(note, PaddleKeyer.Element.DIT)
            MidiKeyRoute.KEYER_DAH -> press(note, PaddleKeyer.Element.DAH)
        }
    }

    /** The keyer's tone went on or off at [at], the time it scheduled. */
    fun keyerTone(isDown: Boolean, at: Long): List<Action> {
        keyerDown = isDown
        return report(at)
    }

    /**
     * Let go of everything at [at]: the screen is going away, or the key was
     * unplugged. The keyer is always stopped first (its element is cut, not
     * finished): with no paddle held and its tone off it may still owe a
     * remembered dit or dah, or iambic B's extra element, which the merge
     * can't see, and stopping an idle keyer emits nothing. Then the key goes
     * up now. The stopped keyer's own tone-off then changes nothing.
     */
    fun releaseAll(at: Long): List<Action> {
        val actions = listOf<Action>(Action.StopKeyer)
        held.clear()
        keyed.clear()
        keyerDown = false
        return actions + report(at)
    }

    private fun press(note: Int, element: PaddleKeyer.Element): List<Action> {
        val alreadyHeld = element in keyed.values
        keyed[note] = element
        return if (alreadyHeld) emptyList() else listOf(Action.Paddle(element, true))
    }

    /** Down while a held note is down or the keyer's tone is on; an edge only when that changes. */
    private fun report(at: Long): List<Action> {
        val now = held.isNotEmpty() || keyerDown
        if (now == reported) return emptyList()
        reported = now
        return listOf(Action.Key(now, at))
    }
}
