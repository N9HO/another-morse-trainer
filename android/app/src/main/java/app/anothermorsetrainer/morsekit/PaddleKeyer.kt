package app.anothermorsetrainer.morsekit

/**
 * A software iambic keyer for the on-screen paddles (#233): turns dit and dah
 * paddle presses into timed key-down / key-up edges, the way a hardware keyer
 * turns a paddle into a keyed tone.
 *
 * Pure and clock-free — the caller supplies every timestamp — so the same
 * script of paddle events always yields the same edges. The app drives it
 * from a coroutine ([advance] at [nextDeadlineMs]); `PaddleKeyerTest` drives
 * it from `fixtures/paddle-keyer.json`, whose `derivation` block is the spec
 * this implements:
 *
 * - One unit is 1200 / wpm ms. A dit keys 1 unit, a dah 3; each element is
 *   followed by 1 unit of silence, and the element plus that silence is its
 *   *slot*. What to send next is decided at the slot's end.
 * - A press while idle starts its element at once. A press while busy is
 *   latched (dit / dah memory); starting an element clears its own latch.
 * - Iambic A / B: at a slot's end send the opposite element if its paddle is
 *   held or latched, else the same one if held or latched, else stop.
 *   B also latches the opposite paddle if it is already held when an element
 *   starts, which is what sends one more element after a squeeze is let go.
 * - Ultimatic: if both are wanted, the most recently pressed paddle wins.
 * - A deadline at the same instant as a paddle event is processed first.
 *
 * Not the Vail Adapter's keyer (`MidiKeyOutput.KeyerMode`), which times a
 * *hardware* paddle inside the adapter; this one times the touch paddles.
 * Mirrors iOS `PaddleKeyer` (ios/Sources/MorseKit/PaddleKeyer.swift).
 */
class PaddleKeyer(val mode: Mode, wpm: Double) {

    enum class Mode(val id: String) {
        IAMBIC_A("iambicA"), IAMBIC_B("iambicB"), ULTIMATIC("ultimatic");

        companion object {
            fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: IAMBIC_A
        }
    }

    enum class Element(val id: String) {
        DIT("dit"), DAH("dah");

        val opposite: Element get() = if (this == DIT) DAH else DIT
    }

    /** One key transition: the tone starts ([isDown]) or stops, at [atMs]. */
    data class Edge(val isDown: Boolean, val atMs: Double, val element: Element)

    val unitMs: Double = 1200.0 / wpm.coerceAtLeast(1.0)

    private val held = BooleanArray(2)
    private val latched = BooleanArray(2)
    private val pressedAt = DoubleArray(2) { Double.NEGATIVE_INFINITY }

    /** The element in progress (tone or trailing silence); null when idle. */
    private var current: Element? = null
    private var toneOn = false
    private var toneEndMs = 0.0
    private var slotEndMs = 0.0

    /** True while an element or its trailing silence is in progress. */
    val isBusy: Boolean get() = current != null

    /**
     * When the keyer next needs [advance]: the end of the tone that is
     * sounding, or the end of the slot. null when idle.
     */
    val nextDeadlineMs: Double?
        get() = if (current == null) null else if (toneOn) toneEndMs else slotEndMs

    /**
     * A paddle went down or up at [atMs]. Returns every edge due up to and
     * including that instant — those the clock owed first, then any this
     * event starts.
     */
    fun paddle(element: Element, isDown: Boolean, atMs: Double): List<Edge> {
        val edges = advance(atMs).toMutableList()
        val i = element.ordinal
        if (isDown) {
            if (held[i]) return edges
            held[i] = true
            pressedAt[i] = atMs
            if (current == null) edges += start(element, atMs) else latched[i] = true
        } else {
            held[i] = false
        }
        return edges
    }

    /** Run the clock on to [toMs], returning every edge due by then. */
    fun advance(toMs: Double): List<Edge> {
        val edges = mutableListOf<Edge>()
        while (true) {
            val deadline = nextDeadlineMs ?: break
            if (deadline > toMs) break
            val element = current!!
            if (toneOn) {
                toneOn = false
                edges += Edge(false, toneEndMs, element)
            } else {
                current = null
                decide(after = element)?.let { edges += start(it, deadline) }
            }
        }
        return edges
    }

    /**
     * Let go of both paddles and cut any tone at [atMs]: the screen is going
     * away. Returns the key-up if a tone was sounding.
     */
    fun releaseAll(atMs: Double): List<Edge> {
        held.fill(false)
        latched.fill(false)
        val element = current
        val wasOn = toneOn
        current = null
        toneOn = false
        return if (wasOn && element != null) listOf(Edge(false, atMs, element)) else emptyList()
    }

    private fun wanted(e: Element) = held[e.ordinal] || latched[e.ordinal]

    private fun decide(after: Element): Element? = when (mode) {
        Mode.IAMBIC_A, Mode.IAMBIC_B -> when {
            wanted(after.opposite) -> after.opposite
            wanted(after) -> after
            else -> null
        }
        Mode.ULTIMATIC -> {
            val dit = wanted(Element.DIT)
            val dah = wanted(Element.DAH)
            when {
                dit && dah ->
                    if (pressedAt[Element.DAH.ordinal] > pressedAt[Element.DIT.ordinal]) Element.DAH else Element.DIT
                dit -> Element.DIT
                dah -> Element.DAH
                else -> null
            }
        }
    }

    private fun start(e: Element, atMs: Double): Edge {
        current = e
        toneOn = true
        toneEndMs = atMs + (if (e == Element.DIT) 1 else 3) * unitMs
        slotEndMs = toneEndMs + unitMs
        latched[e.ordinal] = false
        if (mode == Mode.IAMBIC_B && held[e.opposite.ordinal]) latched[e.opposite.ordinal] = true
        return Edge(true, atMs, e)
    }
}
