package app.anothermorsetrainer.morsekit

/** One directed confusion: [target] was played, [chosen] was answered, [count] times. */
data class ConfusionEntry(val target: Char, val chosen: Char, val count: Int)

/** An unordered confused pair ([a] < [b]) with both directions summed. */
data class ConfusionPair(val a: Char, val b: Char, val count: Int)

/**
 * Tracks which character the learner *actually picked* when they got one
 * wrong — e.g. they heard `X` but answered `Y`. These directed tallies are
 * the raw material for the confusion-pair drills: the characters you mix up
 * in practice are exactly the ones worth drilling head-to-head.
 *
 * Counts are directional ("heard X, picked Y" is tracked separately from
 * "heard Y, picked X") because the two error directions can differ in
 * strength, but [pairs] collapses them into unordered pairs for display.
 *
 * Translated from MorseKit/ConfusionMatrix.swift. Swift's `mutating func` on a
 * value-type `struct` becomes ordinary methods on a `class`. Swift `Codable`
 * would map to a kotlinx.serialization `@Serializable` here; the persisted
 * shape is just the `counts` map, exposed via [snapshot]/[restore] for now.
 */
class ConfusionMatrix {

    /**
     * Directed counts keyed by "target‹US›chosen" (US = unit separator, so the
     * key is unambiguous even though both parts are single characters today).
     */
    private val counts: MutableMap<String, Int> = mutableMapOf()

    companion object {
        private const val US = '\u0001'

        private fun key(target: Char, chosen: Char): String = "$target$US$chosen"

        private fun parse(key: String): Pair<Char, Char>? {
            val parts = key.split(US)
            if (parts.size != 2) return null
            val a = parts[0].firstOrNull() ?: return null
            val b = parts[1].firstOrNull() ?: return null
            return a to b
        }

        // Strongest first; ties broken deterministically by target then chosen.
        private val entryOrder: Comparator<ConfusionEntry> =
            compareByDescending<ConfusionEntry> { it.count }
                .thenBy { it.target }
                .thenBy { it.chosen }

        private val pairOrder: Comparator<ConfusionPair> =
            compareByDescending<ConfusionPair> { it.count }
                .thenBy { it.a }
                .thenBy { it.b }
    }

    /** Note that [target] was played but [chosen] was answered. */
    fun record(target: Char, chosen: Char) {
        if (target == chosen) return
        val k = key(target, chosen)
        counts[k] = (counts[k] ?: 0) + 1
    }

    /**
     * A correct recognition eases a previously-confused pairing (one direction),
     * so pairs the learner has sorted out gradually drop out of rotation.
     */
    fun ease(target: Char, chosen: Char) {
        val k = key(target, chosen)
        val v = counts[k] ?: return
        if (v <= 1) counts.remove(k) else counts[k] = v - 1
    }

    /** How many times [target] was answered as [chosen]. */
    fun count(target: Char, chosen: Char): Int = counts[key(target, chosen)] ?: 0

    val isEmpty: Boolean get() = counts.isEmpty()

    /** Directed confusions, strongest first (ties broken deterministically). */
    fun entries(minCount: Int = 1): List<ConfusionEntry> =
        counts.mapNotNull { (k, v) ->
            if (v < minCount) return@mapNotNull null
            val (t, c) = parse(k) ?: return@mapNotNull null
            ConfusionEntry(t, c, v)
        }.sortedWith(entryOrder)

    /**
     * Unordered confused pairs with both directions summed, strongest first.
     * Handy for a "your most-confused pairs" display.
     */
    fun pairs(minCount: Int = 1): List<ConfusionPair> {
        val summed = mutableMapOf<String, ConfusionPair>()
        for (e in entries()) {
            val lo = minOf(e.target, e.chosen)
            val hi = maxOf(e.target, e.chosen)
            val k = key(lo, hi)
            val existing = summed[k]
            summed[k] = if (existing != null) existing.copy(count = existing.count + e.count)
                        else ConfusionPair(lo, hi, e.count)
        }
        return summed.values
            .filter { it.count >= minCount }
            .sortedWith(pairOrder)
    }

    /** The raw directed counts, for persistence (stand-in for Swift `Codable`). */
    fun snapshot(): Map<String, Int> = counts.toMap()

    /** Restore from a [snapshot]. */
    fun restore(saved: Map<String, Int>) {
        counts.clear()
        counts.putAll(saved)
    }

    override fun equals(other: Any?): Boolean =
        other is ConfusionMatrix && other.counts == counts

    override fun hashCode(): Int = counts.hashCode()
}
