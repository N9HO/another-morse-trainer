package app.anothermorsetrainer.morsekit

import kotlin.math.abs

/**
 * A MIDI key's contacts → the one logical key. The straight-key note keys as
 * it comes (a straight key, or a Vail Adapter's own keyer). Dit and dah from a
 * paddle with no keyer of its own (the N6ARA TinyMIDI, a Morserino-32 Pocket,
 * a Vail Adapter in Passthrough) are timed by [PaddleKeyer]; they used to key
 * as straight-key contacts, so holding dit sounded one long tone. A Vail
 * Adapter's own keyer also sends on notes 1 and 2 (firmware 4.4 and later),
 * already timed, so those key as they come unless the adapter is in
 * Passthrough ([timesNote]).
 *
 * Pure and clock-free: the caller supplies every timestamp. Pinned by
 * `fixtures/midi-keyer.json`, which the iOS harness reads too. Mirrors iOS
 * `MIDIKeyer`.
 */
class MidiKeyer(mode: PaddleKeyer.Mode, wpm: Double) {

    /**
     * One change of the logical key. [timedByApp] is true when the app's
     * keyer timed it (from a dit or dah contact), false when a contact keyed
     * it as it came.
     */
    data class Edge(val isDown: Boolean, val atMs: Double, val timedByApp: Boolean)

    /** The keyer's tone, and each contact keyed as it comes. */
    private sealed interface Half {
        data object Keyer : Half
        data class Held(val paddle: MidiKeyPaddle) : Half
    }

    private var keyer = PaddleKeyer(mode, wpm)
    private val merge = KeyMerge<Half>()
    /** Contacts keyed as they come, held now: the straight contact, and a dit or dah the keyer doesn't time. */
    private val held = mutableSetOf<MidiKeyPaddle>()
    /** A mode and speed [configure] asked for while the keyer was busy. */
    private var pending: Pair<PaddleKeyer.Mode, Double>? = null

    val mode: PaddleKeyer.Mode get() = keyer.mode
    val unitMs: Double get() = keyer.unitMs

    /** True while an element is in progress or a contact keyed as it comes is held. */
    val isBusy: Boolean get() = keyer.isBusy || held.isNotEmpty()

    /** When [advance] is next needed; null when the keyer is idle. */
    val nextDeadlineMs: Double? get() = keyer.nextDeadlineMs

    /**
     * Time the paddles at [mode] and [wpm] from [atMs]. A change made while
     * the keyer is busy waits until it is idle, so nothing in flight is
     * re-timed; of several waiting changes the last wins. Returns the edges
     * the clock owed by [atMs].
     */
    fun configure(mode: PaddleKeyer.Mode, wpm: Double, atMs: Double): List<Edge> {
        val edges = route(keyer.advance(atMs))
        pending = mode to wpm
        takePendingIfIdle()
        return edges
    }

    /**
     * One contact of the key went down or up at [atMs]. A dit or dah goes to
     * the keyer when [timed] ([timesNote]), and keys as it comes otherwise; a
     * release goes wherever its press went. Returns every edge due by then:
     * those the keyer's clock owed first, then this one's.
     */
    fun contact(paddle: MidiKeyPaddle, isDown: Boolean, atMs: Double, timed: Boolean = true): List<Edge> {
        // Run the clock on first, so a change that was waiting for the
        // keyer to go idle is in place before this contact starts anything.
        val edges = route(keyer.advance(atMs)).toMutableList()
        takePendingIfIdle()
        val asItComes = paddle == MidiKeyPaddle.STRAIGHT || (if (isDown) !timed else paddle in held)
        if (asItComes) {
            if ((paddle in held) != isDown) {
                if (isDown) held += paddle else held -= paddle
                merge.update(Half.Held(paddle), isDown)?.let { edges += Edge(it, atMs, timedByApp = false) }
            }
            return edges
        }
        when (paddle) {
            MidiKeyPaddle.STRAIGHT -> {}
            MidiKeyPaddle.DIT -> edges += route(keyer.paddle(PaddleKeyer.Element.DIT, isDown, atMs))
            MidiKeyPaddle.DAH -> edges += route(keyer.paddle(PaddleKeyer.Element.DAH, isDown, atMs))
        }
        return edges
    }

    /** Run the keyer's clock on to [toMs]. */
    fun advance(toMs: Double): List<Edge> {
        val edges = route(keyer.advance(toMs))
        takePendingIfIdle()
        return edges
    }

    /**
     * The key went away (unplugged, or the screen is closing): let go of every
     * contact and cut the keyer's tone at [atMs].
     */
    fun releaseAll(atMs: Double): List<Edge> {
        val edges = route(keyer.advance(atMs)).toMutableList()
        edges += route(keyer.releaseAll(atMs))
        for (paddle in MidiKeyPaddle.entries) {
            if (paddle !in held) continue
            held -= paddle
            merge.update(Half.Held(paddle), false)?.let { edges += Edge(it, atMs, timedByApp = false) }
        }
        takePendingIfIdle()
        return edges
    }

    /**
     * Swap in a keyer with the waiting mode and speed, if the keyer is idle.
     * Only the paddle keyer is replaced: held contacts and the merge
     * carry on, and an idle keyer holds nothing a fresh one would lose.
     */
    private fun takePendingIfIdle() {
        val (mode, wpm) = pending ?: return
        if (keyer.isBusy) return
        pending = null
        if (mode != keyer.mode || abs(1200.0 / wpm.coerceAtLeast(1.0) - keyer.unitMs) > 1e-9) {
            keyer = PaddleKeyer(mode, wpm)
        }
    }

    /** The keyer's tone edges, as changes of the logical key. */
    private fun route(keyed: List<PaddleKeyer.Edge>): List<Edge> =
        keyed.mapNotNull { edge -> merge.update(Half.Keyer, edge.isDown)?.let { Edge(it, edge.atMs, timedByApp = true) } }

    companion object {
        /**
         * Whether the keyer times a dit or dah contact that came on [note]. A
         * Vail Adapter sends its raw paddle contacts on notes 1 and 2 only in
         * Passthrough. In its keyer modes, firmware 4.4 and later sends its own
         * keyer-timed dits and dahs on those notes (earlier firmware sent every
         * element on note 0), and timing them again would add elements: a
         * note-off a moment after the element's end reads as a held paddle. The
         * app sets the adapter's keyer mode, so [adapterPassthrough] is that
         * setting. The other paddle notes come from devices with no keyer mode
         * to set (20/21 the TinyMIDI and the Pocket, 61/62): always timed.
         * Mirrors iOS `MIDIKeyer.timesNote`.
         */
        fun timesNote(note: Int, adapterPassthrough: Boolean): Boolean =
            if (note == 1 || note == 2) adapterPassthrough else true
    }
}
